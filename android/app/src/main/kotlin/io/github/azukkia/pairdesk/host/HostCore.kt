package io.github.azukkia.pairdesk.host

import io.github.azukkia.pairdesk.core.json.jsStringOrNull
import io.github.azukkia.pairdesk.core.json.str
import io.github.azukkia.pairdesk.core.signaling.Signaling.Companion.obj
import io.github.azukkia.pairdesk.rtc.RtcState
import io.github.azukkia.pairdesk.rtc.SenderStats
import io.github.azukkia.pairdesk.viewer.Cancellable
import io.github.azukkia.pairdesk.viewer.ChatMessage
import io.github.azukkia.pairdesk.viewer.EndReason
import io.github.azukkia.pairdesk.viewer.PeerInfo
import io.github.azukkia.pairdesk.viewer.Quality
import io.github.azukkia.pairdesk.viewer.Scheduler
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class HostPhase {
    /** WebRTC is connecting (first time). */
    CONNECTING,
    LIVE,

    /** The connection was lost; ICE restarts (up to 30 s). */
    RECONNECTING,
    ENDED,
}

/** The video encoding for a quality preset of the viewer (QUALITY_PRESETS of src/shared/protocol.js, phone-sized). */
data class EncodingPreset(val maxBitrate: Int, val maxFramerate: Int, val scaleDown: Double)

data class HostState(
    val phase: HostPhase = HostPhase.CONNECTING,
    /** [EndReason] once [HostPhase.ENDED]. */
    val endReason: String? = null,
    /** Screen size announced in `info` (physical pixels, current orientation). */
    val width: Int = 0,
    val height: Int = 0,
    /** The partner may control the phone (`perms.control`). */
    val control: Boolean = false,
    val quality: Quality = Quality.BALANCED,
    val chat: List<ChatMessage> = emptyList(),
    val unread: Int = 0,
    /** True when a TURN relay carries the media, null while unknown. */
    val relayed: Boolean? = null,
) {
    val ended: Boolean get() = phase == HostPhase.ENDED
    val connected: Boolean get() = phase == HostPhase.LIVE
}

/** What [HostCore] needs from the platform. */
interface HostLink {
    /** Sends a control message; false when the `control` channel is not open. */
    fun sendControl(message: JsonObject): Boolean

    /** New offer with an ICE restart. */
    fun restartIce()

    /** Applies the encoding of [quality] to the screen track. */
    fun applyQuality(quality: Quality)

    /** Ends the signaling session with `bye` [reason], stops the capture and the peer connection. */
    fun end(reason: String)
}

/**
 * The host side of a control session when a computer views and controls
 * this phone (docs/PROTOCOL.md 4.2 and 5.2; the logic of the desktop's
 * src/renderer/host/host.js), platform-free: `info` with the phone's screen
 * when the control channel opens and whenever its size or the control
 * permission changes, the viewer's quality, chat, `bye`, and the connection
 * timers (ICE restart after 4 s disconnected or at once when failed, end
 * after 30 s without connection or 60 s for the first one). Main thread only.
 */
