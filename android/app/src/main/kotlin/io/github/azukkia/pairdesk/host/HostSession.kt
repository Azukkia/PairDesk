package io.github.azukkia.pairdesk.host

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.azukkia.pairdesk.AppGraph
import io.github.azukkia.pairdesk.core.signaling.SignalingSession
import io.github.azukkia.pairdesk.rtc.RtcEvent
import io.github.azukkia.pairdesk.rtc.RtcRole
import io.github.azukkia.pairdesk.rtc.RtcSession
import io.github.azukkia.pairdesk.rtc.RtcState
import io.github.azukkia.pairdesk.session.HandlerScheduler
import io.github.azukkia.pairdesk.session.Notifications
import io.github.azukkia.pairdesk.viewer.PeerInfo
import io.github.azukkia.pairdesk.viewer.Quality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * A computer viewing (and maybe controlling) this phone (docs/PROTOCOL.md
 * 5.2), from the user's consent to the end: `accepted`, the foreground
 * service of type mediaProjection, the screen capture (org.webrtc
 * ScreenCapturerAndroid, at most 1920 pixels long, 30 fps), the
 * [RtcSession] (this phone offers, with the usual data channels), the
 * [HostCore] logic, the remote input ([HostInput] on its own thread, applied
 * by [PairDeskAccessibilityService]), rotations, and the screen kept on.
 * Created and used on the main thread.
 */
