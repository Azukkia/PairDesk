package io.github.azukkia.pairdesk.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewConfiguration
import io.github.azukkia.pairdesk.AppGraph
import io.github.azukkia.pairdesk.core.signaling.Caps
import io.github.azukkia.pairdesk.net.ConnectRequest
import io.github.azukkia.pairdesk.net.ConnectResult
import io.github.azukkia.pairdesk.net.OutgoingSession
import io.github.azukkia.pairdesk.net.SessionKind
import io.github.azukkia.pairdesk.rtc.RtcEvent
import io.github.azukkia.pairdesk.rtc.RtcRole
import io.github.azukkia.pairdesk.rtc.RtcSession
import io.github.azukkia.pairdesk.viewer.Cancellable
import io.github.azukkia.pairdesk.viewer.GestureConfig
import io.github.azukkia.pairdesk.viewer.GestureEngine
import io.github.azukkia.pairdesk.viewer.InputMode
import io.github.azukkia.pairdesk.viewer.KeyboardController
import io.github.azukkia.pairdesk.viewer.ModifierKeys
import io.github.azukkia.pairdesk.viewer.PeerInfo
import io.github.azukkia.pairdesk.viewer.PointerController
import io.github.azukkia.pairdesk.viewer.Quality
import io.github.azukkia.pairdesk.viewer.RemoteLayout
import io.github.azukkia.pairdesk.viewer.Scheduler
import io.github.azukkia.pairdesk.viewer.SoftKeyboard
import io.github.azukkia.pairdesk.viewer.ViewerCore
import io.github.azukkia.pairdesk.viewer.ViewerLink
import io.github.azukkia.pairdesk.viewer.Viewport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/** [Scheduler] on a [Handler] (the main thread). */
class HandlerScheduler(private val handler: Handler = Handler(Looper.getMainLooper())) : Scheduler {
    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        val runnable = Runnable { task() }
        handler.postDelayed(runnable, delayMs.coerceAtLeast(0))
        return Cancellable { handler.removeCallbacks(runnable) }
    }
}

/**
 * A running "control a computer" session, app-scoped (it outlives its screen,
 * e.g. while the phone rotates or the user looks at another app): the
 * [RtcSession], the platform-free [ViewerCore], the view state ([viewport])
 * and the input pipeline (gestures → [pointer], soft keyboard → [keyboard]).
 * Created and used on the main thread.
 */
