package io.github.azukkia.pairdesk.viewer

import io.github.azukkia.pairdesk.core.Protocol
import io.github.azukkia.pairdesk.core.json.jsNumberOrNull
import io.github.azukkia.pairdesk.core.json.jsStringOrNull
import io.github.azukkia.pairdesk.core.json.str
import io.github.azukkia.pairdesk.core.signaling.Caps
import io.github.azukkia.pairdesk.core.signaling.Signaling.Companion.obj
import io.github.azukkia.pairdesk.rtc.ReceiverStats
import io.github.azukkia.pairdesk.rtc.RtcState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.SecureRandom
import kotlin.math.roundToInt

/** A screen of the remote computer (`info.displays[]`). [width]/[height] in physical pixels. */
data class RemoteDisplay(val id: String, val width: Int, val height: Int, val primary: Boolean)

/** Encoder presets of the host (`hello.quality`, `{type:'quality', mode}`). */
enum class Quality(val wire: String) {
    SPEED("speed"),
    BALANCED("balanced"),
    QUALITY("quality"),
    ;

    companion object {
        fun fromWire(value: String?): Quality = entries.firstOrNull { it.wire == value } ?: BALANCED
    }
}

enum class ViewerPhase {
    /** WebRTC is connecting (first time). */
    NEGOTIATING,

    /** Connected, no picture yet. */
    WAITING_VIDEO,
    LIVE,

    /** The connection was lost; the host restarts ICE (up to 30 s). */
    RECONNECTING,
    ENDED,
}

/** Why a viewer session ended (the desktop's overlay reasons). */
object EndReason {
    /** The partner ended it (`bye`). */
    const val PEER = "peer"

    /** The connection could not be established or was lost for good. */
    const val FAILED = "failed"

    /** Ended here (disconnect button). */
    const val USER = "user"
}

/** The statistics line of the toolbar. */
data class LinkStats(
    /** True when a TURN relay carries the media, null while unknown. */
    val relayed: Boolean? = null,
    val fps: Int? = null,
    /** Estimated screen-to-screen delay (viewer.js `startStats`). */
    val delayMs: Int? = null,
    val rttMs: Int? = null,
    /** Bits per second. */
    val bitrate: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val codec: String? = null,
)

data class ChatMessage(val id: Long, val text: String, val mine: Boolean, val timeMs: Long)

/** The partner, as announced in `accepted`. */
data class PeerInfo(val id: String, val name: String, val platform: String, val version: String) {
    val label: String get() = name.ifBlank { Protocol.formatId(id) }

    /** A phone running PairDesk (text instead of key codes, Back / Home / Recents). */
    val isAndroid: Boolean get() = platform == Protocol.PLATFORM_ANDROID
}

data class ViewerState(
    val phase: ViewerPhase = ViewerPhase.NEGOTIATING,
    /** [EndReason] once [ViewerPhase.ENDED]. */
    val endReason: String? = null,
    val caps: Caps = Caps(),
    val displays: List<RemoteDisplay> = emptyList(),
    val current: String? = null,
    val quality: Quality = Quality.BALANCED,
    /** Copy what the partner copies (and allowed by the partner: [Caps.clipboard]). */
    val clipboardSync: Boolean = true,
    val stats: LinkStats = LinkStats(),
    /** `elevated` or `secure-desktop` while the host cannot inject input. */
    val inputBlocked: String? = null,
    /** The picture stopped changing for a while (sleeping or locked screen). */
    val frozen: Boolean = false,
    val chat: List<ChatMessage> = emptyList(),
    val unread: Int = 0,
    val hasAudio: Boolean = false,
    val muted: Boolean = false,
) {
    val connected: Boolean get() = phase == ViewerPhase.LIVE || phase == ViewerPhase.WAITING_VIDEO
    val ended: Boolean get() = phase == ViewerPhase.ENDED

    /** Input may be sent (viewer.js `canControl`). */
    val canControl: Boolean get() = connected && caps.control

    val currentIndex: Int get() = displays.indexOfFirst { it.id == current }

    val currentDisplay: RemoteDisplay? get() = displays.firstOrNull { it.id == current }

    /** 0-based index of the screen the "next screen" button switches to. */
    val nextIndex: Int get() = if (displays.isEmpty()) 0 else ((currentIndex.takeIf { it >= 0 } ?: 0) + 1) % displays.size
}

