package io.github.azukkia.pairdesk.rtc

import io.github.azukkia.pairdesk.core.json.JsonJs
import io.github.azukkia.pairdesk.core.json.str
import io.github.azukkia.pairdesk.core.signaling.SessionMessages
import io.github.azukkia.pairdesk.core.transport.Logger
import io.github.azukkia.pairdesk.net.IceServerSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.webrtc.AddIceObserver
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import io.github.azukkia.pairdesk.core.signaling.IceCandidate as SignalCandidate
import io.github.azukkia.pairdesk.core.signaling.SessionDescription as SignalDescription

/** Which side of a session: the host offers, the controller answers (docs/PROTOCOL.md 4). */
enum class RtcRole { HOST, CONTROLLER }

/** `RTCPeerConnection.connectionState`. */
enum class RtcState(val wire: String) {
    NEW("new"),
    CONNECTING("connecting"),
    CONNECTED("connected"),
    DISCONNECTED("disconnected"),
    FAILED("failed"),

    /** Closed locally ([RtcSession.close]) or because the signaling session ended. */
    CLOSED("closed"),
}

/** A remote track and its kind (`video` or `audio`), read once: the track is only valid until the session closes. */
class RemoteTrack(val track: MediaStreamTrack, val kind: String)

/** What happens in an [RtcSession], in order (see [RtcSession.events]). */
sealed interface RtcEvent {
    data class State(val state: RtcState) : RtcEvent

    /** The `control` channel opened: time for `hello` (viewer) or `info` (host). */
    data object ControlOpen : RtcEvent

    data object ControlClose : RtcEvent

    /** A control message not handled by the session itself (everything but ping/pong and files). */
    data class Control(val message: JsonObject) : RtcEvent

    /** Input events received on `input` or `pointer` (host side), when no [RtcSession.inputHandler] is set. */
    data class Input(val events: JsonArray) : RtcEvent

    /** A remote track. [kind] is `video` or `audio`. Valid until the session is closed. */
    class Track(val track: MediaStreamTrack, val kind: String) : RtcEvent
}

/**
 * The WebRTC side of a signaling session: sends and receives the `signal`
 * session messages.
 */
interface SignalLink {
    /** The decrypted session messages of the peer, in order; closed when the session ends. */
    val messages: ReceiveChannel<JsonObject>

    /** Sends a session message (`{type:'signal', data}`); calls made in sequence leave in order. Never throws. */
    fun send(message: JsonObject)
}

class RtcException(message: String?) : Exception(message)

/**
 * A WebRTC peer connection with the PairDesk conventions of
 * src/renderer/common/rtc.js:
 *
 * - [RtcRole.HOST] is the offerer: it creates the `control` (ordered),
 *   `input` (ordered) and `pointer` (`ordered:false, maxRetransmits:0`) data
 *   channels — only `control` plus a `recvonly` video transceiver for a camera
 *   session — and sends an offer whenever negotiation is needed;
 *   [RtcRole.CONTROLLER] answers and adopts the channels the host created.
 * - Trickle ICE through `{type:'signal', data:{candidate}|{description}}` with
 *   Chrome's JSON shapes; incoming signals are applied strictly one after the
 *   other, candidates arriving before the remote description are queued.
 * - `ping` is answered with `pong`, file offers are rejected (no file
 *   transfer in this app yet), everything else is published on [events].
 *
 * Usage: create, subscribe ([events], [inputHandler]), add local tracks
 * ([addTrack]), then [start] (the desktop's `ready()`); [close] when done.
 * The session closes itself when the signaling session ends.
 *
 * Threading: public methods may be called from any thread. Callbacks of
 * org.webrtc arrive on WebRTC's signaling thread and never block; negotiation
 * and disposal run on [RtcEnvironment.dispatcher].
 */
