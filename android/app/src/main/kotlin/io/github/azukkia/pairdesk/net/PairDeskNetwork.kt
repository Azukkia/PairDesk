package io.github.azukkia.pairdesk.net

import io.github.azukkia.pairdesk.core.Protocol
import io.github.azukkia.pairdesk.core.crypto.Prs
import io.github.azukkia.pairdesk.core.signaling.AuthLimiter
import io.github.azukkia.pairdesk.core.signaling.Caps
import io.github.azukkia.pairdesk.core.signaling.Credential
import io.github.azukkia.pairdesk.core.signaling.SessionMessages
import io.github.azukkia.pairdesk.core.signaling.Signaling
import io.github.azukkia.pairdesk.core.signaling.SignalingEvent
import io.github.azukkia.pairdesk.core.signaling.SignalingException
import io.github.azukkia.pairdesk.core.signaling.SignalingSession
import io.github.azukkia.pairdesk.core.signaling.Timeouts
import io.github.azukkia.pairdesk.core.transport.Logger
import io.github.azukkia.pairdesk.core.transport.MqttTransport
import io.github.azukkia.pairdesk.core.transport.SignalingTransport
import io.github.azukkia.pairdesk.core.transport.TransportKind
import io.github.azukkia.pairdesk.core.transport.TransportState
import io.github.azukkia.pairdesk.core.transport.WsTransport
import io.github.azukkia.pairdesk.data.PrsUpdate
import io.github.azukkia.pairdesk.data.SettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Connection to the PairDesk network, for the status dot. */
data class NetworkStatus(
    /** OFFLINE while the network is stopped (app in the background, no session). */
    val state: TransportState,
    val kind: TransportKind,
    /** Private server URL ([TransportKind.SERVER]). */
    val serverUrl: String? = null,
    /** Public relays connected right now ([TransportKind.PUBLIC]). */
    val relays: Int? = null,
    /** Last server error, e.g. `id-taken`. */
    val error: String? = null,
) {
    val isOnline: Boolean get() = state == TransportState.ONLINE
}

enum class IncomingState {
    /** Authenticated; the local user must accept or decline. */
    PENDING,

    /** Accepted: the session runs. */
    ACTIVE,
}

/** A partner connected to this phone (this phone is the host). */
data class IncomingSession(
    val session: SignalingSession,
    val state: IncomingState,
    /** Pending requests are declined automatically at this time (epoch ms). */
    val deadline: Long?,
) {
    val sid: String get() = session.sid
    val peerId: String get() = session.peerId
    val peerName: String get() = session.peerName

    /** Which password matched: `temp` or `perm`. */
    val credential: String? get() = session.credential
}

/** A session ended: by the peer (`bye`) or locally. */
data class SessionEnd(val sid: String, val reason: String, val byPeer: Boolean)

