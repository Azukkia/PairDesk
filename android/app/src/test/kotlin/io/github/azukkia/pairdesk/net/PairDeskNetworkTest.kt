package io.github.azukkia.pairdesk.net

import io.github.azukkia.pairdesk.FakeSecretBox
import io.github.azukkia.pairdesk.core.json.str
import io.github.azukkia.pairdesk.core.signaling.Caps
import io.github.azukkia.pairdesk.core.signaling.Credential
import io.github.azukkia.pairdesk.core.signaling.SessionMessages
import io.github.azukkia.pairdesk.core.signaling.Signaling
import io.github.azukkia.pairdesk.core.signaling.Signaling.Companion.obj
import io.github.azukkia.pairdesk.core.signaling.SignalingEvent
import io.github.azukkia.pairdesk.core.signaling.SignalingException
import io.github.azukkia.pairdesk.core.signaling.SignalingSession
import io.github.azukkia.pairdesk.core.signaling.Timeouts
import io.github.azukkia.pairdesk.core.transport.MemoryBus
import io.github.azukkia.pairdesk.core.transport.MemoryTransport
import io.github.azukkia.pairdesk.core.transport.TransportState
import io.github.azukkia.pairdesk.data.MemoryKeyValueStore
import io.github.azukkia.pairdesk.data.SettingsStore
import io.github.azukkia.pairdesk.fakePrs
import io.github.azukkia.pairdesk.waitUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList

private const val DESK = "987654321"

/** The phone's network layer against desktop-like peers on an in-memory relay. */
class PairDeskNetworkTest {
    private val bus = MemoryBus()
    private val kv = MemoryKeyValueStore()
    private val settings = SettingsStore(kv, FakeSecretBox(), "Pixel 8")
    private val timeouts = Timeouts(probe = 400, hello = 3_000, approval = 5_000, pending = 3_000, grace = 200)
    private val transports = CopyOnWriteArrayList<MemoryTransport>()
    private val network = PairDeskNetwork(
        settings = settings,
        appVersion = "1.2.0",
        transportFactory = { _, _, _ -> bus.transport().also { transports += it } },
        derivePrs = { password, hostId -> fakePrs(password, hostId) },
        timeouts = timeouts,
        stopGraceMs = 100,
        consentTimeoutMs = 1_500,
        onlineWaitMs = 1_000,
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closers = mutableListOf<() -> Unit>()

    @AfterEach
    fun tearDown() {
        closers.reversed().forEach { runCatching(it) }
        network.dispose()
        scope.cancel()
        bus.shutdown()
    }

    /** A desktop on the relay: its own Signaling (host side when [creds] are given). */
    private fun desktop(
        id: String = DESK,
        creds: List<Credential> = emptyList(),
        onIncoming: suspend (Signaling, SignalingSession) -> Unit = { _, _ -> },
    ): Signaling {
        val signaling = Signaling(bus.transport().apply { start(id) }, id, { creds }, timeouts = timeouts)
        closers += { signaling.dispose() }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            signaling.events.collect { e -> if (e is SignalingEvent.Incoming) onIncoming(signaling, e.session) }
        }
        return signaling
    }

    private suspend fun online() {
        network.acquire("test")
        waitUntil(message = "online") { network.status.value.isOnline }
        waitUntil(message = "temporary PRS") { network.hostReady }
    }

    private fun phonePrs() = fakePrs(settings.tempPassword.value, network.myId)

    private fun intro(kind: String = SessionMessages.KIND_CONTROL) =
        SessionMessages.intro(name = "Bureau", appVersion = "1.2.0", kind = kind, platform = "win32")

    // ───────────────────────────── phone = host ─────────────────────────────

