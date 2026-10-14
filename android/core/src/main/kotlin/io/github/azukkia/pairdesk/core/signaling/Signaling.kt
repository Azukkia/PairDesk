package io.github.azukkia.pairdesk.core.signaling

import io.github.azukkia.pairdesk.core.Protocol
import io.github.azukkia.pairdesk.core.crypto.B64u
import io.github.azukkia.pairdesk.core.crypto.CPace
import io.github.azukkia.pairdesk.core.crypto.CPaceException
import io.github.azukkia.pairdesk.core.crypto.ChannelException
import io.github.azukkia.pairdesk.core.crypto.Rng
import io.github.azukkia.pairdesk.core.crypto.SecureChannel
import io.github.azukkia.pairdesk.core.crypto.Sealed
import io.github.azukkia.pairdesk.core.crypto.SessionKeys
import io.github.azukkia.pairdesk.core.crypto.constantTimeEquals
import io.github.azukkia.pairdesk.core.json.JsonJs
import io.github.azukkia.pairdesk.core.json.jsNumberOrNull
import io.github.azukkia.pairdesk.core.json.jsReason
import io.github.azukkia.pairdesk.core.json.jsSafeIntegerOrNull
import io.github.azukkia.pairdesk.core.json.jsStringOrNull
import io.github.azukkia.pairdesk.core.json.str
import io.github.azukkia.pairdesk.core.transport.Logger
import io.github.azukkia.pairdesk.core.transport.SignalingTransport
import io.github.azukkia.pairdesk.core.transport.TransportListener
import cafe.cryptography.curve25519.Scalar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Session establishment on top of a [SignalingTransport]
 * (src/main/signaling/signaling.js, docs/PROTOCOL.md section 3).
 *
 * ```
 *   controller                                   host
 *   probe ───────────────────────────────────────▶
 *         ◀─────────────────────────────── probe-ack
 *   hello {sid, ya} ─────────────────────────────▶          CPace share
 *         ◀─────────── challenge {ybs[], tags[]}            one share per password
 *   confirm {idx, tag, intro(enc)} ──────────────▶          key confirmation
 *         ◀──────────────── sec{waiting|accepted|rejected}
 *   sec{signal …} ◀────────────────────────────▶ sec{signal …}
 * ```
 *
 * Threading: all state lives on one serialized coroutine context (a
 * single-parallelism view of [dispatcher]); transport callbacks and public
 * calls are posted to it in order. [credentials], [canAccept] and the
 * `onStatus` callback of [connect] are invoked on that context: keep them
 * fast and thread-safe. Events are delivered through [events].
 *
 * @param credentials host passwords (as PRS); empty = incoming sessions disabled
 * @param canAccept returns a refusal reason (`busy`, `disabled`) or null
 */