class HostCore(
    private val link: HostLink,
    private val scheduler: Scheduler,
    val peer: PeerInfo,
    width: Int,
    height: Int,
    control: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(HostState(width = width, height = height, control = control))
    val state: StateFlow<HostState> = _state.asStateFlow()

    private val chatChannel = Channel<ChatMessage>(32, BufferOverflow.DROP_OLDEST)

    /** Chat messages of the partner, for notifications (single collector). */
    val incomingChat: Flow<ChatMessage> = chatChannel.receiveAsFlow()

    private var connectTimer: Cancellable? = null
    private var restartTimer: Cancellable? = null
    private var failTimer: Cancellable? = null
    private var everConnected = false
    private var started = false
    private var chatOpen = false
    private var nextChatId = 1L

    val isEnded: Boolean get() = _state.value.ended

    /** Why the connection failed (`ice-timeout`, `connection-lost`), for the logs. */
    var failure: String? = null
        private set

    // ───────────────────────────── connection ─────────────────────────────

    /** The peer connection starts (the offer goes out). */
    fun start() {
        if (started || isEnded) return
        started = true
        connectTimer = scheduler.schedule(CONNECT_TIMEOUT_MS) {
            connectTimer = null
            if (!everConnected && !isEnded) fail("ice-timeout")
        }
    }

    fun onConnectionState(state: RtcState) {
        if (isEnded) return
        cancel(::restartTimer)
        when (state) {
            RtcState.CONNECTED -> {
                everConnected = true
                cancel(::connectTimer)
                cancel(::failTimer)
                _state.update { it.copy(phase = HostPhase.LIVE) }
            }
            RtcState.DISCONNECTED -> {
                if (everConnected) _state.update { it.copy(phase = HostPhase.RECONNECTING) }
                restartTimer = scheduler.schedule(RESTART_DELAY_MS) {
                    restartTimer = null
                    if (!isEnded) link.restartIce()
                }
            }
            RtcState.FAILED -> {
                if (everConnected) _state.update { it.copy(phase = HostPhase.RECONNECTING) }
                link.restartIce()
                cancel(::failTimer)
                failTimer = scheduler.schedule(RECONNECT_TIMEOUT_MS) {
                    failTimer = null
                    if (_state.value.phase != HostPhase.LIVE && !isEnded) fail("connection-lost")
                }
            }
            else -> Unit
        }
    }

    fun onRelayed(relayed: Boolean?) {
        if (relayed != null && relayed != _state.value.relayed) _state.update { it.copy(relayed = relayed) }
    }

    // ───────────────────────────── control channel ─────────────────────────────

    fun onControlOpen() {
        if (!isEnded) sendInfo()
    }

    fun onControl(msg: JsonObject) {
        if (isEnded) return
        when (msg.str("type")) {
            "hello" -> msg.str("quality")?.let { setQuality(Quality.fromWire(it)) }
            "quality" -> setQuality(Quality.fromWire(msg.str("mode")))
            "chat" -> {
                val text = (msg["text"].jsStringOrNull() ?: (msg["text"] as? JsonPrimitive)?.content ?: "").take(MAX_CHAT)
                if (text.isEmpty()) return
                val message = ChatMessage(nextChatId++, text, mine = false, timeMs = clock())
                _state.update { it.copy(chat = (it.chat + message).takeLast(MAX_CHAT_HISTORY), unread = if (chatOpen) 0 else it.unread + 1) }
                chatChannel.trySend(message)
            }
            "bye" -> {
                showEnded(EndReason.PEER)
                link.end("closed")
            }
            // wake (the screen is kept on during the session), select-display
            // (one screen), clipboard and files (not shared by the phone).
            else -> Unit
        }
    }

    private fun setQuality(quality: Quality) {
        _state.update { it.copy(quality = quality) }
        link.applyQuality(quality)
    }

    /** `{type:'info', displays:[{id:'android', width, height, primary:true}], current:'android', perms}`. */
    fun info(): JsonObject {
        val s = _state.value
        return obj(
            "type" to "info",
            "displays" to JsonArray(listOf(obj("id" to DISPLAY_ID, "width" to s.width, "height" to s.height, "primary" to true))),
            "current" to DISPLAY_ID,
            "perms" to obj("control" to s.control, "files" to false, "clipboard" to false, "audio" to false),
        )
    }

    private fun sendInfo() {
        link.sendControl(info())
    }

    /** The phone's screen size changed (rotation): true when it did (the capture must follow). */
    fun onScreenSize(width: Int, height: Int): Boolean {
        val s = _state.value
        if (width <= 0 || height <= 0 || (width == s.width && height == s.height)) return false
        _state.update { it.copy(width = width, height = height) }
        if (!isEnded) sendInfo()
        return true
    }

    /** Control became possible or impossible (accessibility service, setting). */
    fun setControl(control: Boolean) {
        if (control == _state.value.control) return
        _state.update { it.copy(control = control) }
        if (!isEnded) sendInfo()
    }

    /** Once a second while connected (host.js `monitor`). */
    fun onSenderStats(s: SenderStats, scale: Double) {
        if (isEnded || _state.value.phase != HostPhase.LIVE) return
        val fields = linkedMapOf<String, Any?>("type" to "host-stats")
        fields["encodeMs"] = s.encodeMs
        fields["pacerMs"] = s.pacerMs
        fields["fps"] = s.fps
        fields["codec"] = s.codec
        fields["encoder"] = s.encoder
        fields["limitation"] = s.limitation
        fields["bandwidth"] = s.bandwidth
        fields["scale"] = scale
        // Unknown values are left out, like `undefined` in JSON.stringify.
        link.sendControl(obj(*fields.filterValues { it != null }.toList().toTypedArray()))
    }

    // ───────────────────────────── chat ─────────────────────────────

    fun sendChat(text: String): Boolean {
        val t = text.trim().take(MAX_CHAT)
        if (t.isEmpty() || isEnded) return false
        if (!link.sendControl(obj("type" to "chat", "text" to t))) return false
        val message = ChatMessage(nextChatId++, t, mine = true, timeMs = clock())
        _state.update { it.copy(chat = (it.chat + message).takeLast(MAX_CHAT_HISTORY)) }
        return true
    }

    /** The chat is shown on the phone (messages are read) or hidden. */
    fun setChatOpen(open: Boolean) {
        chatOpen = open
        if (open) _state.update { it.copy(unread = 0) }
    }

    // ───────────────────────────── end ─────────────────────────────

    /** The phone's user ends the session (host.js `end`). */
    fun stop() {
        if (isEnded) return
        link.sendControl(obj("type" to "bye"))
        showEnded(EndReason.USER)
        link.end("host-closed")
    }

    /** The signaling session ended ([byPeer]: `bye` from the partner). */
    fun onSessionClosed(reason: String, byPeer: Boolean) {
        showEnded(
            when {
                byPeer -> EndReason.PEER
                reason == EndReason.FAILED -> EndReason.FAILED
                else -> EndReason.USER
            },
        )
    }

    /** The capture stopped by itself (the user revoked the screen sharing from the system). */
    fun onCaptureStopped() {
        if (isEnded) return
        link.sendControl(obj("type" to "bye"))
        showEnded(EndReason.USER)
        link.end("host-closed")
    }

    /** The screen capture could not start: the session ends as failed. */
    fun onCaptureFailed() {
        if (isEnded) return
        link.sendControl(obj("type" to "bye"))
        fail("capture")
    }

    private fun fail(reason: String) {
        failure = reason
        showEnded(EndReason.FAILED)
        link.end(EndReason.FAILED)
    }

    private fun showEnded(reason: String) {
        if (isEnded) return
        cancel(::connectTimer)
        cancel(::restartTimer)
        cancel(::failTimer)
        _state.update { it.copy(phase = HostPhase.ENDED, endReason = reason) }
    }

    private fun cancel(timer: kotlin.reflect.KMutableProperty0<Cancellable?>) {
        timer.get()?.cancel()
        timer.set(null)
    }

    fun dispose() {
        cancel(::connectTimer)
        cancel(::restartTimer)
        cancel(::failTimer)
        chatChannel.close()
    }

    companion object {
        /** The phone's only display in `info`. */
        const val DISPLAY_ID = "android"

        const val CONNECT_TIMEOUT_MS = 60_000L
        const val RESTART_DELAY_MS = 4_000L
        const val RECONNECT_TIMEOUT_MS = 30_000L
        const val MAX_CHAT = 2_000
        const val MAX_CHAT_HISTORY = 200

        /** Phones encode at 30 fps at most; the capture size is already limited ([CaptureSize]). */
        fun encoding(quality: Quality): EncodingPreset = when (quality) {
            Quality.SPEED -> EncodingPreset(maxBitrate = 2_500_000, maxFramerate = 30, scaleDown = 1.5)
            Quality.BALANCED -> EncodingPreset(maxBitrate = 5_000_000, maxFramerate = 30, scaleDown = 1.0)
            Quality.QUALITY -> EncodingPreset(maxBitrate = 10_000_000, maxFramerate = 30, scaleDown = 1.0)
        }
    }
}

/** The size the screen is captured at: the screen's aspect, at most [maxLong] pixels long, sides multiple of [align] (encoders). */
object CaptureSize {
    const val MAX_LONG_SIDE = 1920
    const val ALIGN = 16
    const val FPS = 30

    fun forScreen(width: Int, height: Int, maxLong: Int = MAX_LONG_SIDE, align: Int = ALIGN): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return align to align
        val long = maxOf(width, height)
        val scale = if (long > maxLong) maxLong.toDouble() / long else 1.0
        fun side(v: Int) = ((v * scale / align).toInt() * align).coerceAtLeast(align)
        return side(width) to side(height)
    }
}