    @Test
    fun `incoming request waits for the user then is accepted`() = runBlocking {
        online()
        val desk = desktop()
        val statuses = CopyOnWriteArrayList<String>()
        val result = async { desk.connect(network.myId, phonePrs(), intro()) { statuses += it.wire } }

        waitUntil(message = "pending request") { network.incoming.value?.state == IncomingState.PENDING }
        val request = network.incoming.value!!
        assertEquals(DESK, request.peerId)
        assertEquals("Bureau", request.peerName)
        assertEquals("temp", request.credential)
        assertNotNull(request.deadline)
        waitUntil(message = "waiting status") { "waiting-approval" in statuses }

        assertTrue(network.acceptIncoming(request.sid, network.hostCaps(controlAvailable = true)))
        val session = withTimeout(5_000) { result.await() }
        assertEquals("android", session.peerPlatform)
        assertEquals("Pixel 8", session.peerName)
        assertEquals("1.2.0", session.peerVersion)
        assertEquals(Caps(control = true), Caps.from(session.info!!["caps"]))
        assertEquals(IncomingState.ACTIVE, network.incoming.value?.state)
        assertNull(network.incoming.value?.deadline)
        assertFalse(network.acceptIncoming(request.sid, Caps()), "only once")

        // Session messages flow both ways (what RtcSession relies on).
        desk.send(session.sid, obj("type" to "signal", "data" to obj("description" to obj("type" to "offer", "sdp" to "v=0"))))
        val phoneSession = network.signaling.session(session.sid)!!
        val received = withTimeout(5_000) { phoneSession.messages.receive() }
        assertEquals("offer", SessionMessages.descriptionOf(received)?.type)

        // The desktop ends it.
        val ended = async { withTimeout(5_000) { network.sessionEnded.first { it.sid == session.sid } } }
        desk.close(session.sid, "closed")
        assertEquals(SessionEnd(session.sid, "closed", byPeer = true), ended.await())
        assertNull(network.incoming.value)
    }

    @Test
    fun `allow control off is announced in caps`() {
        settings.setAllowControl(false)
        assertEquals(Caps(), network.hostCaps(controlAvailable = true))
        settings.setAllowControl(true)
        assertEquals(Caps(control = false), network.hostCaps(controlAvailable = false))
    }

    @Test
    fun `declined and timed out requests`() = runBlocking {
        online()
        val desk = desktop()
        val first = async { runCatching { desk.connect(network.myId, phonePrs(), intro()) } }
        waitUntil { network.incoming.value?.state == IncomingState.PENDING }
        network.declineIncoming(network.incoming.value!!.sid)
        assertEquals("declined", (first.await().exceptionOrNull() as SignalingException).code)
        assertNull(network.incoming.value)

        // Nobody answers: declined automatically after the consent delay.
        val second = async { runCatching { desk.connect(network.myId, phonePrs(), intro()) } }
        val error = withTimeout(8_000) { second.await() }.exceptionOrNull() as SignalingException
        assertEquals("timeout", error.code)
        assertNull(network.incoming.value)
    }

    @Test
    fun `a second partner is told the phone is busy`() = runBlocking {
        online()
        val desk = desktop()
        async { runCatching { desk.connect(network.myId, phonePrs(), intro()) } }
        waitUntil { network.incoming.value?.state == IncomingState.PENDING }
        val other = desktop(id = "555555555")
        val error = assertThrows<SignalingException> { runBlocking { other.connect(network.myId, phonePrs(), intro()) } }
        assertEquals("busy", error.code)
    }

    @Test
    fun `incoming sessions can be disabled`() = runBlocking {
        online()
        settings.setAllowIncoming(false)
        val error = assertThrows<SignalingException> { runBlocking { desktop().connect(network.myId, phonePrs(), intro()) } }
        assertEquals("disabled", error.code)
        assertNull(network.incoming.value)
    }

    @Test
    fun `camera sessions towards a phone are refused`() = runBlocking {
        online()
        val error = assertThrows<SignalingException> {
            runBlocking { desktop().connect(network.myId, phonePrs(), intro(SessionMessages.KIND_CAMERA)) }
        }
        assertEquals("unsupported", error.code)
    }

    @Test
    fun `wrong password and a new temporary password`() = runBlocking {
        online()
        val desk = desktop()
        val wrong = assertThrows<SignalingException> { runBlocking { desk.connect(network.myId, fakePrs("nope", network.myId), intro()) } }
        assertEquals("auth", wrong.code)

        val old = phonePrs()
        val password = network.regeneratePassword()
        assertEquals(password, network.hostPassword.value)
        waitUntil { network.hostReady }
        val stale = assertThrows<SignalingException> { runBlocking { desk.connect(network.myId, old, intro()) } }
        assertEquals("auth", stale.code)
    }

