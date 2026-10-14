package io.github.azukkia.pairdesk.rtc

import android.content.Context
import io.github.azukkia.pairdesk.net.IceServerSpec
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Process-wide WebRTC state: one [PeerConnectionFactory] and one shared
 * [EglBase] (renderers, hardware codecs and capturers must share its context).
 * Created on first use, kept for the life of the process.
 */
class RtcEnvironment private constructor(context: Context) {
    /**
     * The thread on which [RtcSession]s run their negotiation and dispose their
     * peer connections. Blocking org.webrtc calls (close, dispose) must never
     * run on WebRTC's own signaling thread, where observers are called.
     */
    val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "pairdesk-rtc").apply { isDaemon = true } }

    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    val eglBase: EglBase = EglBase.create()

    val eglContext: EglBase.Context get() = eglBase.eglBaseContext

    val factory: PeerConnectionFactory

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        // Hardware VP8 / H.264 (MediaCodec) with software fallback (libvpx VP8/VP9, AV1),
        // sharing the EGL context so that textures go straight to the encoder.
        val encoders = DefaultVideoEncoderFactory(eglContext, /* enableIntelVp8Encoder = */ true, /* enableH264HighProfile = */ true)
        val decoders = DefaultVideoDecoderFactory(eglContext)
        // Playback only (audio shared by a Windows host); recording starts only if
        // a local audio track is ever sent.
        val audio = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoders)
            .setVideoDecoderFactory(decoders)
            .setAudioDeviceModule(audio)
            .createPeerConnectionFactory()
        audio.release() // the factory keeps its own reference
    }

    /**
     * The configuration of src/renderer/common/rtc.js: the ICE servers,
     * `bundlePolicy: 'max-bundle'`, `iceCandidatePoolSize: 2`, unified plan.
     */
    fun configuration(iceServers: List<IceServerSpec>): PeerConnection.RTCConfiguration =
        PeerConnection.RTCConfiguration(iceServers.map(::toIceServer)).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceCandidatePoolSize = 2
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
        }

    companion object {
        @Volatile
        private var instance: RtcEnvironment? = null

        fun get(context: Context): RtcEnvironment =
            instance ?: synchronized(this) {
                instance ?: RtcEnvironment(context.applicationContext).also { instance = it }
            }

        fun toIceServer(spec: IceServerSpec): PeerConnection.IceServer {
            val builder = PeerConnection.IceServer.builder(spec.urls)
            spec.username?.let(builder::setUsername)
            spec.credential?.let(builder::setPassword)
            return builder.createIceServer()
        }
    }
}
