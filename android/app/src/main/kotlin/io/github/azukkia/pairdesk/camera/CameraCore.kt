package io.github.azukkia.pairdesk.camera

import io.github.azukkia.pairdesk.core.json.str
import io.github.azukkia.pairdesk.core.signaling.Signaling.Companion.obj
import io.github.azukkia.pairdesk.rtc.RtcState
import io.github.azukkia.pairdesk.viewer.Cancellable
import io.github.azukkia.pairdesk.viewer.EndReason
import io.github.azukkia.pairdesk.viewer.PeerInfo
import io.github.azukkia.pairdesk.viewer.Scheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject

/** Which way a camera looks (`camera-info.facing`). */
enum class CameraFacing(val wire: String) {
    FRONT("front"),
    BACK("back"),
}

enum class CameraPhase {
    /** Waiting for the camera (permission, opening). */
    STARTING,

    /** The camera runs, WebRTC is connecting. */
    CONNECTING,
    LIVE,

    /** The connection was lost; the computer restarts ICE (up to 30 s). */
    RECONNECTING,
    ENDED,
}

data class CameraState(
    val phase: CameraPhase = CameraPhase.STARTING,
    /** [EndReason] once [CameraPhase.ENDED]. */
    val endReason: String? = null,
    /** Why the camera stopped, when it did (`no-camera`, `camera-error`, `permission`). */
    val error: String? = null,
    val facing: CameraFacing? = null,
    /** Size of the pictures sent (as the computer shows them). */
    val width: Int = 0,
    val height: Int = 0,
    /** Another camera is available. */
    val canSwitch: Boolean = false,
    /** A switch is in progress. */
    val switching: Boolean = false,
) {
    val ended: Boolean get() = phase == CameraPhase.ENDED
}

/** What [CameraCore] needs from the platform. */
interface CameraLink {
    /** Sends a control message; false when the `control` channel is not open. */
    fun sendControl(message: JsonObject): Boolean

    /** Starts switching to the other camera; the result comes through [CameraCore.onSwitched] / [CameraCore.onSwitchFailed]. */
    fun switchCamera(): Boolean

    /** Ends the signaling session with `bye` [reason], stops the camera and the peer connection. */
    fun end(reason: String)
}

/**
 * The phone side of a camera session (docs/PROTOCOL.md 6; what
 * test/e2e/camera.test.js expects from a phone), platform-free:
 * `camera-info` when the control channel opens, after a switch, when the size
 * of the pictures changes and in answer to every `camera-hello`;
 * `camera-switch` from the computer switches between the front and back
 * cameras; `bye` ends. Connection: the computer (offerer) restarts ICE, the
 * phone gives up after 60 s without a first connection or 30 s without
 * getting it back. Main thread only.
 */