    @Test
    fun `permanent password`() = runBlocking {
        online()
        assertFalse(network.setPermanentPassword("short"))
        assertTrue(network.setPermanentPassword("correct horse"))
        assertTrue(settings.settings.value.hasPermanentPassword)
        val desk = desktop()
        val result = async { desk.connect(network.myId, fakePrs("correct horse", network.myId), intro()) }
        waitUntil { network.incoming.value?.state == IncomingState.PENDING }
        assertEquals("perm", network.incoming.value!!.credential)
        network.acceptIncoming(network.incoming.value!!.sid, Caps())
        withTimeout(5_000) { result.await() }

        assertTrue(network.setPermanentPassword(null))
        assertFalse(settings.settings.value.hasPermanentPassword)
    }

    // ───────────────────────────── phone = controller ─────────────────────────────

    private fun desktopHost(password: String = "abc234", accept: Boolean = true, echoKind: Boolean = true): Signaling =
        desktop(creds = listOf(Credential("temp", fakePrs(password, DESK)))) { host, s ->
            if (accept) {
                host.accept(
                    s.sid,
                    SessionMessages.accepted(
                        name = "Bureau",
                        caps = Caps(control = true, files = true, clipboard = true, audio = true),
                        appVersion = "1.2.0",
                        kind = if (echoKind) s.kind else null,
                        platform = "win32",
                    ),
                )
            } else {
                host.reject(s.sid, "declined")
            }
        }

    @Test
    fun `connects to a computer and remembers it`() = runBlocking {
        online()
        val host = desktopHost()
        val steps = CopyOnWriteArrayList<ConnectStep>()
        val result = network.connect(ConnectRequest("987 654 321", "abc234", remember = true, kind = SessionKind.CONTROL)) { steps += it }
        val outgoing = (result as ConnectResult.Success).outgoing
        assertEquals(DESK, outgoing.peerId)
        assertEquals("Bureau", outgoing.peerName)
        assertEquals(SessionKind.CONTROL, outgoing.kind)
        assertEquals(ConnectStep.SEARCHING, steps.first())
        assertTrue(ConnectStep.AUTHENTICATING in steps)
        assertEquals(listOf(outgoing), network.outgoingSessions.value)
        assertTrue(network.isSessionActive(outgoing.sid))

        val recent = settings.recents.value.single()
        assertEquals(DESK, recent.id)
        assertEquals("Bureau", recent.name)
        assertTrue(recent.hasPassword)
        assertArrayEquals(fakePrs("abc234", DESK), settings.savedPrs(DESK))

        // The intro announced this phone.
        val hostSession = host.session(outgoing.sid)!!
        assertEquals("android", hostSession.peerPlatform)
        assertEquals("Pixel 8", hostSession.peerName)
        assertEquals("control", hostSession.intro?.str("kind"))

        // Ending it tells the computer.
        val closed = async { withTimeout(5_000) { host.events.first { it is SignalingEvent.Closed } as SignalingEvent.Closed } }
        network.endOutgoing(outgoing.sid, "closed")
        assertEquals("closed", closed.await().reason)
        assertTrue(network.outgoingSessions.value.isEmpty())

        // Next time the remembered password is used.
        val again = network.connect(ConnectRequest(DESK, null, remember = false, kind = SessionKind.CONTROL))
        assertTrue(again is ConnectResult.Success)
        assertTrue(settings.recents.value.single().hasPassword, "kept")
        assertEquals(2, settings.recents.value.single().count)
    }

    @Test
    fun `connection errors`() = runBlocking {
        online()
        suspend fun fail(request: ConnectRequest): ConnectResult.Failure = network.connect(request) as ConnectResult.Failure

        assertEquals("invalid", fail(ConnectRequest("12345", "x", false, SessionKind.CONTROL)).code)
        assertEquals("self", fail(ConnectRequest(network.myId, "x", false, SessionKind.CONTROL)).code)
        assertEquals("offline", fail(ConnectRequest(DESK, "x", false, SessionKind.CONTROL)).code)

        desktopHost()
        assertEquals("need-password", fail(ConnectRequest(DESK, "  ", false, SessionKind.CONTROL)).code)
        assertEquals("auth", fail(ConnectRequest(DESK, "wrong", true, SessionKind.CONTROL)).code)
        assertTrue(settings.recents.value.isEmpty(), "failures are not remembered")
    }