class Signaling(
    transport: SignalingTransport,
    val myId: String,
    private val credentials: () -> List<Credential> = { emptyList() },
    private val canAccept: () -> String? = { null },
    val limiter: AuthLimiter = AuthLimiter(),
    val timeouts: Timeouts = Timeouts(),
    private val log: Logger = Logger.NONE,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val random: SecureRandom = Rng.secure,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val serial = dispatcher.limitedParallelism(1)
    private val scope = CoroutineScope(
        SupervisorJob() + serial + CoroutineExceptionHandler { _, e ->
            log.warn("[signaling] unexpected error: ${e.stackTraceToString()}")
        },
    )

    private val _events = MutableSharedFlow<SignalingEvent>(extraBufferCapacity = 256)

    /** Incoming sessions, session messages, closures and failed attempts, in order. */
    val events: SharedFlow<SignalingEvent> = _events.asSharedFlow()

    @Volatile
    var transport: SignalingTransport = transport
        private set

    private val transportListener = TransportListener { from, data -> scope.launch { onMessage(from, data) } }

    private class Candidate(val kind: String, val keys: SessionKeys, val yb: ByteArray, val tag: ByteArray)

    private class PendingHost(val from: String, val candidates: List<Candidate>) {
        var timer: Job? = null
    }

    private class PendingProbe(val peerId: String, val result: CompletableDeferred<ProbeResult>) {
        var timer: Job? = null
    }

    private enum class Stage { HELLO, CONFIRM }

    private inner class PendingCtrl(
        val sid: String,
        val peerId: String,
        val scalar: Scalar,
        val ya: ByteArray,
        val intro: JsonObject,
        val onStatus: (ConnectStatus) -> Unit,
        val result: CompletableDeferred<SignalingSession>,
    ) {
        var stage = Stage.HELLO
        var channel: SecureChannel? = null
        var grace: Job? = null
        var timer: Job? = null

        fun fail(code: String, retryIn: Long? = null, remoteVersion: Long? = null) {
            if (pendingCtrl[sid] !== this) return
            pendingCtrl.remove(sid)
            timer?.cancel()
            grace?.cancel()
            result.completeExceptionally(SignalingException(code, code, retryIn, remoteVersion))
        }

        fun succeed(session: SignalingSession) {
            pendingCtrl.remove(sid)
            timer?.cancel()
            grace?.cancel()
            result.complete(session)
        }

        fun status(s: ConnectStatus) {
            try {
                onStatus(s)
            } catch (e: Exception) {
                log.warn("[signaling] onStatus failed: ${e.message}")
            }
        }
    }

    // Only touched on the serialized context.
    private val pendingHost = HashMap<String, PendingHost>()
    private val pendingCtrl = HashMap<String, PendingCtrl>()
    private val probes = HashMap<String, PendingProbe>()

    // Read from any thread (session lookups), written on the serialized context.
    private val sessions = ConcurrentHashMap<String, SignalingSession>()

    @Volatile
    private var disposed = false

    init {
        transport.addListener(transportListener)
    }

    /** The established session [sid], if any. */
    fun session(sid: String): SignalingSession? = sessions[sid]

    /** Established sessions (both roles). */
    val activeSessions: List<SignalingSession> get() = sessions.values.toList()

    /** Switches to another transport, keeping established sessions (pending handshakes fail with `network`). */
    fun setTransport(newTransport: SignalingTransport) {
        scope.launch { abortPending("network") }
        transport.removeListener(transportListener)
        transport = newTransport
        newTransport.addListener(transportListener)
    }

    /**
     * Stops for good: pending handshakes and probes fail with `cancelled`,
     * established sessions end (without `bye`) and later calls fail.
     */
    fun dispose() {
        disposed = true
        transport.removeListener(transportListener)
        scope.launch {
            abortPending("cancelled")
            for (s in sessions.values) s.end("cancelled")
            sessions.clear()
        }.invokeOnCompletion { scope.cancel() }
    }

    private fun abortPending(code: String) {
        for (p in pendingCtrl.values.toList()) p.fail(code)
        for (p in pendingHost.values) p.timer?.cancel()
        pendingHost.clear()
        for (p in probes.values) {
            p.timer?.cancel()
            p.result.completeExceptionally(SignalingException(code))
        }
        probes.clear()
    }

    // ───────────────────────────── plumbing ─────────────────────────────

    private suspend fun emit(event: SignalingEvent) = _events.emit(event)

    private fun timer(ms: Long, action: suspend () -> Unit): Job = scope.launch {
        delay(ms)
        action()
    }

    /** Runs [block] on the serialized context; failures (and a cancelled scope) complete [out]. */
    private fun <T> post(out: CompletableDeferred<T>, block: suspend () -> Unit) {
        if (disposed) {
            out.completeExceptionally(SignalingException("cancelled"))
            return
        }
        val job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                out.completeExceptionally(e.toSignaling())
            }
        }
        job.invokeOnCompletion { cause -> if (cause != null) out.completeExceptionally(SignalingException("cancelled")) }
    }

    private fun transmit(to: String, data: JsonObject): Deferred<Unit> = try {
        transport.send(to, data)
    } catch (e: Exception) {
        CompletableDeferred<Unit>().apply { completeExceptionally(e.toSignaling()) }
    }

    /** Runs [block] on the serialized context when the transmission fails. */
    private fun Deferred<Unit>.onFailure(block: suspend (SignalingException) -> Unit) {
        invokeOnCompletion { cause -> if (cause != null) scope.launch { block(cause.toSignaling()) } }
    }

    private fun sendQuiet(to: String, data: JsonObject) {
        transmit(to, data).onFailure { e -> log.warn("[signaling] send ${data.str("t")} to $to failed: ${e.message}") }
    }

    private suspend fun onMessage(from: String, data: JsonObject) {
        if (!Protocol.isValidId(from)) return
        val t = data.str("t") ?: return
        val sid = data.str("sid") ?: return
        if (sid.length < 8 || sid.length > 64) return
        try {
            when (t) {
                "probe" -> sendQuiet(from, obj("t" to "probe-ack", "sid" to sid, "v" to Protocol.PROTOCOL_VERSION))
                "probe-ack" -> {
                    val p = probes[sid]
                    if (p != null && p.peerId == from) {
                        probes.remove(sid)
                        p.timer?.cancel()
                        p.result.complete(ProbeResult(data["v"].jsSafeIntegerOrNull()))
                    }
                }
                "hello" -> onHello(from, sid, data)
                "confirm" -> onConfirm(from, sid, data)
                "abort" -> {
                    val pending = pendingHost[sid]
                    if (pending != null && pending.from == from) {
                        pendingHost.remove(sid)
                        pending.timer?.cancel()
                        emit(SignalingEvent.AuthFailed(from, limiter.recentFailures))
                    }
                }
                "challenge" -> onChallenge(from, sid, data)
                "denied" -> {
                    val p = pendingCtrl[sid]
                    if (p != null && p.peerId == from) {
                        p.fail(
                            data["reason"].jsReason("rejected"),
                            retryIn = data["retryIn"].jsSafeIntegerOrNull(),
                            remoteVersion = data["v"].jsSafeIntegerOrNull(),
                        )
                    }
                }
                "sec" -> onSecure(from, sid, data)
                else -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("[signaling] error handling $t: ${e.stackTraceToString()}")
        }
    }

    // ───────────────────────────── host side ─────────────────────────────

    private fun deny(to: String, sid: String, reason: String, retryIn: Long? = null) {
        val msg = linkedMapOf<String, JsonElement>(
            "t" to JsonPrimitive("denied"),
            "sid" to JsonPrimitive(sid),
            "reason" to JsonPrimitive(reason),
            "v" to JsonPrimitive(Protocol.PROTOCOL_VERSION),
        )
        if (retryIn != null) msg["retryIn"] = JsonPrimitive(retryIn)
        sendQuiet(to, JsonObject(msg))
    }

    private fun onHello(from: String, sid: String, data: JsonObject) {
        if (pendingHost.containsKey(sid) || sessions.containsKey(sid)) return // duplicate
        if (data["v"].jsNumberOrNull() != Protocol.PROTOCOL_VERSION.toDouble()) return deny(from, sid, "version")
        val locked = limiter.lockedFor()
        if (locked > 0) return deny(from, sid, "locked", limiter.retryInSeconds(locked))
        val refusal = canAccept()
        if (refusal != null) return deny(from, sid, refusal)
        if (pendingHost.size >= 4) return deny(from, sid, "busy")
        val ya = unb64(data["ya"])
        if (ya == null || ya.size != 32) return deny(from, sid, "protocol")
        val creds = credentials()
        if (creds.isEmpty()) return deny(from, sid, "disabled")

        val ci = CPace.channelIdentifier(from, myId)
        val candidates = try {
            creds.take(4).map { cred ->
                val share = CPace.share(cred.prs, ci, sid, random)
                val k = CPace.secret(share.scalar, ya)
                val keys = CPace.deriveSessionKeys(sid, from, myId, ya, share.share, k)
                Candidate(cred.kind, keys, share.share, CPace.hostTag(keys))
            }
        } catch (e: CPaceException) {
            log.warn("[signaling] invalid hello from $from: ${e.message}")
            return deny(from, sid, "protocol")
        }
        // Every handshake counts as a failed attempt until it is confirmed: a
        // controller that guesses wrong learns it from our tags and may never
        // send a confirmation, so we cannot wait for one to count the attempt.
        limiter.recordFailure()
        val pending = PendingHost(from, candidates)
        pending.timer = timer(timeouts.pending) {
            if (pendingHost[sid] === pending) {
                pendingHost.remove(sid)
                emit(SignalingEvent.AuthFailed(from, limiter.recentFailures))
            }
        }
        pendingHost[sid] = pending
        sendQuiet(
            from,
            obj(
                "t" to "challenge",
                "sid" to sid,
                "ybs" to JsonArray(candidates.map { JsonPrimitive(B64u.encode(it.yb)) }),
                "tags" to JsonArray(candidates.map { JsonPrimitive(B64u.encode(it.tag)) }),
            ),
        )
    }

    private suspend fun onConfirm(from: String, sid: String, data: JsonObject) {
        val pending = pendingHost[sid] ?: return
        if (pending.from != from) return
        pendingHost.remove(sid)
        pending.timer?.cancel()
        val idx = data["idx"].jsSafeIntegerOrNull()
        val cand = if (idx != null && idx >= 0 && idx < pending.candidates.size) pending.candidates[idx.toInt()] else null
        val tag = unb64(data["tag"])
        if (cand == null || !constantTimeEquals(CPace.ctrlTag(cand.keys), tag)) {
            log.warn("[signaling] authentication failed from $from")
            deny(from, sid, "auth")
            emit(SignalingEvent.AuthFailed(from, limiter.recentFailures))
            return
        }
        val channel = SecureChannel.forHost(cand.keys, sid)
        val introValue = try {
            channel.open(data["intro"] as? JsonObject ?: JsonJs.EMPTY)
        } catch (e: ChannelException) {
            deny(from, sid, "protocol")
            return
        }
        limiter.recordSuccess()
        val intro = introValue as? JsonObject ?: JsonJs.EMPTY
        val session = SignalingSession(
            sid = sid,
            peerId = from,
            role = Role.HOST,
            credential = cand.kind,
            peerName = intro.str("name")?.take(64) ?: "",
            peerPlatform = intro.str("platform")?.take(16) ?: "",
            peerVersion = intro.str("appVersion")?.take(32) ?: "",
            intro = intro,
            info = null,
            startedAt = clock(),
            channel = channel,
        )
        sessions[sid] = session
        emit(SignalingEvent.Incoming(session))
    }

    /** Host: tells the controller its request awaits the local user's decision. Never throws. */
    suspend fun notifyWaiting(sid: String) {
        try {
            send(sid, obj("type" to "waiting"))
        } catch (_: SignalingException) {
            // ignored, like the desktop
        }
    }

    /**
     * Host: accepts the session. [info] is merged into `{type:'accepted', …}`:
     * `name`, `caps` ({control, files, clipboard, audio}), `platform`, `appVersion`, `kind`.
     */
    suspend fun accept(sid: String, info: JsonObject) {
        send(sid, JsonJs.merge(obj("type" to "accepted"), info))
    }

    /** Host: refuses the session (`{type:'rejected', reason}`) and forgets it. Never throws. */
    suspend fun reject(sid: String, reason: String = "rejected") {
        val out = CompletableDeferred<Unit>()
        post(out) {
            val s = sessions[sid]
            if (s == null) {
                out.complete(Unit)
                return@post
            }
            val sealed = s.channel.seal(obj("type" to "rejected", "reason" to reason))
            sessions.remove(sid)
            s.end(reason)
            val sent = transmit(s.peerId, secEnvelope(sid, sealed))
            sent.invokeOnCompletion { out.complete(Unit) }
        }
        try {
            out.await()
        } catch (_: SignalingException) {
            // ignored
        }
    }

    // ─────────────────────────── controller side ──────────────────────────

    /** Asks whether [peerId] is online; fails with `offline` after [timeoutMs] or `network`. */
    suspend fun probe(peerId: String, timeoutMs: Long = timeouts.probe): ProbeResult {
        val sid = Rng.b64u(12, random)
        val result = CompletableDeferred<ProbeResult>()
        post(result) {
            val p = PendingProbe(peerId, result)
            probes[sid] = p
            p.timer = timer(timeoutMs) {
                probes.remove(sid)
                result.completeExceptionally(SignalingException("offline"))
            }
            transmit(peerId, obj("t" to "probe", "sid" to sid, "v" to Protocol.PROTOCOL_VERSION)).onFailure { e ->
                p.timer?.cancel()
                if (probes[sid] === p) probes.remove(sid)
                result.completeExceptionally(SignalingException(if (e.code == "offline") "offline" else "network", e.message))
            }
        }
        try {
            return result.await()
        } catch (e: CancellationException) {
            scope.launch { probes.remove(sid)?.timer?.cancel() }
            throw e
        }
    }

    /**
     * Authenticates against [peerId] with [prs] (see [io.github.azukkia.pairdesk.core.crypto.Prs]).
     * Returns the established session once the host accepted, or throws a
     * [SignalingException] whose code is one of: auth, offline, network, busy,
     * locked, version, disabled, rejected (or the host's reason), timeout,
     * cancelled, protocol. Cancelling the calling coroutine abandons the attempt.
     *
     * @param intro merged into `{type:'intro', …}`: `name`, `platform`, `appVersion`, `kind`
     * @param onStatus progress, called on the signaling context
     */
    suspend fun connect(
        peerId: String,
        prs: ByteArray,
        intro: JsonObject = JsonJs.EMPTY,
        onStatus: (ConnectStatus) -> Unit = {},
    ): SignalingSession {
        val sid = Rng.b64u(18, random)
        val result = CompletableDeferred<SignalingSession>()
        post(result) {
            val ci = CPace.channelIdentifier(myId, peerId)
            val share = CPace.share(prs, ci, sid, random)
            val p = PendingCtrl(sid, peerId, share.scalar, share.share, intro, onStatus, result)
            pendingCtrl[sid] = p
            p.timer = timer(timeouts.hello) { p.fail("timeout") }
            p.status(ConnectStatus.AUTHENTICATING)
            transmit(peerId, obj("t" to "hello", "sid" to sid, "v" to Protocol.PROTOCOL_VERSION, "ya" to B64u.encode(share.share)))
                .onFailure { e -> p.fail(if (e.code == "offline") "offline" else "network") }
        }
        try {
            return result.await()
        } catch (e: CancellationException) {
            // Abandoned by the caller: fail the handshake, or close the session
            // if the host accepted in the meantime.
            scope.launch {
                val p = pendingCtrl[sid]
                if (p != null) p.fail("cancelled") else closeNow(sid, "cancelled")
            }
            throw e
        }
    }

    private fun onChallenge(from: String, sid: String, data: JsonObject) {
        val p = pendingCtrl[sid] ?: return
        if (p.peerId != from || p.stage != Stage.HELLO) return
        val ybs = (data["ybs"] as? JsonArray)?.take(4) ?: emptyList()
        val tags = data["tags"] as? JsonArray ?: JsonArray(emptyList())
        var matchIdx = -1
        var matchKeys: SessionKeys? = null
        for ((idx, ybText) in ybs.withIndex()) {
            val yb = unb64(ybText)
            val tag = unb64(tags.getOrNull(idx))
            if (yb == null || yb.size != 32 || tag == null) continue
            try {
                val k = CPace.secret(p.scalar, yb)
                val keys = CPace.deriveSessionKeys(sid, myId, p.peerId, p.ya, yb, k)
                if (constantTimeEquals(CPace.hostTag(keys), tag)) {
                    matchIdx = idx
                    matchKeys = keys
                    break
                }
            } catch (_: CPaceException) {
                // invalid share: ignore
            }
        }
        if (matchKeys == null) {
            // Wrong password — or a forged answer on a public relay: give the real
            // host a short grace period to answer before reporting the failure.
            if (p.grace == null) {
                p.grace = timer(timeouts.grace) {
                    sendQuiet(p.peerId, obj("t" to "abort", "sid" to p.sid))
                    p.fail("auth")
                }
            }
            return
        }
        p.grace?.cancel()
        p.grace = null
        p.stage = Stage.CONFIRM
        val channel = SecureChannel.forController(matchKeys, sid)
        p.channel = channel
        val tag = CPace.ctrlTag(matchKeys)
        val sealedIntro = channel.seal(JsonJs.merge(obj("type" to "intro"), p.intro))
        p.timer?.cancel()
        p.timer = timer(timeouts.approval) { p.fail("timeout") }
        p.status(ConnectStatus.AUTHENTICATED)
        transmit(
            from,
            obj("t" to "confirm", "sid" to sid, "idx" to matchIdx, "tag" to B64u.encode(tag), "intro" to sealedIntro.toJson()),
        ).onFailure { p.fail("network") }
    }

    // ───────────────────────────── both sides ─────────────────────────────

    private suspend fun onSecure(from: String, sid: String, data: JsonObject) {
        val pending = pendingCtrl[sid]
        if (pending != null && pending.peerId == from && pending.stage == Stage.CONFIRM) {
            val channel = pending.channel ?: return
            val msg = try {
                channel.open(data)
            } catch (_: ChannelException) {
                return
            } as? JsonObject ?: return
            when (msg.str("type")) {
                "waiting" -> pending.status(ConnectStatus.WAITING_APPROVAL)
                "rejected" -> pending.fail(msg["reason"].jsReason("rejected"))
                "accepted" -> {
                    val session = SignalingSession(
                        sid = sid,
                        peerId = from,
                        role = Role.CONTROLLER,
                        credential = null,
                        peerName = msg.str("name")?.take(64) ?: "",
                        peerPlatform = msg.str("platform")?.take(16) ?: "",
                        peerVersion = msg.str("appVersion")?.take(32) ?: "",
                        intro = null,
                        info = msg,
                        startedAt = clock(),
                        channel = channel,
                    )
                    sessions[sid] = session
                    pending.succeed(session)
                }
                else -> Unit
            }
            return
        }
        val session = sessions[sid] ?: return
        if (session.peerId != from) return
        val msg = try {
            session.channel.open(data)
        } catch (e: ChannelException) {
            log.warn("[signaling] dropped message for $sid: ${e.message}")
            return
        }
        if (msg !is JsonObject) return
        if (msg.str("type") == "bye") {
            val reason = msg["reason"].jsReason("remote")
            sessions.remove(sid)
            session.end(reason)
            emit(SignalingEvent.Closed(sid, reason))
            return
        }
        session.deliver(msg)
        emit(SignalingEvent.Message(sid, msg))
    }

    private fun secEnvelope(sid: String, sealed: Sealed): JsonObject =
        obj("t" to "sec", "sid" to sid, "n" to sealed.n, "ct" to sealed.ct)

    /**
     * Seals and queues [message] for session [sid]. Calls made from one thread
     * leave in order. The result completes once a relay accepted the message,
     * or fails with a [SignalingException] (`no-session`, `network`…).
     */
    fun sendAsync(sid: String, message: JsonObject): Deferred<Unit> {
        val out = CompletableDeferred<Unit>()
        post(out) {
            val s = sessions[sid] ?: throw SignalingException("no-session")
            val sealed = s.channel.seal(message)
            transmit(s.peerId, secEnvelope(sid, sealed)).invokeOnCompletion { cause ->
                if (cause == null) out.complete(Unit) else out.completeExceptionally(cause.toSignaling())
            }
        }
        return out
    }

    /** [sendAsync] and wait for the relay's acknowledgment. */
    suspend fun send(sid: String, message: JsonObject) = sendAsync(sid, message).await()

    /** Ends session [sid]: sends `{type:'bye', reason}` (best effort) and forgets it. */
    fun close(sid: String, reason: String = "closed") {
        scope.launch { closeNow(sid, reason) }
    }

    private fun closeNow(sid: String, reason: String) {
        val s = sessions[sid] ?: return
        val sealed = try {
            s.channel.seal(obj("type" to "bye", "reason" to reason))
        } catch (e: ChannelException) {
            null
        }
        sessions.remove(sid)
        s.end(reason)
        if (sealed != null) transmit(s.peerId, secEnvelope(sid, sealed)).onFailure { }
    }

    private fun unb64(v: JsonElement?): ByteArray? {
        val s = v.jsStringOrNull() ?: return null
        if (s.length > 128) return null
        return B64u.decode(s)
    }

    companion object {
        /** Builds a JSON object keeping the order of [pairs] (String, Number, Boolean, JsonElement or null). */
        fun obj(vararg pairs: Pair<String, Any?>): JsonObject {
            val map = LinkedHashMap<String, JsonElement>()
            for ((k, v) in pairs) {
                map[k] = when (v) {
                    null -> kotlinx.serialization.json.JsonNull
                    is JsonElement -> v
                    is String -> JsonPrimitive(v)
                    is Number -> JsonPrimitive(v)
                    is Boolean -> JsonPrimitive(v)
                    else -> throw IllegalArgumentException("unsupported JSON value for $k")
                }
            }
            return JsonObject(map)
        }
    }
}

internal fun Throwable.toSignaling(): SignalingException = when (this) {
    is SignalingException -> this
    else -> SignalingException("network", message)
}