class CameraCore(
    private val link: CameraLink,
    private val scheduler: Scheduler,
    val peer: PeerInfo,
) {
    private val _state = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = _state.asStateFlow()

    private var connectTimer: Cancellable? = null
    private var lostTimer: Cancellable? = null
    private var failTimer: Cancellable? = null
    private var everConnected = false
    private var started = false

    val isEnded: Boolean get() = _state.value.ended

    /** Why the connection failed (`ice-timeout`, `connection-lost`), for the logs. */
    var failure: String? = null
        private set

    // ───────────────────────────── camera ─────────────────────────────

    /** The camera runs: [facing], pictures of [width]×[height]; [canSwitch] when another camera exists. */
    fun onCameraStarted(facing: CameraFacing, width: Int, height: Int, canSwitch: Boolean) {
        if (isEnded) return
        _state.update { it.copy(facing = facing, width = width, height = height, canSwitch = canSwitch) }
    }

    /** The size of the pictures changed (first picture, rotation of the phone…). */
    fun onFrameSize(width: Int, height: Int) {
        val s = _state.value
        if (isEnded || width <= 0 || height <= 0 || (width == s.width && height == s.height)) return
        _state.update { it.copy(width = width, height = height) }
        sendInfo()
    }

    /** The switch button of the phone, or `camera-switch` from the computer. */
    fun switchCamera(): Boolean {
        val s = _state.value
        if (isEnded || s.switching || !s.canSwitch) return false
        _state.update { it.copy(switching = true) }
        if (!link.switchCamera()) {
            _state.update { it.copy(switching = false) }
            return false
        }
        return true
    }

    fun onSwitched(facing: CameraFacing, width: Int, height: Int) {
        if (isEnded) return
        _state.update { it.copy(facing = facing, width = width, height = height, switching = false) }
        sendInfo()
    }

    fun onSwitchFailed() {
        _state.update { it.copy(switching = false) }
    }

    /** The camera stopped working ([error]: `no-camera`, `camera-error`, `permission`). */
    fun onCameraFailed(error: String) {
        if (isEnded) return
        _state.update { it.copy(error = error) }
        link.sendControl(obj("type" to "bye"))
        showEnded(EndReason.FAILED)
        link.end(EndReason.FAILED)
    }

    // ───────────────────────────── connection ─────────────────────────────

    /** The peer connection starts (the camera track is ready). */
    fun start() {
        if (started || isEnded) return
        started = true
        _state.update { it.copy(phase = CameraPhase.CONNECTING) }
        connectTimer = scheduler.schedule(CONNECT_TIMEOUT_MS) {
            connectTimer = null
            if (!everConnected && !isEnded) fail("ice-timeout")
        }
    }

    fun onConnectionState(state: RtcState) {
        if (isEnded) return
        cancel(::lostTimer)
        when (state) {
            RtcState.CONNECTED -> {
                everConnected = true
                cancel(::connectTimer)
                cancel(::failTimer)
                _state.update { it.copy(phase = CameraPhase.LIVE) }
            }
            RtcState.DISCONNECTED, RtcState.FAILED -> {
                if (!everConnected) return
                lostTimer = scheduler.schedule(if (state == RtcState.FAILED) 0 else LOST_DELAY_MS) {
                    lostTimer = null
                    if (!isEnded) _state.update { it.copy(phase = CameraPhase.RECONNECTING) }
                }
                if (failTimer == null) {
                    failTimer = scheduler.schedule(RECONNECT_TIMEOUT_MS) {
                        failTimer = null
                        if (_state.value.phase != CameraPhase.LIVE && !isEnded) fail("connection-lost")
                    }
                }
            }
            else -> Unit
        }
    }

    fun onControlOpen() {
        if (!isEnded) sendInfo()
    }

    fun onControl(msg: JsonObject) {
        if (isEnded) return
        when (msg.str("type")) {
            // The computer's side of the channel opened: what we sent before may be lost.
            "camera-hello" -> sendInfo()
            "camera-switch" -> switchCamera()
            "bye" -> {
                showEnded(EndReason.PEER)
                link.end("closed")
            }
            else -> Unit
        }
    }

    /** `{type:'camera-info', width, height, facing}`; null while the camera is not running. */
    fun info(): JsonObject? {
        val s = _state.value
        val facing = s.facing ?: return null
        return obj("type" to "camera-info", "width" to s.width, "height" to s.height, "facing" to facing.wire)
    }

    private fun sendInfo() {
        info()?.let(link::sendControl)
    }

    // ───────────────────────────── end ─────────────────────────────

    /** The end button of the phone. */
    fun end() {
        if (isEnded) return
        link.sendControl(obj("type" to "bye"))
        showEnded(EndReason.USER)
        link.end("closed")
    }

    /** The signaling session ended ([byPeer]: `bye` from the computer). */
    fun onSessionClosed(reason: String, byPeer: Boolean) {
        showEnded(
            when {
                byPeer -> EndReason.PEER
                reason == EndReason.FAILED -> EndReason.FAILED
                else -> EndReason.USER
            },
        )
    }

    private fun fail(reason: String) {
        failure = reason
        showEnded(EndReason.FAILED)
        link.end(EndReason.FAILED)
    }

    private fun showEnded(reason: String) {
        if (isEnded) return
        cancel(::connectTimer)
        cancel(::lostTimer)
        cancel(::failTimer)
        _state.update { it.copy(phase = CameraPhase.ENDED, endReason = reason, switching = false) }
    }

    private fun cancel(timer: kotlin.reflect.KMutableProperty0<Cancellable?>) {
        timer.get()?.cancel()
        timer.set(null)
    }

    fun dispose() {
        cancel(::connectTimer)
        cancel(::lostTimer)
        cancel(::failTimer)
    }

    companion object {
        /** The computer's camera window gives up after 60 s too. */
        const val CONNECT_TIMEOUT_MS = 60_000L
        const val LOST_DELAY_MS = 1_500L
        const val RECONNECT_TIMEOUT_MS = 30_000L
    }
}

/** A camera of the phone, as org.webrtc's CameraEnumerator lists it. */
data class CameraDevice(val name: String, val front: Boolean, val back: Boolean) {
    val facing: CameraFacing get() = if (front) CameraFacing.FRONT else CameraFacing.BACK
}

/** A capture format of a camera; [maxFps] in frames per second. */
data class CameraFormat(val width: Int, val height: Int, val maxFps: Int)

/** The camera and capture format of a camera session: 1280×720 at 30 fps when possible, back camera first. */
object CameraChoice {
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val FPS = 30

    /** The first camera facing [facing], else the first camera. */
    fun pick(devices: List<CameraDevice>, facing: CameraFacing = CameraFacing.BACK): CameraDevice? =
        devices.firstOrNull { it.facing == facing } ?: devices.firstOrNull()

    /** The camera to switch to from [current]: one facing the other way, else the next one; null when there is none. */
    fun next(devices: List<CameraDevice>, current: CameraDevice): CameraDevice? {
        if (devices.size < 2) return null
        devices.firstOrNull { it.name != current.name && it.facing != current.facing }?.let { return it }
        val i = devices.indexOfFirst { it.name == current.name }
        return devices[(i + 1).mod(devices.size)].takeIf { it.name != current.name }
    }

    /**
     * The format closest to [width]×[height] (like org.webrtc's
     * `getClosestSupportedSize`), among those reaching [fps] when there are
     * some; the frame rate is [fps] or what the format can do.
     */
    fun format(formats: List<CameraFormat>, width: Int = WIDTH, height: Int = HEIGHT, fps: Int = FPS): CameraFormat {
        val usable = formats.filter { it.width > 0 && it.height > 0 }
        if (usable.isEmpty()) return CameraFormat(width, height, fps)
        val fast = usable.filter { it.maxFps >= fps }.ifEmpty { usable }
        val best = fast.minWith(compareBy<CameraFormat>({ kotlin.math.abs(it.width - width) + kotlin.math.abs(it.height - height) }, { -it.maxFps }))
        return CameraFormat(best.width, best.height, minOf(fps, best.maxFps.coerceAtLeast(1)))
    }
}
