package io.github.azukkia.pairdesk.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import io.github.azukkia.pairdesk.AppGraph
import io.github.azukkia.pairdesk.net.OutgoingSession
import io.github.azukkia.pairdesk.rtc.RtcEvent
import io.github.azukkia.pairdesk.rtc.RtcRole
import io.github.azukkia.pairdesk.rtc.RtcSession
import io.github.azukkia.pairdesk.session.HandlerScheduler
import io.github.azukkia.pairdesk.viewer.PeerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * A running camera session (the phone streams its camera to a computer,
 * docs/PROTOCOL.md 6), app-scoped like the viewer: the camera (org.webrtc
 * Camera2 capturer, 1280×720 at 30 fps when possible, back camera first),
 * its track answered to the computer's offer, the [CameraCore] logic and the
 * previews of the screen. The camera starts with [startCamera], once the
 * CAMERA permission is granted. Created and used on the main thread.
 */
class CameraSession internal constructor(
    private val graph: AppGraph,
    val outgoing: OutgoingSession,
    private val onLiveChanged: () -> Unit,
) {
    val sid: String = outgoing.sid
    private val context: Context = graph.appContext
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val log = graph.log

    val peer = PeerInfo(
        id = outgoing.peerId,
        name = outgoing.peerName,
        platform = outgoing.session.peerPlatform,
        version = outgoing.session.peerVersion,
    )

    private var releaseJob: Runnable? = null

    private val link = object : CameraLink {
        override fun sendControl(message: JsonObject): Boolean = rtc?.sendControl(message) ?: false

        override fun switchCamera(): Boolean = switchToNext()

        override fun end(reason: String) {
            graph.network.endOutgoing(sid, reason)
            // A moment for `bye` to leave on the control channel.
            scheduleRelease()
        }
    }

    val core = CameraCore(link, HandlerScheduler(handler), peer)

    /** Null until the camera runs. */
    var rtc: RtcSession? = null
        private set

    private var enumerator: CameraEnumerator? = null
    private var devices: List<CameraDevice> = emptyList()
    private var device: CameraDevice? = null
    private var capturer: CameraVideoCapturer? = null
    private var source: VideoSource? = null
    private var helper: SurfaceTextureHelper? = null
    private var track: VideoTrack? = null
    private val previews = LinkedHashSet<VideoSink>()
    private var started = false
    private var released = false

    /** True while the camera captures (the foreground service of type camera is needed). */
    val capturing: Boolean get() = track != null && !released

    // Size of the pictures, read on the capture thread.
    @Volatile
    private var frameWidth = 0

    @Volatile
    private var frameHeight = 0

    private val frameProbe = VideoSink { frame ->
        val w = frame.rotatedWidth
        val h = frame.rotatedHeight
        if (w != frameWidth || h != frameHeight) {
            frameWidth = w
            frameHeight = h
            handler.post { core.onFrameSize(w, h) }
        }
    }

    private val events = object : CameraVideoCapturer.CameraEventsHandler {
        override fun onCameraError(error: String?) {
            log.warn("[camera] error: $error")
            handler.post { core.onCameraFailed("camera-error") }
        }

        override fun onCameraDisconnected() {
            // Another app took the camera, or the system closed it.
            log.warn("[camera] disconnected")
            handler.post { core.onCameraFailed("camera-error") }
        }

        override fun onCameraFreezed(error: String?) = log.warn("[camera] frozen: $error")

        override fun onCameraOpening(cameraName: String?) = log.info("[camera] opening $cameraName")

        override fun onFirstFrameAvailable() = log.info("[camera] first frame")

        override fun onCameraClosed() = log.info("[camera] closed")
    }

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            graph.network.sessionEnded.filter { it.sid == sid }.collect { core.onSessionClosed(it.reason, it.byPeer) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            core.state.map { it.ended }.distinctUntilChanged().collect { ended ->
                if (ended) scheduleRelease()
                onLiveChanged()
            }
        }
        if (!graph.network.isSessionActive(sid)) core.onSessionClosed("closed", byPeer = false)
    }

    val isLive: Boolean get() = !core.isEnded

    /** Starts the camera and the connection (the CAMERA permission is granted). */
    @MainThread
    fun startCamera() {
        if (started || core.isEnded) return
        started = true
        val env = graph.rtc.environment
        val videoTrack: VideoTrack
        try {
            val e: CameraEnumerator = if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator(true)
            val list = e.deviceNames.map { CameraDevice(it, e.isFrontFacing(it), e.isBackFacing(it)) }
            val first = CameraChoice.pick(list)
            if (first == null) {
                core.onCameraFailed("no-camera")
                return
            }
            val format = CameraChoice.format(formatsOf(e, first.name))
            val cap = e.createCapturer(first.name, events)
            val h = SurfaceTextureHelper.create("pairdesk-camera", env.eglContext)
            val src = env.factory.createVideoSource(/* isScreencast = */ false)
            cap.initialize(h, context, src.capturerObserver)
            cap.startCapture(format.width, format.height, format.maxFps)
            videoTrack = env.factory.createVideoTrack("pairdesk-camera", src)
            videoTrack.addSink(frameProbe)
            enumerator = e
            devices = list
            device = first
            capturer = cap
            helper = h
            source = src
            track = videoTrack
            log.info("[camera] ${first.name} (${first.facing.wire}) at ${format.width}x${format.height}@${format.maxFps}")
            core.onCameraStarted(first.facing, format.width, format.height, canSwitch = CameraChoice.next(list, first) != null)
        } catch (e: RuntimeException) {
            log.warn("[camera] cannot start: ${e.message}")
            core.onCameraFailed("camera-error")
            return
        }
        for (p in previews) videoTrack.addSink(p)
        val r = graph.rtc.open(sid, RtcRole.CONTROLLER) { it.sendVideoInAnswer(videoTrack) }
        if (r == null) {
            core.onSessionClosed("closed", byPeer = false)
            return
        }
        rtc = r
        scope.launch { r.events.collect(::onRtcEvent) }
        core.start()
        onLiveChanged()
    }

    private fun formatsOf(e: CameraEnumerator, name: String): List<CameraFormat> = runCatching {
        // org.webrtc reports frame rates multiplied by 1000.
        e.getSupportedFormats(name).orEmpty().map { f ->
            val max = f.framerate.max
            CameraFormat(f.width, f.height, if (max >= 1000) max / 1000 else max)
        }
    }.getOrDefault(emptyList())

    private fun onRtcEvent(event: RtcEvent) {
        when (event) {
            is RtcEvent.State -> core.onConnectionState(event.state)
            RtcEvent.ControlOpen -> core.onControlOpen()
            is RtcEvent.Control -> core.onControl(event.message)
            RtcEvent.ControlClose, is RtcEvent.Input, is RtcEvent.Track -> Unit
        }
    }

    private fun switchToNext(): Boolean {
        val cap = capturer ?: return false
        val e = enumerator ?: return false
        val current = device ?: return false
        val next = CameraChoice.next(devices, current) ?: return false
        val format = CameraChoice.format(formatsOf(e, next.name))
        cap.switchCamera(
            object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                    handler.post {
                        device = next
                        core.onSwitched(next.facing, format.width, format.height)
                    }
                }

                override fun onCameraSwitchError(error: String?) {
                    log.warn("[camera] switch failed: $error")
                    handler.post { core.onSwitchFailed() }
                }
            },
            next.name,
        )
        return true
    }

    // ───────────────────────────── previews ─────────────────────────────

    /** Shows the camera in [sink] (the preview of the screen). */
    @MainThread
    fun addPreview(sink: VideoSink) {
        if (released || !previews.add(sink)) return
        track?.addSink(sink)
    }

    @MainThread
    fun removePreview(sink: VideoSink) {
        if (!previews.remove(sink) || released) return
        track?.removeSink(sink)
    }

    // ───────────────────────────── end ─────────────────────────────

    /** The end button. */
    fun end() = core.end()

    private fun scheduleRelease() {
        if (releaseJob != null || released) return
        releaseJob = Runnable { release() }.also { handler.postDelayed(it, CLOSE_DELAY_MS) }
    }

    /** Stops the camera and the connection (native objects are released on the WebRTC thread). */
    @MainThread
    private fun release() {
        if (released) return
        released = true
        val t = track
        val cap = capturer
        val src = source
        val h = helper
        for (p in previews) t?.removeSink(p)
        t?.removeSink(frameProbe)
        previews.clear()
        track = null
        capturer = null
        source = null
        helper = null
        rtc?.close()
        onLiveChanged()
        if (cap == null && t == null) return
        // After the peer connection's disposal (same single thread).
        graph.rtc.environment.executor.execute {
            runCatching { cap?.stopCapture() }.onFailure { log.warn("[camera] stop failed: ${it.message}") }
            runCatching {
                cap?.dispose()
                t?.dispose()
                src?.dispose()
                h?.dispose()
            }.onFailure { log.warn("[camera] dispose failed: ${it.message}") }
        }
    }

    /** Ends (if needed) and frees everything: the screen closed. */
    internal fun dispose() {
        if (!core.isEnded) core.end()
        releaseJob?.let(handler::removeCallbacks)
        releaseJob = null
        scope.cancel()
        core.dispose()
        handler.postDelayed({ release() }, CLOSE_DELAY_MS)
    }

    companion object {
        private const val CLOSE_DELAY_MS = 200L
    }
}