class HostSession internal constructor(
    private val graph: AppGraph,
    private val session: SignalingSession,
    /** The result of the screen capture consent (MediaProjectionManager.createScreenCaptureIntent). */
    private val projection: Intent,
    private val onChanged: () -> Unit,
) {
    val sid: String = session.sid
    private val context: Context = graph.appContext
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val log = graph.log

    val peer = PeerInfo(id = session.peerId, name = session.peerName, platform = session.peerPlatform, version = session.peerVersion)

    private val inputThread = HandlerThread("pairdesk-input").apply { start() }
    private val inputHandler = Handler(inputThread.looper)
    private val density = context.resources.displayMetrics.density

    /** Used on the input thread only. */
    private val input = HostInput(
        injector = { PairDeskAccessibilityService.instance.value?.injector(inputHandler) },
        scheduler = HandlerScheduler(inputHandler),
        clock = SystemClock::uptimeMillis,
        config = HostInputConfig(
            touchSlop = TOUCH_SLOP_DP * density,
            longPressMs = ViewConfiguration.getLongPressTimeout().toLong().coerceIn(400, 800),
            wheelScale = density * WHEEL_SCALE_PER_DP,
        ),
    )

    private val link = object : HostLink {
        override fun sendControl(message: JsonObject): Boolean = rtc?.sendControl(message) ?: false

        override fun restartIce() {
            rtc?.restartIce()
        }

        override fun applyQuality(quality: Quality) = applyEncoding(quality)

        override fun end(reason: String) {
            graph.network.endIncoming(sid, reason)
            scheduleRelease()
        }
    }

    private val initialSize = screenSize(context)

    val core = HostCore(
        link = link,
        scheduler = HandlerScheduler(handler),
        peer = peer,
        width = initialSize.x,
        height = initialSize.y,
        control = controlAvailable(),
    )

    var rtc: RtcSession? = null
        private set
    private var capturer: ScreenCapturerAndroid? = null
    private var source: VideoSource? = null
    private var helper: SurfaceTextureHelper? = null
    private var track: VideoTrack? = null
    private var captureSize: Pair<Int, Int> = CaptureSize.forScreen(initialSize.x, initialSize.y)
    private var wakeLock: PowerManager.WakeLock? = null
    private var statsJob: Job? = null
    private var releaseJob: Runnable? = null
    private var released = false
    private var started = false

    val isLive: Boolean get() = !core.isEnded

    /** The partner may control the phone right now. */
    val controlled: Boolean get() = isLive && core.state.value.control

    private val displays = context.getSystemService(DisplayManager::class.java)

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY && !released) onScreenChanged()
        }
    }

    init {
        inputHandler.post { input.setScreenSize(initialSize.x, initialSize.y) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            graph.network.sessionEnded.filter { it.sid == sid }.collect { core.onSessionClosed(it.reason, it.byPeer) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            core.state.map { it.ended }.distinctUntilChanged().collect { ended ->
                if (ended) scheduleRelease()
                onChanged()
            }
        }
        // Control follows the accessibility service and the setting, during the session too.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(PairDeskAccessibilityService.instance, graph.settings.settings) { service, s -> service != null && s.allowControl }
                .distinctUntilChanged()
                .collect { core.setControl(it) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            core.state.map { it.control }.distinctUntilChanged().collect { control ->
                inputHandler.post { input.enabled = control }
                ScreenShareService.update(context, peer.label, control)
            }
        }
        scope.launch {
            core.incomingChat.collect { message ->
                if (!appVisible()) Notifications.showChat(context, peer.label, message.text)
            }
        }
    }

    private fun controlAvailable(): Boolean = PairDeskAccessibilityService.instance.value != null && graph.settings.settings.value.allowControl

    /** Accepts the session, starts the foreground service, the capture and the connection. */
    @MainThread
    fun start() {
        if (started) return
        started = true
        scope.launch {
            if (!graph.network.acceptIncoming(sid, graph.network.hostCaps(controlAvailable()))) {
                core.onSessionClosed("network", byPeer = false)
                return@launch
            }
            // Android 10+: the capture needs the foreground service of type mediaProjection first.
            val inForeground = try {
                ScreenShareService.start(context)
                withTimeoutOrNull(FOREGROUND_WAIT_MS) { ScreenShareService.running.first { it } } == true
            } catch (e: RuntimeException) {
                log.warn("[host] foreground service not started: ${e.message}")
                false
            }
            if (!inForeground || !startCapture()) {
                core.onCaptureFailed()
                return@launch
            }
            displays?.registerDisplayListener(displayListener, handler)
            keepScreenOn()
        }
    }

    private fun startCapture(): Boolean {
        if (core.isEnded) return false
        val env = graph.rtc.environment
        val (cw, ch) = captureSize
        val videoTrack: VideoTrack
        try {
            val cap = ScreenCapturerAndroid(
                projection,
                object : MediaProjection.Callback() {
                    // The user stopped the sharing from the system (status bar chip, quick settings…).
                    override fun onStop() {
                        handler.post { if (!released) core.onCaptureStopped() }
                    }
                },
            )
            val h = SurfaceTextureHelper.create("pairdesk-screen", env.eglContext)
            val src = env.factory.createVideoSource(/* isScreencast = */ true)
            cap.initialize(h, context, src.capturerObserver)
            capturer = cap
            helper = h
            source = src
            cap.startCapture(cw, ch, CaptureSize.FPS)
            videoTrack = env.factory.createVideoTrack("pairdesk-screen", src)
            track = videoTrack
            log.info("[host] capturing the screen at ${cw}x$ch (screen ${core.state.value.width}x${core.state.value.height})")
        } catch (e: RuntimeException) {
            // SecurityException (consent no longer valid), IllegalStateException…
            log.warn("[host] capture failed: ${e.message}")
            return false
        }
        val r = graph.rtc.open(sid, RtcRole.HOST) { rtc ->
            rtc.addTrack(videoTrack)
            // Straight from WebRTC's thread to the input thread.
            rtc.inputHandler = { events -> inputHandler.post { input.onEvents(events) } }
        }
        if (r == null) {
            core.onSessionClosed("closed", byPeer = false)
            return true
        }
        rtc = r
        applyEncoding(core.state.value.quality)
        scope.launch { r.events.collect(::onRtcEvent) }
        statsJob = scope.launch {
            while (isActive && !core.isEnded) {
                delay(STATS_INTERVAL_MS)
                if (core.state.value.connected) core.onSenderStats(r.senderStats(), HostCore.encoding(core.state.value.quality).scaleDown)
            }
        }
        core.start()
        return true
    }

    private fun onRtcEvent(event: RtcEvent) {
        when (event) {
            is RtcEvent.State -> {
                core.onConnectionState(event.state)
                if (event.state == RtcState.CONNECTED) {
                    applyEncoding(core.state.value.quality)
                    rtc?.let { r -> scope.launch { core.onRelayed(r.stats().relayed) } }
                }
            }
            RtcEvent.ControlOpen -> core.onControlOpen()
            is RtcEvent.Control -> core.onControl(event.message)
            RtcEvent.ControlClose, is RtcEvent.Input, is RtcEvent.Track -> Unit
        }
    }

    private fun applyEncoding(quality: Quality) {
        val p = HostCore.encoding(quality)
        rtc?.setVideoEncoding(p.maxBitrate, p.maxFramerate, p.scaleDown)
    }

    /** Rotation (or another resolution): new `info`, capture and input at the new size. */
    private fun onScreenChanged() {
        val size = screenSize(context)
        if (!core.onScreenSize(size.x, size.y)) return
        inputHandler.post { input.setScreenSize(size.x, size.y) }
        val next = CaptureSize.forScreen(size.x, size.y)
        if (next != captureSize) {
            captureSize = next
            runCatching { capturer?.changeCaptureFormat(next.first, next.second, CaptureSize.FPS) }
                .onFailure { log.warn("[host] capture resize failed: ${it.message}") }
        }
    }

    /** The screen stays on while the computer shows it (like the desktop's prevent-display-sleep). */
    @Suppress("DEPRECATION")
    private fun keepScreenOn() {
        if (wakeLock != null) return
        val power = context.getSystemService(PowerManager::class.java) ?: return
        wakeLock = runCatching {
            power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "PairDesk:screen-share").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_MAX_MS)
            }
        }.getOrNull()
    }

    // ───────────────────────────── chat ─────────────────────────────

    fun sendChat(text: String): Boolean = core.sendChat(text)

    fun setChatOpen(open: Boolean) {
        core.setChatOpen(open)
        if (open) Notifications.cancelChat(context)
    }

    // ───────────────────────────── end ─────────────────────────────

    /** The End button (screen or notification). */
    @MainThread
    fun stop() = core.stop()

    private fun scheduleRelease() {
        if (releaseJob != null || released) return
        // A moment for `bye` to leave on the control channel.
        releaseJob = Runnable { release() }.also { handler.postDelayed(it, CLOSE_DELAY_MS) }
    }

    @MainThread
    private fun release() {
        if (released) return
        released = true
        statsJob?.cancel()
        displays?.unregisterDisplayListener(displayListener)
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        val cap = capturer
        val t = track
        val src = source
        val h = helper
        capturer = null
        track = null
        source = null
        helper = null
        rtc?.close()
        if (cap != null) {
            // After the peer connection's disposal (same single thread); stops the projection too.
            graph.rtc.environment.executor.execute {
                runCatching { cap.stopCapture() }.onFailure { log.warn("[host] stop capture failed: ${it.message}") }
                runCatching {
                    cap.dispose()
                    t?.dispose()
                    src?.dispose()
                    h?.dispose()
                }.onFailure { log.warn("[host] dispose failed: ${it.message}") }
            }
        }
        inputHandler.post { input.dispose() }
        inputThread.quitSafely()
        ScreenShareService.stop(context)
        Notifications.cancelChat(context)
        onChanged()
    }

    /** Ends (if needed) and frees everything: the screen closed. */
    internal fun dispose() {
        if (!core.isEnded) core.stop()
        releaseJob?.let(handler::removeCallbacks)
        releaseJob = null
        scope.cancel()
        core.dispose()
        handler.postDelayed({ release() }, CLOSE_DELAY_MS)
    }

    companion object {
        private const val CLOSE_DELAY_MS = 200L
        private const val STATS_INTERVAL_MS = 1_000L
        private const val FOREGROUND_WAIT_MS = 5_000L
        private const val WAKE_LOCK_MAX_MS = 8 * 60 * 60 * 1000L
        private const val TOUCH_SLOP_DP = 8f

        /** Swipe dp per wheel pixel: a notch (~120 px) scrolls about 90 dp. */
        private const val WHEEL_SCALE_PER_DP = 0.75f

        /** The size of the default display in its current orientation (physical pixels). */
        fun screenSize(context: Context): Point {
            val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // A fresh display context: its configuration is the current one.
                val wm = context.createDisplayContext(display).getSystemService(WindowManager::class.java)
                val bounds = wm?.maximumWindowMetrics?.bounds
                if (bounds != null && bounds.width() > 0 && bounds.height() > 0) return Point(bounds.width(), bounds.height())
            }
            val point = Point()
            @Suppress("DEPRECATION")
            display?.getRealSize(point)
            if (point.x <= 0 || point.y <= 0) {
                val m = context.resources.displayMetrics
                point.set(m.widthPixels, m.heightPixels)
            }
            return point
        }

        fun appVisible(): Boolean = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
}