/** One-shot things to tell the user. */
sealed interface ViewerNotice {
    data class Chat(val from: String, val text: String) : ViewerNotice

    /** The partner's clipboard was copied to the phone. */
    data object ClipboardReceived : ViewerNotice

    /** The partner copied files: not supported by the phone. */
    data object ClipboardFilesIgnored : ViewerNotice

    data object ClipboardSent : ViewerNotice

    data object ClipboardEmpty : ViewerNotice

    data object ClipboardTooLarge : ViewerNotice

    /** The partner does not share its clipboard ([Caps.clipboard] is false). */
    data object ClipboardDisabled : ViewerNotice
}

/** What [ViewerCore] needs from the platform. */
interface ViewerLink {
    /** Sends a control message; false when the `control` channel is not open. */
    fun sendControl(message: JsonObject): Boolean

    fun sendInput(events: JsonArray)

    fun sendPointer(events: JsonArray)

    /** Writes text to the phone's clipboard. */
    fun writeClipboard(text: String)

    /** Ends the signaling session with `bye` [reason] and closes the peer connection. */
    fun end(reason: String)

    /** Remembers a viewer preference. */
    fun saveQuality(quality: Quality) = Unit

    fun saveClipboardSync(enabled: Boolean) = Unit
}

/**
 * The controller side of a control session, platform-free (the logic of the
 * desktop's src/renderer/viewer/viewer.js): connection phases and their
 * timers, control messages (`hello`, `info`, `display-changed`, `chat`,
 * `clipboard`, `host-stats`, `input-blocked`, `bye`…), the statistics line,
 * and the [input] sender. Main thread only (timers from [scheduler]).
 */