    @Test
    fun `a wrong remembered password is forgotten`() = runBlocking {
        online()
        desktopHost(password = "new-one")
        settings.touchRecent(DESK, "Bureau", io.github.azukkia.pairdesk.data.PrsUpdate.Set(fakePrs("old-one", DESK)))
        val result = network.connect(ConnectRequest(DESK, null, false, SessionKind.CONTROL)) as ConnectResult.Failure
        assertEquals("auth", result.code)
        assertFalse(settings.recents.value.single().hasPassword)
    }

    @Test
    fun `declined by the computer`() = runBlocking {
        online()
        desktopHost(accept = false)
        val result = network.connect(ConnectRequest(DESK, "abc234", false, SessionKind.CONTROL)) as ConnectResult.Failure
        assertEquals("declined", result.code)
    }

    @Test
    fun `camera sessions need a 1_2 computer`() = runBlocking {
        online()
        desktopHost(echoKind = true)
        val ok = network.connect(ConnectRequest(DESK, "abc234", false, SessionKind.CAMERA))
        assertEquals(SessionKind.CAMERA, (ok as ConnectResult.Success).outgoing.kind)
        assertEquals("camera", ok.outgoing.session.kind)
        network.endOutgoing(ok.outgoing.sid)
    }

    @Test
    fun `camera sessions refused by an older computer`() = runBlocking {
        online()
        val host = desktopHost(echoKind = false)
        val closed = async { withTimeout(5_000) { host.events.first { it is SignalingEvent.Closed } as SignalingEvent.Closed } }
        val result = network.connect(ConnectRequest(DESK, "abc234", false, SessionKind.CAMERA)) as ConnectResult.Failure
        assertEquals("camera-unsupported", result.code)
        assertEquals("unsupported", closed.await().reason)
        assertTrue(network.outgoingSessions.value.isEmpty())
    }

    @Test
    fun `version mismatch is detected by the probe`() = runBlocking {
        online()
        val raw = bus.transport().apply { start(DESK) }
        raw.addListener { from, data ->
            if (data.str("t") == "probe") raw.send(from, obj("t" to "probe-ack", "sid" to data.str("sid"), "v" to 2))
        }
        val result = network.connect(ConnectRequest(DESK, "abc234", false, SessionKind.CONTROL)) as ConnectResult.Failure
        assertEquals("version", result.code)
    }

    // ───────────────────────────── lifecycle ─────────────────────────────

    @Test
    fun `runs while held, stops after the grace delay`() = runBlocking {
        assertFalse(network.isRunning)
        assertEquals(TransportState.OFFLINE, network.status.value.state)
        network.acquire("foreground")
        network.acquire("session:x")
        waitUntil { network.status.value.isOnline }
        network.release("foreground")
        Thread.sleep(300)
        assertTrue(network.isRunning, "a session still holds it")
        network.release("session:x")
        waitUntil(message = "stopped") { !network.isRunning && network.status.value.state == TransportState.OFFLINE }
        network.acquire("foreground")
        waitUntil(message = "restarted") { network.status.value.isOnline }
    }

    @Test
    fun `an outgoing session keeps the network running`() = runBlocking {
        online()
        desktopHost()
        val result = network.connect(ConnectRequest(DESK, "abc234", false, SessionKind.CONTROL)) as ConnectResult.Success
        network.release("test")
        Thread.sleep(300)
        assertTrue(network.isRunning)
        network.endOutgoing(result.outgoing.sid)
        waitUntil(message = "stopped") { !network.isRunning }
    }

    @Test
    fun `ice servers are the STUN servers of the configuration`() {
        val urls = network.iceServers().flatMap { it.urls }
        assertTrue("stun:stun.l.google.com:19302" in urls)
        assertTrue("stun:stun.cloudflare.com:3478" in urls)
    }

    private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.first(predicate: (T) -> Boolean): T =
        kotlinx.coroutines.flow.first(this, predicate)

    @Suppress("unused")
    private fun JsonObject.kind(): String? = str("kind")
}
