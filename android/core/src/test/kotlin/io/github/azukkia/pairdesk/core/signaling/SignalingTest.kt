package io.github.azukkia.pairdesk.core.signaling

import io.github.azukkia.pairdesk.core.crypto.B64u
import io.github.azukkia.pairdesk.core.crypto.CPace
import io.github.azukkia.pairdesk.core.crypto.Rng
import io.github.azukkia.pairdesk.core.json.JsonJs
import io.github.azukkia.pairdesk.core.json.str
import io.github.azukkia.pairdesk.core.signaling.Signaling.Companion.obj
import io.github.azukkia.pairdesk.core.transport.MemoryBus
import io.github.azukkia.pairdesk.core.transport.MemoryTransport
import io.github.azukkia.pairdesk.core.waitUntil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

private const val HOST = "123456789"
private const val CTRL = "987654321"
private const val ATTACKER = "555555555"

/** Kotlin controller ↔ Kotlin host over an in-memory relay (same scenarios as test/unit/signaling.test.js). */
class SignalingTest {
    private val closers = mutableListOf<() -> Unit>()

    @AfterEach
    fun tearDown() {
        closers.reversed().forEach { runCatching(it) }
    }

    private inner class Setup(
        hostCreds: List<Credential>? = null,
        canAccept: () -> String? = { null },
        val limiter: AuthLimiter = AuthLimiter(),
        timeouts: Timeouts = Timeouts(),
        val autoAccept: Boolean = true,
        val onIncoming: suspend (Signaling, SignalingSession) -> Unit = { host, s ->
            host.accept(s.sid, obj("name" to "Host PC", "caps" to Caps(control = true).toJson(), "platform" to "linux", "appVersion" to "1.2.0"))
        },
    ) {
        val bus = MemoryBus()
        val ht: MemoryTransport = bus.transport().apply { start(HOST) }
        val ct: MemoryTransport = bus.transport().apply { start(CTRL) }
        val temp: ByteArray = Rng.bytes(32)
        val perm: ByteArray = Rng.bytes(32)
        val creds = hostCreds ?: listOf(Credential("temp", temp), Credential("perm", perm))
        val host = Signaling(ht, HOST, { creds }, canAccept, limiter, timeouts)
        val ctrl = Signaling(ct, CTRL, timeouts = timeouts)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val incoming: MutableList<SignalingSession> = CopyOnWriteArrayList()
        val hostEvents: MutableList<SignalingEvent> = CopyOnWriteArrayList()
        val ctrlEvents: MutableList<SignalingEvent> = CopyOnWriteArrayList()

        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                host.events.collect { e ->
                    hostEvents += e
                    if (e is SignalingEvent.Incoming) {
                        incoming += e.session
                        if (autoAccept) onIncoming(host, e.session)
                    }
                }
            }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { ctrl.events.collect { ctrlEvents += it } }
            closers += {
                scope.cancel()
                host.dispose()
                ctrl.dispose()
                bus.shutdown()
            }
        }

        inline fun <reified T : SignalingEvent> hostEventsOf(): List<T> = hostEvents.filterIsInstance<T>()

        /** A raw transport on [id], recording what it receives. */
        fun raw(id: String): Pair<MemoryTransport, MutableList<JsonObject>> {
            val t = bus.transport().apply { start(id) }
            val got = Collections.synchronizedList(mutableListOf<JsonObject>())
            t.addListener { _, data -> got += data }
            return t to got
        }
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    private suspend fun expectCode(code: String, block: suspend () -> Unit): SignalingException {
        val e = try {
            block()
            null
        } catch (e: SignalingException) {
            e
        }
        assertNotNull(e, "expected a SignalingException $code")
        assertEquals(code, e!!.code)
        return e
    }

    @Test
    fun `probe answers when the partner is online, fails otherwise`() = test {
        val s = Setup()
        assertEquals(1L, s.ctrl.probe(HOST).version)
        expectCode("offline") { s.ctrl.probe(ATTACKER) }
        // A peer that never answers: offline after the probe timeout.
        s.raw("444444444")
        expectCode("offline") { s.ctrl.probe("444444444", timeoutMs = 200) }
    }

    @Test
    fun `connects with the temporary password and exchanges encrypted messages`() = test {
        val s = Setup()
        val statuses = CopyOnWriteArrayList<ConnectStatus>()
        val session = s.ctrl.connect(HOST, s.temp, SessionMessages.intro("Laptop", "1.2.0")) { statuses += it }
        assertEquals(Role.CONTROLLER, session.role)
        assertEquals("Host PC", session.peerName)
        assertEquals("linux", session.peerPlatform)
        assertEquals("1.2.0", session.peerVersion)
        assertEquals(Caps(control = true), Caps.from(session.info!!["caps"]))
        assertNull(session.kind) // the host did not echo a kind
        assertEquals(listOf(ConnectStatus.AUTHENTICATING, ConnectStatus.AUTHENTICATED), statuses.toList())
        assertEquals(1, s.incoming.size)
        val hostSession = s.incoming[0]
        assertEquals(session.sid, hostSession.sid)
        assertEquals("temp", hostSession.credential)
        assertEquals("Laptop", hostSession.peerName)
        assertEquals("android", hostSession.peerPlatform)
        assertEquals("1.2.0", hostSession.peerVersion)
        assertEquals("control", hostSession.kind)
        assertEquals(Role.HOST, hostSession.role)
        assertEquals(session, s.ctrl.session(session.sid))
        assertEquals(0, s.limiter.recentFailures)

        // controller → host
        s.ctrl.send(session.sid, SessionMessages.signal(SessionDescription("answer", "v=0 secret-sdp")))
        val got = withTimeout(5000) { hostSession.messages.receive() }
        assertEquals(SessionDescription("answer", "v=0 secret-sdp"), SessionMessages.descriptionOf(got))
        waitUntil { s.hostEventsOf<SignalingEvent.Message>().isNotEmpty() }
        assertEquals(session.sid, s.hostEventsOf<SignalingEvent.Message>()[0].sid)
        // host → controller, several in order
        for (i in 0 until 5) {
            s.host.send(session.sid, SessionMessages.signal(IceCandidate("candidate:$i 1 udp 2122260223 10.0.0.2 5000$i typ host", "0", 0)))
        }
        for (i in 0 until 5) {
            val c = SessionMessages.candidateOf(withTimeout(5000) { session.messages.receive() })
            assertEquals(IceCandidate("candidate:$i 1 udp 2122260223 10.0.0.2 5000$i typ host", "0", 0), c)
        }
        // Nothing sensitive travels in clear on the relay.
        val wire = s.bus.log.joinToString("\n") { it.third.toString() }
        assertFalse(wire.contains("secret-sdp"))
        assertFalse(wire.contains("Laptop"))
        assertFalse(wire.contains("Host PC"))
        assertFalse(wire.contains("candidate:"))

        s.ctrl.close(session.sid, "user")
        waitUntil { s.hostEventsOf<SignalingEvent.Closed>().isNotEmpty() }
        assertEquals(SignalingEvent.Closed(session.sid, "user"), s.hostEventsOf<SignalingEvent.Closed>()[0])
        assertEquals("user", hostSession.closeReason)
        assertTrue(hostSession.messages.receiveCatching().isClosed)
        assertEquals("user", session.closeReason)
        assertNull(s.host.session(session.sid))
        assertNull(s.ctrl.session(session.sid))
        expectCode("no-session") { s.ctrl.send(session.sid, obj("type" to "chat")) }
    }

    @Test
    fun `connects with the permanent password`() = test {
        val s = Setup()
        s.ctrl.connect(HOST, s.perm)
        assertEquals("perm", s.incoming[0].credential)
    }

    @Test
    fun `camera sessions announce and echo their kind`() = test {
        val s = Setup(onIncoming = { host, session ->
            host.accept(session.sid, SessionMessages.accepted("PC", Caps(), "1.2.0", kind = session.kind, platform = "win32"))
        })
        val session = s.ctrl.connect(HOST, s.temp, SessionMessages.intro("Pixel", "1.2.0", kind = SessionMessages.KIND_CAMERA))
        assertEquals("camera", s.incoming[0].kind)
        assertEquals("camera", session.kind)
        assertEquals("win32", session.peerPlatform)
    }

    @Test
    fun `wrong password fails with auth after the grace period and is counted`() = test {
        val limiter = AuthLimiter()
        val s = Setup(limiter = limiter, timeouts = Timeouts(grace = 300))
        val start = System.nanoTime()
        expectCode("auth") { s.ctrl.connect(HOST, Rng.bytes(32)) }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMs >= 290, "failed after $elapsedMs ms, before the grace period")
        // The controller detected the mismatch itself and aborted: the attempt
        // still counts on the host side, so guessing is rate limited.
        waitUntil { s.hostEventsOf<SignalingEvent.AuthFailed>().isNotEmpty() }
        assertEquals(SignalingEvent.AuthFailed(CTRL, 1), s.hostEventsOf<SignalingEvent.AuthFailed>()[0])
        assertEquals(1, limiter.recentFailures)
        assertTrue(s.bus.log.any { it.third.str("t") == "abort" })
        assertTrue(s.incoming.isEmpty())
    }

    @Test
    fun `a forged confirmation is counted as a failure and locks out brute force`() = test {
        var now = 1_000_000L
        val limiter = AuthLimiter(threshold = 3, baseLockMs = 60_000, now = { now })
        val s = Setup(limiter = limiter)
        val (attacker, replies) = s.raw(ATTACKER)
        for (i in 0 until 3) {
            val sid = "forged-session-$i"
            val share = CPace.share(Rng.bytes(32), CPace.channelIdentifier(ATTACKER, HOST), sid)
            attacker.send(HOST, obj("t" to "hello", "sid" to sid, "v" to 1, "ya" to B64u.encode(share.share))).await()
            waitUntil { replies.size >= 2 * i + 1 }
            assertEquals("challenge", replies[2 * i].str("t"))
            assertEquals(2, (replies[2 * i]["ybs"] as kotlinx.serialization.json.JsonArray).size)
            attacker.send(HOST, obj("t" to "confirm", "sid" to sid, "idx" to 0, "tag" to B64u.encode(ByteArray(32)), "intro" to obj())).await()
            waitUntil { replies.size >= 2 * i + 2 }
        }
        assertEquals(3, limiter.recentFailures)
        assertTrue(limiter.lockedFor() > 0)
        assertEquals(3, replies.count { it.str("t") == "denied" && it.str("reason") == "auth" })
        waitUntil { s.hostEventsOf<SignalingEvent.AuthFailed>().size == 3 }
        // Even the right password is refused while locked.
        val e = expectCode("locked") { s.ctrl.connect(HOST, s.temp) }
        assertEquals(60L, e.retryIn)
        assertEquals(1L, e.remoteVersion)
        now += 61_000
        s.ctrl.connect(HOST, s.temp)
        assertEquals(0, limiter.recentFailures)
    }

    @Test
    fun `busy and disabled hosts refuse before authentication`() = test {
        val busy = Setup(canAccept = { "busy" })
        expectCode("busy") { busy.ctrl.connect(HOST, busy.temp) }
        assertEquals(0, busy.limiter.recentFailures)
        val disabled = Setup(canAccept = { "disabled" })
        expectCode("disabled") { disabled.ctrl.connect(HOST, disabled.temp) }
        val none = Setup(hostCreds = emptyList())
        expectCode("disabled") { none.ctrl.connect(HOST, Rng.bytes(32)) }
    }

    @Test
    fun `at most four handshakes are pending on a host`() = test {
        val s = Setup(timeouts = Timeouts(pending = 60_000))
        val (attacker, replies) = s.raw(ATTACKER)
        for (i in 0 until 5) {
            val sid = "pending-session-$i"
            val share = CPace.share(Rng.bytes(32), CPace.channelIdentifier(ATTACKER, HOST), sid)
            attacker.send(HOST, obj("t" to "hello", "sid" to sid, "v" to 1, "ya" to B64u.encode(share.share))).await()
            waitUntil { replies.size >= i + 1 }
        }
        assertEquals(listOf("challenge", "challenge", "challenge", "challenge", "denied"), replies.map { it.str("t") })
        assertEquals("busy", replies[4].str("reason"))
    }

    @Test
    fun `the host can ask its user, then reject`() = test {
        val s = Setup(onIncoming = { host, session ->
            host.notifyWaiting(session.sid)
            delay(20)
            host.reject(session.sid, "denied-by-user")
        })
        val statuses = CopyOnWriteArrayList<ConnectStatus>()
        expectCode("denied-by-user") { s.ctrl.connect(HOST, s.temp) { statuses += it } }
        assertTrue(ConnectStatus.WAITING_APPROVAL in statuses)
        waitUntil { s.host.session(s.incoming[0].sid) == null }
        assertEquals("denied-by-user", s.incoming[0].closeReason)
    }

    @Test
    fun `waiting for approval, then accepted`() = test {
        val s = Setup(onIncoming = { host, session ->
            host.notifyWaiting(session.sid)
            delay(50)
            host.accept(session.sid, SessionMessages.accepted("PC", Caps(files = true), "1.2.0", platform = "darwin"))
        })
        val statuses = CopyOnWriteArrayList<ConnectStatus>()
        val session = s.ctrl.connect(HOST, s.temp) { statuses += it }
        assertEquals(
            listOf(ConnectStatus.AUTHENTICATING, ConnectStatus.AUTHENTICATED, ConnectStatus.WAITING_APPROVAL),
            statuses.toList(),
        )
        assertEquals(Caps(files = true), Caps.from(session.info!!["caps"]))
    }

    @Test
    fun `an impostor answering on a public relay cannot hijack the session`() = test {
        val s = Setup()
        // A second listener on the host ID answering every hello with garbage, before the real host.
        val impostor = s.bus.transport().apply { start(HOST) }
        impostor.addListener { from, data ->
            if (data.str("t") == "hello") {
                impostor.send(
                    from,
                    obj(
                        "t" to "challenge",
                        "sid" to data.str("sid"),
                        "ybs" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(Rng.b64u(32)))),
                        "tags" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(Rng.b64u(32)))),
                    ),
                )
            }
        }
        val session = s.ctrl.connect(HOST, s.temp)
        assertNotNull(session.sid)
        assertEquals(1, s.incoming.size)
    }

    @Test
    fun `connect can be cancelled and the signaling stays usable`() = test {
        val s = Setup(autoAccept = false)
        val attempt = async { s.ctrl.connect(HOST, s.temp) }
        waitUntil { s.incoming.isNotEmpty() } // authenticated, waiting for the host's decision
        attempt.cancel()
        val cancelled = try {
            attempt.await()
            false
        } catch (_: CancellationException) {
            true
        }
        assertTrue(cancelled)
        // The host accepts too late: the controller ignores it.
        s.host.accept(s.incoming[0].sid, obj("name" to "late"))
        delay(100)
        assertNull(s.ctrl.session(s.incoming[0].sid))
        // A new attempt works.
        val again = async { s.ctrl.connect(HOST, s.temp) }
        waitUntil { s.incoming.size == 2 }
        s.host.accept(s.incoming[1].sid, obj("name" to "PC"))
        assertEquals("PC", again.await().peerName)
    }

    @Test
    fun `version mismatch is reported both ways`() = test {
        val s = Setup()
        val (t, replies) = s.raw("444444444")
        t.send(HOST, obj("t" to "hello", "sid" to "some-session-x", "v" to 99, "ya" to B64u.encode(s.temp))).await()
        waitUntil { replies.isNotEmpty() }
        assertEquals(obj("t" to "denied", "sid" to "some-session-x", "reason" to "version", "v" to 1), replies[0])
        // A controller talking to a newer host gets `version` and the host's version.
        val (fakeHost, _) = s.raw("222222222")
        fakeHost.addListener { from, data ->
            if (data.str("t") == "hello") fakeHost.send(from, obj("t" to "denied", "sid" to data.str("sid"), "reason" to "version", "v" to 2))
        }
        val e = expectCode("version") { s.ctrl.connect("222222222", s.temp) }
        assertEquals(2L, e.remoteVersion)
    }

    @Test
    fun `invalid hellos are refused with protocol`() = test {
        val s = Setup()
        val (t, replies) = s.raw("444444444")
        t.send(HOST, obj("t" to "hello", "sid" to "bad-share-1", "v" to 1, "ya" to B64u.encode(ByteArray(31)))).await()
        t.send(HOST, obj("t" to "hello", "sid" to "bad-share-2", "v" to 1, "ya" to B64u.encode(ByteArray(32)))).await() // identity
        t.send(HOST, obj("t" to "hello", "sid" to "bad-share-3", "v" to 1)).await()
        // Ignored: invalid sid, unknown type, invalid sender.
        t.send(HOST, obj("t" to "hello", "sid" to "short", "v" to 1, "ya" to B64u.encode(s.temp))).await()
        t.send(HOST, obj("t" to "nonsense", "sid" to "valid-session-id")).await()
        waitUntil { replies.size >= 3 }
        delay(100)
        assertEquals(3, replies.size)
        assertTrue(replies.all { it.str("t") == "denied" && it.str("reason") == "protocol" })
        s.ht.receive("12345", obj("t" to "probe", "sid" to "valid-session-id"))
        delay(50)
        assertEquals(0, s.limiter.recentFailures)
    }

    @Test
    fun `a host that never answers makes connect time out`() = test {
        val s = Setup(timeouts = Timeouts(hello = 300))
        s.raw("222222222")
        expectCode("timeout") { s.ctrl.connect("222222222", s.temp) }
        expectCode("offline") { s.ctrl.connect("333333333", s.temp) }
    }

    @Test
    fun `an unconfirmed handshake expires on the host and counts as a failure`() = test {
        val s = Setup(timeouts = Timeouts(pending = 200))
        val (t, replies) = s.raw(ATTACKER)
        val share = CPace.share(Rng.bytes(32), CPace.channelIdentifier(ATTACKER, HOST), "never-confirmed")
        t.send(HOST, obj("t" to "hello", "sid" to "never-confirmed", "v" to 1, "ya" to B64u.encode(share.share))).await()
        waitUntil { replies.isNotEmpty() }
        waitUntil { s.hostEventsOf<SignalingEvent.AuthFailed>().isNotEmpty() }
        assertEquals(1, s.limiter.recentFailures)
        // A late confirm is ignored.
        t.send(HOST, obj("t" to "confirm", "sid" to "never-confirmed", "idx" to 0, "tag" to "AAAA")).await()
        delay(100)
        assertEquals(1, replies.size)
    }

    @Test
    fun `replayed or misrouted session messages are dropped`() = test {
        val s = Setup()
        val session = s.ctrl.connect(HOST, s.temp)
        val hostSession = s.incoming[0]
        s.ctrl.send(session.sid, obj("type" to "chat", "text" to "one"))
        assertEquals("one", withTimeout(5000) { hostSession.messages.receive() }.str("text"))
        val sec = s.bus.log.last { it.first == CTRL && it.third.str("t") == "sec" }.third
        // Same message again (a second relay, an attacker): dropped.
        s.ht.receive(CTRL, sec)
        // Same message claiming another sender: dropped.
        s.ht.receive(ATTACKER, sec)
        // Tampered ciphertext: dropped.
        val tampered = JsonJs.merge(sec, obj("n" to (sec["n"]!!.jsonPrimitive.int + 1)))
        s.ht.receive(CTRL, tampered)
        s.ctrl.send(session.sid, obj("type" to "chat", "text" to "two"))
        assertEquals("two", withTimeout(5000) { hostSession.messages.receive() }.str("text"))
        delay(50)
        assertTrue(hostSession.messages.tryReceive().isFailure)
        assertEquals(2, s.hostEventsOf<SignalingEvent.Message>().size)
    }

    @Test
    fun `an abort from the controller is reported on the host`() = test {
        val s = Setup()
        val (t, replies) = s.raw(ATTACKER)
        val share = CPace.share(Rng.bytes(32), CPace.channelIdentifier(ATTACKER, HOST), "aborted-session")
        t.send(HOST, obj("t" to "hello", "sid" to "aborted-session", "v" to 1, "ya" to B64u.encode(share.share))).await()
        waitUntil { replies.isNotEmpty() }
        t.send(HOST, obj("t" to "abort", "sid" to "aborted-session")).await()
        waitUntil { s.hostEventsOf<SignalingEvent.AuthFailed>().isNotEmpty() }
        assertEquals(SignalingEvent.AuthFailed(ATTACKER, 1), s.hostEventsOf<SignalingEvent.AuthFailed>()[0])
    }

    @Test
    fun `dispose cancels pending handshakes and ends sessions`() = test {
        val s = Setup(autoAccept = false)
        val session = run {
            val attempt = async { s.ctrl.connect(HOST, s.temp) }
            waitUntil { s.incoming.isNotEmpty() }
            s.host.accept(s.incoming[0].sid, obj("name" to "PC"))
            attempt.await()
        }
        val pending = async {
            try {
                s.ctrl.connect(HOST, s.temp)
                null
            } catch (e: SignalingException) {
                e.code
            }
        }
        waitUntil { s.incoming.size == 2 }
        s.ctrl.dispose()
        assertEquals("cancelled", pending.await())
        waitUntil { !session.isActive }
        assertEquals("cancelled", session.closeReason)
        expectCode("cancelled") { s.ctrl.send(session.sid, obj("type" to "chat")) }
    }

    @Test
    fun `transport failures are reported as network errors`() = test {
        val s = Setup()
        val failing = object : io.github.azukkia.pairdesk.core.transport.SignalingTransport by s.ct {
            override fun send(to: String, data: JsonObject) =
                kotlinx.coroutines.CompletableDeferred<Unit>().apply { completeExceptionally(SignalingException("network")) }
        }
        val signaling = Signaling(failing, "111111111")
        closers += { signaling.dispose() }
        expectCode("network") { signaling.connect(HOST, s.temp) }
        expectCode("network") { signaling.probe(HOST) }
    }

    @Test
    fun `parallel connections to one host are independent`() = test {
        val s = Setup()
        val others = (0 until 3).map { i ->
            val id = "30000000$i"
            val t = s.bus.transport().apply { start(id) }
            Signaling(t, id).also { sig -> closers += { sig.dispose() } }
        }
        val sessions = others.map { sig -> async { sig.connect(HOST, if (sig.myId.endsWith("1")) s.perm else s.temp) } }.map { it.await() }
        assertEquals(3, sessions.map { it.sid }.toSet().size)
        assertEquals(3, s.incoming.size)
        assertEquals(setOf("300000000", "300000001", "300000002"), s.incoming.map { it.peerId }.toSet())
        assertEquals("perm", s.incoming.first { it.peerId == "300000001" }.credential)
    }
}