class ViewerCore(
    private val link: ViewerLink,
    private val scheduler: Scheduler,
    val peer: PeerInfo,
    caps: Caps,
    quality: Quality = Quality.BALANCED,
    clipboardSync: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newClipId: () -> String = ::randomClipId,
) {
    private val _state = MutableStateFlow(ViewerState(caps = caps, quality = quality, clipboardSync = clipboardSync))
    val state: StateFlow<ViewerState> = _state.asStateFlow()

    private val noticeChannel = Channel<ViewerNotice>(32, BufferOverflow.DROP_OLDEST)

    /** Toasts / snackbars (single collector). */
    val notices: Flow<ViewerNotice> = noticeChannel.receiveAsFlow()

    val input = InputSender(
        sink = object : InputSink {
            override fun sendReliable(events: JsonArray) = link.sendInput(events)

            override fun sendPointer(events: JsonArray) = link.sendPointer(events)
        },
        scheduler = scheduler,
    )

    private var connectTimer: Cancellable? = null
    private var lostTimer: Cancellable? = null
    private var failTimer: Cancellable? = null
    private var rtcConnected = false
    private var hadVideo = false
    private var started = false
    private var frozenFor = 0
    private var hostStats: HostStats? = null
    private var chatOpen = false
    private var nextChatId = 1L

    /** The last text we sent or wrote: not written again when the partner echoes it. */
    private var lastClipboardText: String? = null

    /** Called when input becomes impossible (connection lost, session ended): drags are forgotten. */
    var onInputLost: () -> Unit = {}

    private class HostStats(val encodeMs: Double?, val pacerMs: Double?, val at: Long)

    val canControl: Boolean get() = _state.value.canControl

    val isEnded: Boolean get() = _state.value.ended

    // ───────────────────────────── connection ─────────────────────────────

    /** The peer connection starts (viewer.js `startSession`). */
    fun start() {
        if (started || isEnded) return
        started = true
        connectTimer = scheduler.schedule(CONNECT_TIMEOUT_MS) {
            if (!rtcConnected && !isEnded) fail("ice-timeout")
        }
    }

    fun onConnectionState(state: RtcState) {
        if (isEnded) return
        lostTimer?.cancel()
        lostTimer = null
        when (state) {
            RtcState.CONNECTED -> {
                rtcConnected = true
                cancel(::connectTimer)
                cancel(::failTimer)
                _state.update { it.copy(phase = if (hadVideo) ViewerPhase.LIVE else ViewerPhase.WAITING_VIDEO) }
            }
            RtcState.DISCONNECTED, RtcState.FAILED -> {
                lostTimer = scheduler.schedule(if (state == RtcState.FAILED) 0 else LOST_DELAY_MS) {
                    lostTimer = null
                    if (isEnded) return@schedule
                    rtcConnected = false
                    releaseInput()
                    _state.update { it.copy(phase = ViewerPhase.RECONNECTING) }
                }
                cancel(::failTimer)
                failTimer = scheduler.schedule(RECONNECT_TIMEOUT_MS) {
                    failTimer = null
                    if (!rtcConnected && !isEnded) fail("connection-lost")
                }
            }
            else -> Unit
        }
    }

    fun onControlOpen() {
        if (isEnded) return
        val s = _state.value
        link.sendControl(obj("type" to "hello", "quality" to s.quality.wire, "clipboard" to s.clipboardSync))
    }

    fun onControlClose() {
        if (!isEnded) onConnectionState(RtcState.DISCONNECTED)
    }

    /** The first picture was drawn. */
    fun onFirstFrame() {
        if (hadVideo || isEnded) return
        hadVideo = true
        if (rtcConnected) _state.update { it.copy(phase = ViewerPhase.LIVE) }
    }

    fun onAudioTrack() {
        _state.update { it.copy(hasAudio = true) }
    }

    fun setMuted(muted: Boolean) {
        _state.update { it.copy(muted = muted) }
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

    /** The disconnect button. */
    fun disconnect() {
        if (isEnded) return
        releaseInput()
        link.sendControl(obj("type" to "bye"))
        showEnded(EndReason.USER)
        link.end("closed")
    }

    /** Why the connection failed (`ice-timeout`, `connection-lost`), for the logs. */
    var failure: String? = null
        private set

    private fun fail(reason: String) {
        failure = reason
        link.end(EndReason.FAILED)
        showEnded(EndReason.FAILED)
    }

    private fun showEnded(reason: String) {
        if (isEnded) return
        releaseInput()
        input.dispose()
        rtcConnected = false
        cancel(::connectTimer)
        cancel(::failTimer)
        cancel(::lostTimer)
        _state.update { it.copy(phase = ViewerPhase.ENDED, endReason = reason, inputBlocked = null, frozen = false) }
    }

    private fun releaseInput() {
        input.releaseAll()
        onInputLost()
    }

    private fun cancel(timer: kotlin.reflect.KMutableProperty0<Cancellable?>) {
        timer.get()?.cancel()
        timer.set(null)
    }

    // ───────────────────────────── control messages ─────────────────────────────

    fun onControl(msg: JsonObject) {
        if (isEnded) return
        when (msg.str("type")) {
            "info" -> {
                val displays = (msg["displays"] as? JsonArray)?.mapNotNull(::parseDisplay) ?: emptyList()
                val current = idOf(msg["current"])
                _state.update { s ->
                    s.copy(displays = displays, current = current, caps = mergePerms(s.caps, msg["perms"] as? JsonObject))
                }
            }
            "display-changed" -> _state.update { it.copy(current = idOf(msg["current"])) }
            "chat" -> {
                val text = (msg["text"].jsStringOrNull() ?: msg["text"]?.let(::jsString) ?: "").take(MAX_CHAT)
                if (text.isEmpty()) return
                val message = ChatMessage(nextChatId++, text, mine = false, timeMs = clock())
                _state.update { it.copy(chat = (it.chat + message).takeLast(MAX_CHAT_HISTORY), unread = if (chatOpen) 0 else it.unread + 1) }
                if (!chatOpen) noticeChannel.trySend(ViewerNotice.Chat(peer.label, text))
            }
            "clipboard" -> onRemoteClipboard(msg)
            "host-stats" -> hostStats = HostStats(
                encodeMs = msg["encodeMs"].jsNumberOrNull()?.takeIf { it.isFinite() },
                pacerMs = msg["pacerMs"].jsNumberOrNull()?.takeIf { it.isFinite() },
                at = clock(),
            )
            "input-blocked" -> _state.update { it.copy(inputBlocked = msg["reason"].jsStringOrNull()?.take(32)) }
            "bye" -> {
                showEnded(EndReason.PEER)
                link.end("closed")
            }
            // cursor shapes, clipboard files…: not used by the phone.
            else -> Unit
        }
    }

    private fun parseDisplay(e: JsonElement): RemoteDisplay? {
        val o = e as? JsonObject ?: return null
        val id = idOf(o["id"]) ?: return null
        val w = o["width"].jsNumberOrNull()?.takeIf { it.isFinite() && it > 0 }?.toInt() ?: 0
        val h = o["height"].jsNumberOrNull()?.takeIf { it.isFinite() && it > 0 }?.toInt() ?: 0
        val primary = (o["primary"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } ?: false
        return RemoteDisplay(id, w, h, primary)
    }

    /** Display ids are strings on the desktop; numbers are accepted too. */
    private fun idOf(e: JsonElement?): String? = when {
        e == null || e is JsonNull -> null
        e is JsonPrimitive && e.isString -> e.content
        e is JsonPrimitive -> e.content
        else -> null
    }?.take(64)

    private fun jsString(e: JsonElement): String = (e as? JsonPrimitive)?.content ?: ""

    /** `init.caps = {...init.caps, ...msg.perms}`: only the flags present change. */
    private fun mergePerms(caps: Caps, perms: JsonObject?): Caps {
        if (perms == null) return caps
        fun flag(key: String, current: Boolean): Boolean {
            val v = perms[key] ?: return current
            return v is JsonPrimitive && !v.isString && v.content == "true"
        }
        return Caps(
            control = flag("control", caps.control),
            files = flag("files", caps.files),
            clipboard = flag("clipboard", caps.clipboard),
            audio = flag("audio", caps.audio),
        )
    }

    // ───────────────────────────── toolbar actions ─────────────────────────────

    fun selectDisplay(id: String) {
        if (isEnded) return
        link.sendControl(obj("type" to "select-display", "displayId" to id))
    }

    /** One tap to the next screen (viewer.js `nextScreen`). */
    fun nextDisplay() {
        val s = _state.value
        if (s.displays.size < 2) return
        selectDisplay(s.displays[s.nextIndex].id)
    }

    fun setQuality(quality: Quality) {
        _state.update { it.copy(quality = quality) }
        link.saveQuality(quality)
        if (!isEnded) link.sendControl(obj("type" to "quality", "mode" to quality.wire))
    }

    fun sendChat(text: String): Boolean {
        val t = text.trim().take(MAX_CHAT)
        if (t.isEmpty() || isEnded) return false
        if (!link.sendControl(obj("type" to "chat", "text" to t))) return false
        val message = ChatMessage(nextChatId++, t, mine = true, timeMs = clock())
        _state.update { it.copy(chat = (it.chat + message).takeLast(MAX_CHAT_HISTORY)) }
        return true
    }

    /** The chat panel is shown (incoming messages are read) or hidden. */
    fun setChatOpen(open: Boolean) {
        chatOpen = open
        if (open) _state.update { it.copy(unread = 0) }
    }

    fun setClipboardSync(enabled: Boolean) {
        _state.update { it.copy(clipboardSync = enabled) }
        link.saveClipboardSync(enabled)
    }

    /**
     * Sends the phone's clipboard [text] to the partner: `{type:'clipboard', cid, text}`
     * for 1.2+ partners, `{type:'clipboard', text}` for older ones.
     */
    fun sendClipboard(text: String?) {
        val s = _state.value
        if (s.ended) return
        if (!s.caps.clipboard) {
            noticeChannel.trySend(ViewerNotice.ClipboardDisabled)
            return
        }
        if (text.isNullOrEmpty()) {
            noticeChannel.trySend(ViewerNotice.ClipboardEmpty)
            return
        }
        if (text.toByteArray(Charsets.UTF_8).size > MAX_CLIPBOARD_BYTES) {
            noticeChannel.trySend(ViewerNotice.ClipboardTooLarge)
            return
        }
        val message = if (Protocol.versionAtLeast(peer.version, "1.2.0")) {
            obj("type" to "clipboard", "cid" to newClipId(), "text" to text)
        } else {
            obj("type" to "clipboard", "text" to text)
        }
        if (link.sendControl(message)) {
            lastClipboardText = text
            noticeChannel.trySend(ViewerNotice.ClipboardSent)
        }
    }

    private fun onRemoteClipboard(msg: JsonObject) {
        val s = _state.value
        if (!s.clipboardSync || !s.caps.clipboard) return
        val rich = msg["cid"].jsStringOrNull() != null
        if (rich && (msg["files"] as? JsonArray)?.isNotEmpty() == true) {
            noticeChannel.trySend(ViewerNotice.ClipboardFilesIgnored)
            return
        }
        val text = msg["text"].jsStringOrNull() ?: if (rich) return else (msg["text"]?.let(::jsString) ?: "")
        if (text.isEmpty() || text == lastClipboardText) return
        lastClipboardText = text
        link.writeClipboard(text)
        noticeChannel.trySend(ViewerNotice.ClipboardReceived)
    }

    // ───────────────────────────── statistics ─────────────────────────────

    /** Once a second (viewer.js `startStats` and `watchFrozen`). */
    fun onStats(s: ReceiverStats) {
        if (isEnded) return
        watchFrozen(s)
        val host = hostStats?.takeIf { clock() - it.at < 3_000 }
        val delay = s.rttMs?.let { rtt ->
            (rtt / 2.0 + (host?.encodeMs ?: 0.0) + (host?.pacerMs ?: 0.0) + (s.jitterBufferMs ?: 0.0) + (s.decodeMs ?: 0.0) + 16).roundToInt()
        }
        _state.update {
            it.copy(
                stats = LinkStats(
                    relayed = s.relayed ?: it.stats.relayed,
                    fps = s.fps?.roundToInt(),
                    delayMs = delay,
                    rttMs = s.rttMs,
                    bitrate = s.bitrate,
                    width = s.width,
                    height = s.height,
                    codec = s.codec,
                ),
            )
        }
    }

    private fun watchFrozen(s: ReceiverStats) {
        if (!hadVideo || !rtcConnected || s.fps == null) return
        frozenFor = if (s.fps > 0) 0 else frozenFor + 1
        if (frozenFor == WAKE_AFTER_S) link.sendControl(obj("type" to "wake"))
        val frozen = frozenFor >= FROZEN_AFTER_S
        if (frozen != _state.value.frozen) _state.update { it.copy(frozen = frozen) }
    }

    fun dispose() {
        cancel(::connectTimer)
        cancel(::failTimer)
        cancel(::lostTimer)
        input.dispose()
        noticeChannel.close()
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 45_000L
        const val LOST_DELAY_MS = 1_500L
        const val RECONNECT_TIMEOUT_MS = 30_000L
        const val MAX_CHAT = 2_000
        const val MAX_CHAT_HISTORY = 200
        const val WAKE_AFTER_S = 3
        const val FROZEN_AFTER_S = 6

        /** `text` ≤ 200 KiB (docs/PROTOCOL.md 4.2). */
        const val MAX_CLIPBOARD_BYTES = 200 * 1024

        private val random = SecureRandom()

        /** A random clipboard content id (≤ 32 chars). */
        fun randomClipId(): String {
            val bytes = ByteArray(12)
            random.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