class ViewerSession internal constructor(
    private val graph: AppGraph,
    val outgoing: OutgoingSession,
    private val onLiveChanged: () -> Unit,
) {
    val sid: String = outgoing.sid
    private val context: Context = graph.appContext
    private val handler = Handler(Looper.getMainLooper())
    val scheduler: Scheduler = HandlerScheduler(handler)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val peer = PeerInfo(
        id = outgoing.peerId,
        name = outgoing.peerName,
        platform = outgoing.session.peerPlatform,
        version = outgoing.session.peerVersion,
    )

    /** Null when the signaling session was already over. */
    val rtc: RtcSession? = graph.rtc.open(sid, RtcRole.CONTROLLER)

    private var closeJob: Runnable? = null

    private val link = object : ViewerLink {
        override fun sendControl(message: JsonObject): Boolean = rtc?.sendControl(message) ?: false

        override fun sendInput(events: JsonArray) {
            rtc?.sendInput(events)
        }

        override fun sendPointer(events: JsonArray) {
            rtc?.sendPointer(events)
        }

        override fun writeClipboard(text: String) {
            val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
            runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("PairDesk", text)) }
                .onFailure { graph.log.warn("[viewer] clipboard write failed: ${it.message}") }
        }

        override fun end(reason: String) {
            graph.network.endOutgoing(sid, reason)
            // A moment for `bye` and the last input events to leave on the data channels.
            if (closeJob == null) {
                closeJob = Runnable { rtc?.close() }.also { handler.postDelayed(it, CLOSE_DELAY_MS) }
            }
        }

        override fun saveQuality(quality: Quality) = graph.settings.updateViewer { it.copy(quality = quality.wire) }

        override fun saveClipboardSync(enabled: Boolean) = graph.settings.updateViewer { it.copy(clipboardSync = enabled) }
    }

    private val prefs get() = graph.settings.viewer.value

    val core = ViewerCore(
        link = link,
        scheduler = scheduler,
        peer = peer,
        caps = Caps.from(outgoing.session.info?.get("caps")),
        quality = Quality.fromWire(prefs.quality),
        clipboardSync = prefs.clipboardSync,
    )

    private val density = context.resources.displayMetrics.density

    val viewport = Viewport(InputMode.fromWire(prefs.inputMode) ?: defaultInputMode(context))

    private val _remoteLayout = MutableStateFlow(RemoteLayout.fromWire(prefs.remoteLayout) ?: RemoteLayout.guess(Locale.getDefault()))

    /** Keyboard layout of the computer (shortcuts typed with Ctrl / Alt / Win). */
    val remoteLayout: StateFlow<RemoteLayout> = _remoteLayout.asStateFlow()

    private val _inputMode = MutableStateFlow(viewport.mode)
    val inputMode: StateFlow<InputMode> = _inputMode.asStateFlow()

    val modifiers = ModifierKeys()

    val keyboard = KeyboardController(core.input, modifiers, layout = { _remoteLayout.value }, canControl = { core.canControl })

    private val _touches = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** A finger touched the remote screen (the toolbar hides). */
    val touches: SharedFlow<Unit> = _touches.asSharedFlow()

    /** Haptic feedback of a long press, set by the view. */
    var hapticFeedback: () -> Unit = {}

    val pointer = PointerController(
        viewport = viewport,
        sender = core.input,
        modifiers = keyboard,
        scheduler = scheduler,
        density = density,
        canControl = { core.canControl },
        remoteWidth = { core.state.value.currentDisplay?.width },
        onTouch = { _touches.tryEmit(Unit) },
        onLongPressFeedback = { hapticFeedback() },
    )

    val gestures: GestureEngine

    val softKeyboard = SoftKeyboard(keyboard)

    init {
        val vc = ViewConfiguration.get(context)
        gestures = GestureEngine(
            config = GestureConfig(
                touchSlop = vc.scaledTouchSlop.toFloat(),
                doubleTapSlop = vc.scaledDoubleTapSlop.toFloat(),
                density = density,
                longPressMs = ViewConfiguration.getLongPressTimeout().toLong().coerceIn(350, 600),
            ),
            scheduler = scheduler,
            listener = pointer,
            isZoomed = { viewport.zoom.isZoomed },
        )
        gestures.mode = viewport.mode
        core.onInputLost = {
            pointer.forgetDrag()
            gestures.reset()
        }

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            graph.network.sessionEnded.filter { it.sid == sid }.collect { core.onSessionClosed(it.reason, it.byPeer) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            core.state.map { it.ended }.distinctUntilChanged().collect { ended ->
                if (ended) {
                    modifiers.clear()
                    softKeyboard.reset()
                }
                onLiveChanged()
            }
        }
        val r = rtc
        if (r == null || !graph.network.isSessionActive(sid)) {
            core.onSessionClosed("closed", byPeer = false)
        } else {
            scope.launch { r.events.collect(::onRtcEvent) }
            scope.launch {
                while (isActive && !core.isEnded) {
                    delay(STATS_INTERVAL_MS)
                    if (!core.isEnded) core.onStats(r.stats())
                }
            }
            core.start()
        }
    }

    private fun onRtcEvent(event: RtcEvent) {
        when (event) {
            is RtcEvent.State -> core.onConnectionState(event.state)
            RtcEvent.ControlOpen -> core.onControlOpen()
            RtcEvent.ControlClose -> core.onControlClose()
            is RtcEvent.Control -> core.onControl(event.message)
            is RtcEvent.Track -> if (event.kind == "audio") {
                core.onAudioTrack()
                if (core.state.value.muted) rtc?.setRemoteAudioEnabled(false)
            }
            is RtcEvent.Input -> Unit
        }
    }

    val isLive: Boolean get() = !core.isEnded

    // ───────────────────────────── actions ─────────────────────────────

    fun setInputMode(mode: InputMode) {
        viewport.mode = mode
        gestures.mode = mode
        _inputMode.value = mode
        graph.settings.updateViewer { it.copy(inputMode = mode.wire) }
    }

    fun setRemoteLayout(layout: RemoteLayout) {
        _remoteLayout.value = layout
        graph.settings.updateViewer { it.copy(remoteLayout = layout.wire) }
    }

    fun setMuted(muted: Boolean) {
        core.setMuted(muted)
        rtc?.setRemoteAudioEnabled(!muted)
    }

    /** Sends the phone's clipboard to the computer (toolbar button). */
    fun sendPhoneClipboard() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val text = runCatching {
            clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        }.getOrNull()
        core.sendClipboard(text)
    }

    fun disconnect() = core.disconnect()

    /** Connects again to the same computer with the same password (ended screen). */
    suspend fun reconnect(): ConnectResult = graph.network.connect(
        ConnectRequest(peerId = peer.id, password = null, remember = false, kind = SessionKind.CONTROL, prs = outgoing.prs),
    )

    internal fun dispose() {
        if (!core.isEnded) core.disconnect()
        scope.cancel()
        gestures.reset()
        pointer.dispose()
        core.dispose()
        if (closeJob == null) handler.postDelayed({ rtc?.close() }, CLOSE_DELAY_MS)
    }

    companion object {
        private const val STATS_INTERVAL_MS = 1_000L
        private const val CLOSE_DELAY_MS = 200L
        private const val TABLET_MIN_WIDTH_DP = 600

        /** Phones: touchpad (precise on a small picture); tablets: direct touch. */
        fun defaultInputMode(context: Context): InputMode =
            if (context.resources.configuration.smallestScreenWidthDp >= TABLET_MIN_WIDTH_DP) {
                InputMode.DIRECT
            } else {
                InputMode.TOUCHPAD
            }
    }
}