/**
 * App-scoped owner of the signaling transport, the [Signaling] engine and the
 * host passwords (src/main/network.js and the session bookkeeping of
 * src/main/sessions.js of the desktop).
 *
 * The transport runs while at least one holder [acquire]d it: the app in the
 * foreground (`"foreground"`), a running session (`"session:<sid>"`), a
 * connection attempt. It stops [stopGraceMs] after the last [release].
 *
 * Pure JVM (no Android API): the Android glue (process lifecycle,
 * connectivity) calls [acquire], [release] and [onNetworkAvailable].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairDeskNetwork(
    private val settings: SettingsStore,
    /** Announced to partners (`appVersion` of intro / accepted). */
    val appVersion: String,
    private val log: Logger = Logger.NONE,
    private val transportFactory: TransportFactory = TransportFactory.DEFAULT,
    private val derivePrs: suspend (password: String, hostId: String) -> ByteArray = ::derivePrsInBackground,
    timeouts: Timeouts = Timeouts(),
    private val stopGraceMs: Long = STOP_GRACE_MS,
    private val consentTimeoutMs: Long = CONSENT_TIMEOUT_MS,
    private val onlineWaitMs: Long = ONLINE_WAIT_MS,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val myId: String = settings.deviceId

    private val lock = Any()
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> log.warn("[network] ${e.stackTraceToString()}") },
    )

    /** Transport start/stop may block (MQTT disconnects): serialized, off the main thread. */
    private val transportOps = ioDispatcher.limitedParallelism(1)

    // ───────────────────────────── transport ─────────────────────────────

    private var config: TransportConfig = TransportConfig.from(settings.settings.value)
    private val _transportError = MutableStateFlow<String?>(null)
    private val _transport = MutableStateFlow(createTransport(config))
    private val holders = HashSet<String>()
    private var running = false
    private var stopJob: Job? = null

    /** The transport in use (it changes with the network settings). */
    val transport: SignalingTransport get() = _transport.value

    // ───────────────────────────── host passwords ─────────────────────────────

    private val credLock = Any()

    @Volatile
    private var tempPrs: ByteArray? = settings.tempPrs()

    @Volatile
    private var permanentPrs: ByteArray? = settings.permanentPrs()

    /** The temporary password to show (its PRS may still be computing). */
    val hostPassword: StateFlow<String> = settings.tempPassword

    // ───────────────────────────── sessions ─────────────────────────────

    private val _incoming = MutableStateFlow<IncomingSession?>(null)

    /** The partner connected (or asking to connect) to this phone, if any. */
    val incoming: StateFlow<IncomingSession?> = _incoming.asStateFlow()
    private var consentJob: Job? = null

    private val outgoing = LinkedHashMap<String, OutgoingSession>()
    private val _outgoing = MutableStateFlow<List<OutgoingSession>>(emptyList())

    /** Established outgoing sessions (viewer / camera). */
    val outgoingSessions: StateFlow<List<OutgoingSession>> = _outgoing.asStateFlow()

    private val _ended = MutableSharedFlow<SessionEnd>(extraBufferCapacity = 32)

    /** Sessions that ended (by the peer or locally), for the screens showing them. */
    val sessionEnded: SharedFlow<SessionEnd> = _ended.asSharedFlow()

    private val _authFailures = MutableSharedFlow<SignalingEvent.AuthFailed>(extraBufferCapacity = 32)

    /** Failed password attempts against this phone. */
    val authFailures: SharedFlow<SignalingEvent.AuthFailed> = _authFailures.asSharedFlow()

    val signaling: Signaling = Signaling(
        transport = _transport.value,
        myId = myId,
        credentials = ::credentials,
        canAccept = ::canAccept,
        limiter = AuthLimiter(),
        timeouts = timeouts,
        log = log,
        dispatcher = dispatcher,
    )

    /** Online / connecting / offline, with details for the settings screen. */
    val status: StateFlow<NetworkStatus> = combine(
        _transport.flatMapLatest { t -> t.state.map { s -> t to s } },
        _transportError,
    ) { (t, s), error -> describe(t, s, error) }
        .stateIn(scope, SharingStarted.Eagerly, describe(_transport.value, TransportState.OFFLINE, null))

    private val connector = Connector(
        myId = myId,
        signaling = { signaling },
        recents = object : RecentsAccess {
            override fun savedPrs(id: String) = settings.savedPrs(id)
            override fun touchRecent(id: String, name: String?, update: PrsUpdate) = settings.touchRecent(id, name, update)
            override fun forgetRecentPassword(id: String) = settings.forgetRecentPassword(id)
        },
        intro = { kind -> SessionMessages.intro(name = settings.displayName, appVersion = appVersion, kind = kind.wire) },
        derivePrs = derivePrs,
        awaitOnline = ::awaitOnline,
    )

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            signaling.events.collect { event ->
                when (event) {
                    is SignalingEvent.Incoming -> onIncoming(event.session)
                    is SignalingEvent.Closed -> onPeerClosed(event.sid, event.reason)
                    is SignalingEvent.AuthFailed -> _authFailures.tryEmit(event)
                    is SignalingEvent.Message -> Unit // read by the RtcSession of the session
                }
            }
        }
        scope.launch {
            settings.settings.map { TransportConfig.from(it) }.distinctUntilChanged().collect { cfg ->
                if (cfg != synchronized(lock) { config }) switchTransport(cfg)
            }
        }
        if (tempPrs == null) deriveTempPrs(settings.tempPassword.value)
    }

    private fun createTransport(cfg: TransportConfig): SignalingTransport =
        transportFactory.create(cfg, log) { _transportError.value = "id-taken" }

    private fun describe(t: SignalingTransport, s: TransportState, error: String?) = NetworkStatus(
        state = s,
        kind = t.kind,
        serverUrl = (t as? WsTransport)?.url,
        relays = (t as? MqttTransport)?.connectedBrokers?.size,
        error = error ?: (t as? WsTransport)?.lastError?.takeIf { s != TransportState.ONLINE },
    )

    /** Keeps the network running for [tag] (idempotent per tag). */
    fun acquire(tag: String) = synchronized(lock) {
        if (!holders.add(tag)) return@synchronized
        stopJob?.cancel()
        stopJob = null
        if (!running) {
            running = true
            val t = _transport.value
            scope.launch(transportOps) { t.start(myId, settings.deviceKey) }
            log.info("[network] started (${config::class.simpleName})")
        }
    }

    /** Releases [tag]; the network stops [stopGraceMs] after the last holder left. */
    fun release(tag: String) = synchronized(lock) {
        if (!holders.remove(tag) || holders.isNotEmpty() || !running) return@synchronized
        stopJob?.cancel()
        stopJob = scope.launch {
            delay(stopGraceMs)
            synchronized(lock) {
                if (holders.isNotEmpty() || !running) return@synchronized
                running = false
                val t = _transport.value
                scope.launch(transportOps) { t.stop() }
                log.info("[network] stopped")
            }
        }
    }

    val isRunning: Boolean get() = synchronized(lock) { running }

    /** The device's connectivity came back: retry the relays now. */
    fun onNetworkAvailable() {
        val t = _transport.value
        scope.launch(transportOps) { if (isRunning) t.reconnectNow() }
    }

    /** Re-creates the transport after a network settings change; established sessions are kept. */
    private fun switchTransport(cfg: TransportConfig) = synchronized(lock) {
        config = cfg
        val old = _transport.value
        val fresh = createTransport(cfg)
        signaling.setTransport(fresh)
        _transportError.value = null
        _transport.value = fresh
        val start = running
        scope.launch(transportOps) {
            TransportFactory.dispose(old)
            if (start) fresh.start(myId, settings.deviceKey)
        }
        log.info("[network] transport changed: ${cfg::class.simpleName}")
    }

    /** STUN servers of the configuration, then the TURN servers of a private server. */
    fun iceServers(): List<IceServerSpec> = IceServers.combine(_transport.value.iceServers.value)

    private suspend fun awaitOnline(): Boolean {
        val reached = withTimeoutOrNull(onlineWaitMs) {
            status.first { it.state == TransportState.ONLINE || it.state == TransportState.ERROR }
        }
        return reached?.state == TransportState.ONLINE
    }

    // ───────────────────────────── host passwords ─────────────────────────────

    private fun credentials(): List<Credential> {
        val list = ArrayList<Credential>(2)
        tempPrs?.let { list += Credential("temp", it) }
        permanentPrs?.let { list += Credential("perm", it) }
        return list
    }

    private fun deriveTempPrs(password: String) {
        scope.launch {
            val prs = derivePrs(password, myId)
            synchronized(credLock) {
                if (settings.tempPassword.value == password && settings.storeTempPrs(password, prs)) tempPrs = prs
            }
        }
    }

    /** A new temporary password (the previous one stops working at once). */
    fun regeneratePassword(): String = synchronized(credLock) {
        tempPrs = null
        settings.regenerateTempPassword()
    }.also(::deriveTempPrs)

    /**
     * Sets the permanent password (at least [Protocol.MIN_PERMANENT_PASSWORD_LENGTH]
     * characters), or removes it with null. Only its PRS is stored. False when
     * it is too short or cannot be stored securely.
     */
    suspend fun setPermanentPassword(password: String?): Boolean {
        if (password == null) {
            settings.setPermanentPrs(null)
            permanentPrs = null
            return true
        }
        if (Protocol.normalizePassword(password).length < Protocol.MIN_PERMANENT_PASSWORD_LENGTH) return false
        val prs = derivePrs(password, myId)
        if (!settings.setPermanentPrs(prs)) return false
        permanentPrs = prs
        return true
    }

    /** True once the temporary password can be used by partners. */
    val hostReady: Boolean get() = tempPrs != null || permanentPrs != null

    // ───────────────────────────── outgoing sessions ─────────────────────────────

    /**
     * Connects to a computer (probe, PRS, CPace, approval). On success the
     * session is registered (see [outgoingSession], [endOutgoing]) and keeps the
     * network running until it ends. Cancel the calling coroutine to abort.
     */
    suspend fun connect(request: ConnectRequest, onStep: (ConnectStep) -> Unit = {}): ConnectResult {
        val tag = "connect:" + System.identityHashCode(request)
        acquire(tag)
        try {
            val result = connector.connect(request, onStep)
            if (result is ConnectResult.Success) registerOutgoing(result.outgoing)
            return result
        } finally {
            release(tag)
        }
    }

    private fun registerOutgoing(o: OutgoingSession) {
        synchronized(lock) {
            outgoing[o.sid] = o
            _outgoing.value = outgoing.values.toList()
        }
        acquire(sessionTag(o.sid))
        // The peer may have ended it while we were registering it.
        if (!o.session.isActive) onPeerClosed(o.sid, o.session.closeReason ?: "closed")
    }

    fun outgoingSession(sid: String): OutgoingSession? = synchronized(lock) { outgoing[sid] }

    /** Ends an outgoing session (sends `bye` with [reason]). */
    fun endOutgoing(sid: String, reason: String = "closed") {
        val o = synchronized(lock) {
            outgoing.remove(sid)?.also { _outgoing.value = outgoing.values.toList() }
        } ?: return
        signaling.close(o.sid, reason)
        release(sessionTag(sid))
        _ended.tryEmit(SessionEnd(sid, reason, byPeer = false))
    }

    // ───────────────────────────── incoming sessions ─────────────────────────────

    private fun canAccept(): String? {
        if (!settings.settings.value.allowIncoming) return "disabled"
        if (_incoming.value != null) return "busy"
        return null
    }

    private fun onIncoming(session: SignalingSession) {
        val refusal = synchronized(lock) {
            when {
                _incoming.value != null -> "busy"
                // A phone only shares its screen; camera sessions go phone → computer.
                session.kind != SessionMessages.KIND_CONTROL -> "unsupported"
                else -> {
                    _incoming.value = IncomingSession(session, IncomingState.PENDING, clock() + consentTimeoutMs)
                    null
                }
            }
        }
        if (refusal != null) {
            scope.launch { signaling.reject(session.sid, refusal) }
            return
        }
        log.info("[network] incoming session from ${session.peerId} (${session.credential} password)")
        acquire(sessionTag(session.sid))
        scope.launch { signaling.notifyWaiting(session.sid) }
        synchronized(lock) {
            consentJob?.cancel()
            consentJob = scope.launch {
                delay(consentTimeoutMs)
                declineIncoming(session.sid, "timeout")
            }
        }
    }

    /**
     * Accepts the pending incoming session [sid]: sends `accepted` with this
     * phone's name, [caps], platform `android` and the app version, and
     * returns once a relay took it (the WebRTC offer must follow it).
     */
    suspend fun acceptIncoming(sid: String, caps: Caps): Boolean {
        synchronized(lock) {
            val current = _incoming.value
            if (current == null || current.sid != sid || current.state != IncomingState.PENDING) return false
            consentJob?.cancel()
            consentJob = null
            _incoming.value = current.copy(state = IncomingState.ACTIVE, deadline = null)
        }
        return try {
            signaling.accept(sid, SessionMessages.accepted(name = settings.displayName, caps = caps, appVersion = appVersion))
            true
        } catch (e: SignalingException) {
            log.warn("[network] accept failed: ${e.code}")
            endIncoming(sid, "network")
            false
        }
    }

    /** Declines the pending incoming session [sid] (`rejected` with [reason]). */
    fun declineIncoming(sid: String, reason: String = "declined") {
        val current = synchronized(lock) {
            val c = _incoming.value
            if (c == null || c.sid != sid || c.state != IncomingState.PENDING) return
            consentJob?.cancel()
            consentJob = null
            _incoming.value = null
            c
        }
        scope.launch { signaling.reject(current.sid, reason) }
        release(sessionTag(sid))
        _ended.tryEmit(SessionEnd(sid, reason, byPeer = false))
    }

    /** Ends the incoming session [sid], pending or active. */
    fun endIncoming(sid: String, reason: String = "host-closed") {
        val current = synchronized(lock) {
            val c = _incoming.value
            if (c == null || c.sid != sid) return
            consentJob?.cancel()
            consentJob = null
            _incoming.value = null
            c
        }
        if (current.state == IncomingState.PENDING) scope.launch { signaling.reject(sid, reason) } else signaling.close(sid, reason)
        release(sessionTag(sid))
        _ended.tryEmit(SessionEnd(sid, reason, byPeer = false))
    }

    /** Capabilities announced to the controller (files, clipboard and audio are not supported yet). */
    fun hostCaps(controlAvailable: Boolean): Caps =
        Caps(control = controlAvailable && settings.settings.value.allowControl, files = false, clipboard = false, audio = false)

    private fun onPeerClosed(sid: String, reason: String) {
        val wasOutgoing = synchronized(lock) {
            outgoing.remove(sid)?.also { _outgoing.value = outgoing.values.toList() } != null
        }
        val wasIncoming = synchronized(lock) {
            val c = _incoming.value
            if (c != null && c.sid == sid) {
                consentJob?.cancel()
                consentJob = null
                _incoming.value = null
                true
            } else {
                false
            }
        }
        if (!wasOutgoing && !wasIncoming) return
        log.info("[network] session $sid closed by the peer ($reason)")
        release(sessionTag(sid))
        _ended.tryEmit(SessionEnd(sid, reason, byPeer = true))
    }

    /** True while [sid] is an established session. */
    fun isSessionActive(sid: String): Boolean = signaling.session(sid)?.isActive == true

    /** Stops everything for good (tests; the app keeps one instance for its whole life). */
    fun dispose() {
        signaling.dispose()
        val t = _transport.value
        scope.launch(transportOps) { TransportFactory.dispose(t) }.invokeOnCompletion { scope.cancel() }
    }

    private fun sessionTag(sid: String) = "session:$sid"

    companion object {
        /** Keeps the relays connected a little after the app left the foreground. */
        const val STOP_GRACE_MS = 30_000L

        /** Pending incoming requests are declined after this delay (same as the desktop). */
        const val CONSENT_TIMEOUT_MS = 45_000L

        /** How long a connection attempt waits for the transport to come online. */
        const val ONLINE_WAIT_MS = 12_000L

        suspend fun derivePrsInBackground(password: String, hostId: String): ByteArray =
            withContext(Dispatchers.Default) { Prs.derive(password, hostId) }
    }
}