class RtcSession(
    private val env: RtcEnvironment,
    val role: RtcRole,
    iceServers: List<IceServerSpec>,
    private val link: SignalLink,
    /** Host only: a phone camera session (receives one video track, `control` channel only). */
    val camera: Boolean = false,
    private val log: Logger = Logger.NONE,
    /** Called once the session is closed and disposed. */
    private val onClosed: (RtcSession) -> Unit = {},
) {
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    /** Guards native objects: readers never wait (tryLock), [close] disposes under the write lock. */
    private val rw = ReentrantReadWriteLock()

    private val scope = CoroutineScope(
        SupervisorJob() + env.dispatcher + CoroutineExceptionHandler { _, e -> log.warn("[rtc] ${e.stackTraceToString()}") },
    )

    private val _state = MutableStateFlow(RtcState.NEW)

    /** `connectionState` of the peer connection ([RtcState.CLOSED] once closed). */
    val state: StateFlow<RtcState> = _state.asStateFlow()

    private val _controlOpen = MutableStateFlow(false)

    /** True while the `control` channel is open. */
    val controlOpen: StateFlow<Boolean> = _controlOpen.asStateFlow()

    private val _remoteTracks = MutableStateFlow<List<RemoteTrack>>(emptyList())

    /** Remote tracks received so far (emptied when the session closes, before the tracks are released). */
    val remoteTracks: StateFlow<List<RemoteTrack>> = _remoteTracks.asStateFlow()

    private val eventChannel = Channel<RtcEvent>(EVENT_BUFFER, BufferOverflow.DROP_OLDEST)

    /**
     * Everything that happens, in order, for a single collector (buffered until
     * collected; completes when the session is closed).
     */
    val events: Flow<RtcEvent> = eventChannel.receiveAsFlow()

    /**
     * Receives input events directly on WebRTC's thread (lowest latency, host
     * side) instead of [events]. Must be fast and must not call [close].
     */
    @Volatile
    var inputHandler: ((JsonArray) -> Unit)? = null

    private val disposed: CompletableJob = Job()

    // ─────────────────────────── negotiation state ───────────────────────────

    private sealed interface Op {
        class Remote(val message: JsonObject) : Op

        class Offer(val iceRestart: Boolean) : Op
    }

    private val ops = Channel<Op>(Channel.UNLIMITED)
    private val startLock = Any()
    private var offerWanted = false
    private val offerQueued = AtomicBoolean(false)

    /** An offer was asked for while an earlier one awaited its answer. */
    private var offerWhenStable = false
    private val pendingCandidates = ArrayList<SignalCandidate>()

    /** Local candidates wait while a local description is being set and sent (it must go first). */
    private val outLock = Any()
    private var holdCandidates = false
    private val heldCandidates = ArrayList<JsonObject>()

    // ─────────────────────────── channels and media ───────────────────────────

    private val channels = CopyOnWriteArrayList<DataChannel>()

    @Volatile
    private var control: DataChannel? = null

    @Volatile
    private var input: DataChannel? = null

    @Volatile
    private var pointer: DataChannel? = null

    @Volatile
    private var remoteVideo: VideoTrack? = null
    private val videoSinks = CopyOnWriteArraySet<VideoSink>()
    private val tracker = RtcStatsTracker()

    /** The underlying peer connection, for what this class does not wrap (codec preferences, encodings…). */
    val peerConnection: PeerConnection = env.factory.createPeerConnection(env.configuration(iceServers), Observer())
        ?: throw RtcException("cannot create the peer connection")

    init {
        if (role == RtcRole.HOST) {
            adopt(peerConnection.createDataChannel(LABEL_CONTROL, DataChannel.Init().apply { ordered = true }))
            if (camera) {
                peerConnection.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
                )
            } else {
                adopt(peerConnection.createDataChannel(LABEL_INPUT, DataChannel.Init().apply { ordered = true }))
                adopt(
                    peerConnection.createDataChannel(
                        LABEL_POINTER,
                        DataChannel.Init().apply {
                            ordered = false
                            maxRetransmits = 0
                        },
                    ),
                )
            }
        }
    }

    // ─────────────────────────── lifecycle ───────────────────────────

    /**
     * Starts applying the peer's signals (and, for a host, sending the offer).
     * Call it once the local tracks are added. Idempotent.
     */
    fun start() {
        synchronized(startLock) {
            if (closed.get() || !started.compareAndSet(false, true)) return
            scope.launch {
                for (op in ops) process(op)
            }
            scope.launch {
                for (message in link.messages) ops.send(Op.Remote(message))
                log.info("[rtc] signaling session ended")
                close()
            }
            if (offerWanted) {
                offerWanted = false
                requestOfferLocked()
            }
        }
    }

    val isClosed: Boolean get() = closed.get()

    /**
     * Closes the connection and releases every native object (the remote
     * tracks become invalid; local tracks stay owned by the caller). Returns a
     * job completed once everything is disposed. Idempotent.
     */
    fun close(): Job {
        if (!closed.compareAndSet(false, true)) return disposed
        log.info("[rtc] closing")
        ops.close()
        scope.cancel()
        _controlOpen.value = false
        if (_state.value != RtcState.CLOSED) {
            _state.value = RtcState.CLOSED
            eventChannel.trySend(RtcEvent.State(RtcState.CLOSED))
        }
        eventChannel.close()
        env.executor.execute {
            val write = rw.writeLock()
            write.lock()
            try {
                val video = remoteVideo
                remoteVideo = null
                for (sink in videoSinks) runCatching { video?.removeSink(sink) }
                videoSinks.clear()
                _remoteTracks.value = emptyList()
                control = null
                input = null
                pointer = null
                for (dc in channels) {
                    runCatching {
                        dc.unregisterObserver()
                        dc.close()
                        dc.dispose()
                    }
                }
                channels.clear()
                runCatching { peerConnection.dispose() }.onFailure { log.warn("[rtc] dispose failed: ${it.message}") }
            } finally {
                write.unlock()
            }
            disposed.complete()
            onClosed(this)
        }
        return disposed
    }

    /** Runs [block] on live native objects, or returns [default] when closed (never waits). */
    private inline fun <T> guarded(default: T, block: () -> T): T {
        val read = rw.readLock()
        if (!read.tryLock()) return default
        try {
            if (closed.get()) return default
            return block()
        } finally {
            read.unlock()
        }
    }

    private fun emit(event: RtcEvent) {
        if (!closed.get()) eventChannel.trySend(event)
    }

    private fun setState(state: RtcState) {
        if (closed.get() || _state.value == state) return
        _state.value = state
        emit(RtcEvent.State(state))
    }

    // ─────────────────────────── media ───────────────────────────

    /** Adds a local track (screen or camera) before [start]; the caller keeps owning it. */
    fun addTrack(track: MediaStreamTrack, streamIds: List<String> = listOf(DEFAULT_STREAM)): RtpSender? =
        guarded(null) { peerConnection.addTrack(track, streamIds) }

    /** Shows the remote video in [sink] (follows a replaced track; detached on close). */
    fun addVideoSink(sink: VideoSink) {
        videoSinks += sink
        guarded(Unit) { remoteVideo?.addSink(sink) }
    }

    fun removeVideoSink(sink: VideoSink) {
        videoSinks -= sink
        guarded(Unit) { remoteVideo?.removeSink(sink) }
    }

    /** Mutes or unmutes the partner's audio (played by the audio device module). */
    fun setRemoteAudioEnabled(enabled: Boolean) {
        guarded(Unit) {
            for (t in _remoteTracks.value) if (t.kind == MediaStreamTrack.AUDIO_TRACK_KIND) runCatching { t.track.setEnabled(enabled) }
        }
    }

    private fun onRemoteTrack(track: MediaStreamTrack) {
        val kind = guarded(null) { track.kind() } ?: return
        log.info("[rtc] remote $kind track")
        if (track is VideoTrack) {
            // Attached from our thread: org.webrtc calls are not made from its own callbacks.
            scope.launch {
                guarded(Unit) {
                    val previous = remoteVideo
                    remoteVideo = track
                    for (sink in videoSinks) {
                        if (previous != null && previous !== track) previous.removeSink(sink)
                        track.addSink(sink)
                    }
                }
            }
        }
        _remoteTracks.update { it + RemoteTrack(track, kind) }
        emit(RtcEvent.Track(track, kind))
    }

    // ─────────────────────────── data channels ───────────────────────────

    private fun adopt(dc: DataChannel?) {
        if (dc == null) return
        channels += dc
        when (val label = dc.label()) {
            LABEL_CONTROL -> {
                control = dc
                dc.registerObserver(ChannelObserver(dc, label))
            }
            LABEL_INPUT, LABEL_POINTER -> {
                if (label == LABEL_INPUT) input = dc else pointer = dc
                dc.registerObserver(ChannelObserver(dc, label))
            }
            else -> {
                // `file:<fid>` (no transfer accepted by this app) or unknown: closed.
                scope.launch { guarded(Unit) { dc.close() } }
            }
        }
    }

    private inner class ChannelObserver(private val dc: DataChannel, private val label: String) : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit

        override fun onStateChange() {
            if (label != LABEL_CONTROL || closed.get()) return
            when (runCatching { dc.state() }.getOrNull()) {
                DataChannel.State.OPEN -> {
                    _controlOpen.value = true
                    emit(RtcEvent.ControlOpen)
                }
                DataChannel.State.CLOSED -> {
                    _controlOpen.value = false
                    emit(RtcEvent.ControlClose)
                }
                else -> Unit
            }
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            if (buffer.binary || closed.get()) return
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            val text = String(bytes, Charsets.UTF_8)
            if (label == LABEL_CONTROL) onControlText(text) else onInputText(text)
        }
    }

    private fun onControlText(text: String) {
        val msg = JsonJs.parseObjectOrNull(text) ?: return
        when (msg.str("type")) {
            "ping" -> {
                val pong = RtcControl.pong(msg)
                scope.launch { sendControl(pong) }
            }
            "file-offer" -> RtcControl.fileReject(msg)?.let { reject -> scope.launch { sendControl(reject) } }
            "pong", "file-accept", "file-reject", "file-done", "file-cancel" -> Unit
            else -> emit(RtcEvent.Control(msg))
        }
    }

    private fun onInputText(text: String) {
        val events = try {
            JsonJs.parse(text) as? JsonArray
        } catch (_: Exception) {
            null
        } ?: return
        val handler = inputHandler
        if (handler != null) handler(events) else emit(RtcEvent.Input(events))
    }

    private fun send(channel: DataChannel?, text: String): Boolean = guarded(false) {
        if (channel == null || channel.state() != DataChannel.State.OPEN) return@guarded false
        try {
            channel.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false))
        } catch (e: RuntimeException) {
            log.warn("[rtc] send on ${channel.label()} failed: ${e.message}")
            false
        }
    }

    /** Sends a JSON control message; false when the channel is not open. */
    fun sendControl(message: JsonObject): Boolean = send(control, JsonJs.stringify(message))

    /** Clicks, keys, wheel, text: ordered and reliable. */
    fun sendInput(events: JsonArray) {
        if (events.isNotEmpty()) send(input, JsonJs.stringify(events))
    }

    /** Mouse moves: latest wins (falls back to the reliable channel on hosts older than 1.1). */
    fun sendPointer(events: JsonArray) {
        if (events.isEmpty()) return
        if (!send(pointer, JsonJs.stringify(events))) sendInput(events)
    }

    // ─────────────────────────── negotiation ───────────────────────────

    /** Host: new offer with an ICE restart (connection lost). */
    fun restartIce() {
        if (role == RtcRole.HOST && started.get() && !closed.get()) ops.trySend(Op.Offer(iceRestart = true))
    }

    /** Host: new offer with the current transceiver settings (e.g. another codec). */
    fun renegotiate() {
        if (role == RtcRole.HOST && started.get() && !closed.get()) ops.trySend(Op.Offer(iceRestart = false))
    }

    private fun requestOffer() = synchronized(startLock) { requestOfferLocked() }

    private fun requestOfferLocked() {
        if (closed.get() || role != RtcRole.HOST) return
        if (!started.get()) {
            offerWanted = true
            return
        }
        if (offerQueued.compareAndSet(false, true)) ops.trySend(Op.Offer(iceRestart = false))
    }

    private suspend fun process(op: Op) {
        if (closed.get()) return
        try {
            when (op) {
                is Op.Remote -> onRemote(op.message)
                is Op.Offer -> {
                    offerQueued.set(false)
                    offer(op.iceRestart)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Like the desktop: logged, the next signals are still applied.
            log.warn("[rtc] signaling error: ${e.message}")
        }
    }

    private suspend fun offer(iceRestart: Boolean) {
        val pc = peerConnection
        if (guarded(null) { pc.signalingState() } != PeerConnection.SignalingState.STABLE) {
            offerWhenStable = true
            return
        }
        val constraints = MediaConstraints()
        if (iceRestart) constraints.mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        val offer = pc.awaitCreate(offer = true, constraints)
        log.info("[rtc] sending offer${if (iceRestart) " (ICE restart)" else ""}")
        setLocalAndSend(offer)
    }

    private suspend fun onRemote(message: JsonObject) {
        if (message.str("type") != "signal") return
        val description = SessionMessages.descriptionOf(message)
        if (description != null) {
            applyDescription(description)
            return
        }
        val candidate = SessionMessages.candidateOf(message) ?: return
        if (guarded(null) { peerConnection.remoteDescription } == null) {
            pendingCandidates += candidate
        } else {
            addCandidate(candidate)
        }
    }

    private suspend fun applyDescription(d: SignalDescription) {
        val type = descriptionType(d.type) ?: throw RtcException("unknown description type ${d.type}")
        log.info("[rtc] received ${d.type}")
        val pc = peerConnection
        pc.awaitSet(local = false, SessionDescription(type, d.sdp))
        if (type == SessionDescription.Type.OFFER) {
            val answer = pc.awaitCreate(offer = false, MediaConstraints())
            setLocalAndSend(answer)
        }
        val queued = pendingCandidates.toList()
        pendingCandidates.clear()
        for (c in queued) addCandidate(c)
        if (offerWhenStable && guarded(null) { pc.signalingState() } == PeerConnection.SignalingState.STABLE) {
            offerWhenStable = false
            requestOffer()
        }
    }

    /** Sets [sdp] as local description, then sends it before any candidate gathered meanwhile. */
    private suspend fun setLocalAndSend(sdp: SessionDescription) {
        synchronized(outLock) { holdCandidates = true }
        try {
            peerConnection.awaitSet(local = true, sdp)
            val local = guarded(null) { peerConnection.localDescription } ?: sdp
            synchronized(outLock) { link.send(RtcSignals.description(local.type.canonicalForm(), local.description)) }
        } finally {
            synchronized(outLock) {
                holdCandidates = false
                for (c in heldCandidates) link.send(c)
                heldCandidates.clear()
            }
        }
    }

    private suspend fun addCandidate(c: SignalCandidate) {
        if (c.candidate.isEmpty() || (c.sdpMid == null && c.sdpMLineIndex == null)) return
        val candidate = IceCandidate(c.sdpMid ?: "", c.sdpMLineIndex ?: 0, c.candidate)
        // Errors are ignored, like the desktop (`addIceCandidate(c).catch(() => {})`).
        suspendCancellableCoroutine { cont ->
            val added = guarded(false) {
                peerConnection.addIceCandidate(
                    candidate,
                    object : AddIceObserver {
                        override fun onAddSuccess() = cont.resume(Unit)

                        override fun onAddFailure(error: String?) = cont.resume(Unit)
                    },
                )
                true
            }
            if (!added) cont.resume(Unit)
        }
    }

    private suspend fun PeerConnection.awaitCreate(offer: Boolean, constraints: MediaConstraints): SessionDescription =
        suspendCancellableCoroutine { cont ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)

                override fun onCreateFailure(error: String?) = cont.resumeWithException(RtcException("create ${if (offer) "offer" else "answer"}: $error"))

                override fun onSetSuccess() = Unit

                override fun onSetFailure(error: String?) = Unit
            }
            val called = guarded(false) {
                if (offer) createOffer(observer, constraints) else createAnswer(observer, constraints)
                true
            }
            if (!called) cont.resumeWithException(RtcException("closed"))
        }

    private suspend fun PeerConnection.awaitSet(local: Boolean, sdp: SessionDescription) =
        suspendCancellableCoroutine { cont ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) = Unit

                override fun onCreateFailure(error: String?) = Unit

                override fun onSetSuccess() = cont.resume(Unit)

                override fun onSetFailure(error: String?) =
                    cont.resumeWithException(RtcException("set ${if (local) "local" else "remote"} ${sdp.type.canonicalForm()}: $error"))
            }
            val called = guarded(false) {
                if (local) setLocalDescription(observer, sdp) else setRemoteDescription(observer, sdp)
                true
            }
            if (!called) cont.resumeWithException(RtcException("closed"))
        }

    // ─────────────────────────── stats ───────────────────────────

    private suspend fun report(): List<StatEntry>? {
        val result = CompletableDeferred<List<StatEntry>>()
        val requested = guarded(false) {
            peerConnection.getStats { report ->
                result.complete(report.statsMap.values.map { StatEntry(it.id, it.type, it.timestampUs, it.members) })
            }
            true
        }
        if (!requested) return null
        return withTimeoutOrNull(STATS_TIMEOUT_MS) { result.await() }
    }

    /** Receiver (viewer) side statistics (RtcSession.stats() of rtc.js). */
    suspend fun stats(): ReceiverStats {
        val report = report() ?: return ReceiverStats()
        return synchronized(tracker) { tracker.receiver(report) }
    }

    /** Sender (host, camera) side statistics (RtcSession.senderStats() of rtc.js). */
    suspend fun senderStats(): SenderStats {
        val report = report() ?: return SenderStats()
        return synchronized(tracker) { tracker.sender(report) }
    }

    // ─────────────────────────── observer ───────────────────────────

    private inner class Observer : PeerConnection.Observer {
        override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            if (!closed.get()) log.info("[rtc] ice ${newState?.name?.lowercase()}")
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            if (closed.get() || newState == null) return
            log.info("[rtc] connection ${newState.name.lowercase()}")
            setState(
                when (newState) {
                    PeerConnection.PeerConnectionState.NEW -> RtcState.NEW
                    PeerConnection.PeerConnectionState.CONNECTING -> RtcState.CONNECTING
                    PeerConnection.PeerConnectionState.CONNECTED -> RtcState.CONNECTED
                    PeerConnection.PeerConnectionState.DISCONNECTED -> RtcState.DISCONNECTED
                    PeerConnection.PeerConnectionState.FAILED -> RtcState.FAILED
                    PeerConnection.PeerConnectionState.CLOSED -> RtcState.CLOSED
                },
            )
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
            if (!closed.get() && newState == PeerConnection.IceGatheringState.COMPLETE) log.info("[rtc] local ICE gathering complete")
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            if (candidate == null || closed.get()) return
            log.info("[rtc] local candidate ${RtcSignals.typeOf(candidate.sdp) ?: "?"}")
            val message = RtcSignals.candidate(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
            synchronized(outLock) {
                if (holdCandidates) heldCandidates += message else link.send(message)
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit

        override fun onAddStream(stream: MediaStream?) = Unit

        override fun onRemoveStream(stream: MediaStream?) = Unit

        override fun onDataChannel(dc: DataChannel?) {
            if (closed.get()) {
                // Not ours to keep: released right away.
                dc?.let { channel -> env.executor.execute { runCatching { channel.dispose() } } }
                return
            }
            adopt(dc)
        }

        override fun onRenegotiationNeeded() {
            if (role == RtcRole.HOST) requestOffer()
        }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit

        override fun onTrack(transceiver: RtpTransceiver?) {
            if (closed.get()) return
            val track = transceiver?.receiver?.track() ?: return
            onRemoteTrack(track)
        }
    }

    companion object {
        const val LABEL_CONTROL = "control"
        const val LABEL_INPUT = "input"
        const val LABEL_POINTER = "pointer"
        const val DEFAULT_STREAM = "pairdesk"

        private const val EVENT_BUFFER = 1024
        private const val STATS_TIMEOUT_MS = 3_000L

        fun descriptionType(type: String): SessionDescription.Type? = when (type) {
            "offer" -> SessionDescription.Type.OFFER
            "answer" -> SessionDescription.Type.ANSWER
            "pranswer" -> SessionDescription.Type.PRANSWER
            "rollback" -> SessionDescription.Type.ROLLBACK
            else -> null
        }
    }
}
