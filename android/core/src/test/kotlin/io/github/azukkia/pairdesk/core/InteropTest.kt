package io.github.azukkia.pairdesk.core

import io.github.azukkia.pairdesk.core.crypto.Prs
import io.github.azukkia.pairdesk.core.json.JsonJs
import io.github.azukkia.pairdesk.core.json.str
import io.github.azukkia.pairdesk.core.signaling.AuthLimiter
import io.github.azukkia.pairdesk.core.signaling.Caps
import io.github.azukkia.pairdesk.core.signaling.ConnectStatus
import io.github.azukkia.pairdesk.core.signaling.Credential
import io.github.azukkia.pairdesk.core.signaling.SessionDescription
import io.github.azukkia.pairdesk.core.signaling.SessionMessages
import io.github.azukkia.pairdesk.core.signaling.Signaling
import io.github.azukkia.pairdesk.core.signaling.Signaling.Companion.obj
import io.github.azukkia.pairdesk.core.signaling.SignalingEvent
import io.github.azukkia.pairdesk.core.signaling.SignalingException
import io.github.azukkia.pairdesk.core.signaling.SignalingSession
import io.github.azukkia.pairdesk.core.transport.Logger
import io.github.azukkia.pairdesk.core.transport.MqttTransport
import io.github.azukkia.pairdesk.core.transport.SignalingTransport
import io.github.azukkia.pairdesk.core.transport.TransportState
import io.github.azukkia.pairdesk.core.transport.WsTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

// Must match scripts/android-interop-peer.mjs.
private const val OFFER_SDP = "v=0\r\no=- 4611731400430051336 2 IN IP4 127.0.0.1\r\ns=PairDesk interop é✓😀\r\n"
private const val ANSWER_SDP = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=Android answer ü€😀\r\n"

/**
 * Real interoperability with the desktop implementation: the desktop's
 * signaling code (src/main/signaling, run by Node through
 * scripts/android-interop-peer.mjs) talks to this Kotlin implementation over
 * a local relay, with the Kotlin MQTT transport (two aedes brokers over
 * WebSocket, like the public relays) and the private-server WebSocket
 * transport (server/src/server.js). Skipped when Node or the repository's
 * node_modules are not available, or with -Ppairdesk.skipInterop=true.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class InteropTest {
    private val repo = TestFiles.repoRoot
    private val script = File(repo, "scripts/android-interop-peer.mjs")
    private val closers = mutableListOf<() -> Unit>()
    private val log = object : Logger {
        override fun info(message: String) = println("[kotlin] $message")
        override fun warn(message: String) = println("[kotlin] WARN $message")
    }

    @BeforeEach
    fun requirements() {
        assumeTrue(System.getProperty("pairdesk.skipInterop") != "true", "interop tests disabled")
        assumeTrue(script.isFile, "missing $script")
        assumeTrue(File(repo, "node_modules/aedes").isDirectory && File(repo, "node_modules/mqtt").isDirectory, "run npm install at the repository root")
        assumeTrue(nodeAvailable, "node is not installed")
    }

    @AfterEach
    fun tearDown() {
        closers.reversed().forEach { runCatching(it) }
    }

    /** The Node peer: JSON lines on stdout, diagnostics on stderr (printed when a test fails). */
    private inner class NodePeer(vararg args: String) {
        val process: Process
        private val lines = LinkedBlockingQueue<String>()
        private val stderr = File.createTempFile("pairdesk-interop", ".log")
        private val stdin: BufferedWriter

        init {
            process = ProcessBuilder(listOf("node", script.path) + args)
                .directory(repo)
                .redirectError(stderr)
                .start()
            stdin = process.outputStream.bufferedWriter()
            Thread {
                process.inputStream.bufferedReader().forEachLine { lines.put(it) }
            }.apply { isDaemon = true }.start()
            closers += {
                process.destroy()
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }

        fun next(timeoutMs: Long = 30_000): JsonObject {
            val line = lines.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: error("no output from the Node peer:\n${stderr.readText()}")
            return JsonJs.parse(line) as JsonObject
        }

        fun write(obj: JsonObject) = writeLine(JsonJs.stringify(obj))

        fun writeLine(line: String) {
            stdin.write(line)
            stdin.newLine()
            stdin.flush()
        }

        fun closeInput() = runCatching { stdin.close() }

        /** The final `{ok}` line and the exit code. */
        fun finish(): JsonObject {
            val result = next(60_000)
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Node peer did not exit")
            val diagnostics = stderr.readText()
            assertEquals(true, result["ok"]?.jsonPrimitive?.content == "true", "Node peer failed: $result\n$diagnostics")
            assertEquals(0, process.exitValue(), diagnostics)
            return result
        }
    }

    private fun relayOf(ready: JsonObject): List<String>? =
        (ready["brokers"] as? JsonArray)?.map { it.jsonPrimitive.content }

    /** A Kotlin transport on the relay announced by the Node peer, online. */
    private suspend fun kotlinTransport(ready: JsonObject, myId: String): SignalingTransport {
        val brokers = relayOf(ready)
        val transport: SignalingTransport = if (brokers != null) {
            MqttTransport(brokers = brokers, log = log, reconnectDelayMs = 500).also { t -> closers += { t.dispose() } }
        } else {
            WsTransport(ready.text("serverUrl"), log = log).also { t -> closers += { t.dispose() } }
        }
        transport.start(myId, Protocol.generateDeviceKey())
        withTimeout(15_000) { transport.state.first { it == TransportState.ONLINE } }
        if (transport is MqttTransport) waitUntil(15_000) { transport.connectedBrokers.size == brokers!!.size }
        return transport
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(100_000) { block() } }

    // ───────────────────── Kotlin controller → JS (desktop) host ─────────────────────

    private fun kotlinControllerToJsHost(transportKind: String) = test {
        val peer = NodePeer("--role", "host", "--transport", transportKind)
        val ready = peer.next()
        assertEquals(true, ready["ready"]?.jsonPrimitive?.content == "true", "$ready")
        val hostId = ready.text("id")
        val myId = Protocol.generateDeviceId()
        val transport = kotlinTransport(ready, myId)
        val signaling = Signaling(transport, myId, log = log).also { closers += { it.dispose() } }

        assertEquals(1L, signaling.probe(hostId).version)
        val prs = Prs.derive(ready.text("password"), hostId)
        val statuses = CopyOnWriteArrayList<ConnectStatus>()
        val session = signaling.connect(hostId, prs, SessionMessages.intro("Kotlin controller ✓", "1.2.0")) { statuses += it }
        assertEquals("JS host", session.peerName)
        assertEquals("linux", session.peerPlatform)
        assertEquals("1.2.0", session.peerVersion)
        assertEquals(Caps(control = true, files = true), Caps.from(session.info!!["caps"]))
        assertEquals(
            listOf(ConnectStatus.AUTHENTICATING, ConnectStatus.AUTHENTICATED, ConnectStatus.WAITING_APPROVAL),
            statuses.toList(),
        )
        // The host offers right after `accepted`: buffered in the session inbox.
        val offer = withTimeout(15_000) { session.messages.receive() }
        assertEquals(SessionDescription("offer", OFFER_SDP), SessionMessages.descriptionOf(offer))
        signaling.send(session.sid, SessionMessages.signal(SessionDescription("answer", ANSWER_SDP)))
        // The JS host checks the answer and ends the session with bye.
        assertTrue(withTimeout(15_000) { session.messages.receiveCatching() }.isClosed)
        assertEquals("interop-done", session.closeReason)
        peer.finish()
    }

    @Test
    fun `Kotlin controller to JS host over MQTT`() = kotlinControllerToJsHost("mqtt")

    @Test
    fun `Kotlin controller to JS host through the PairDesk server`() = kotlinControllerToJsHost("ws")

    // ───────────────────── JS (desktop) controller → Kotlin host ─────────────────────

    private fun jsControllerToKotlinHost(transportKind: String) = test {
        val peer = NodePeer("--role", "controller", "--transport", transportKind)
        val ready = peer.next()
        val ctrlId = ready.text("id")
        val hostId = Protocol.generateDeviceId()
        val password = Protocol.generatePassword()
        val transport = kotlinTransport(ready, hostId)
        val creds = listOf(Credential("temp", Prs.derive(password, hostId)), Credential("perm", Prs.derive("permanent password", hostId)))
        val signaling = Signaling(transport, hostId, { creds }, log = log).also { closers += { it.dispose() } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { s -> closers += { s.cancel() } }
        val incoming = Channel<SignalingSession>(Channel.UNLIMITED)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            signaling.events.collect { if (it is SignalingEvent.Incoming) incoming.send(it.session) }
        }
        peer.write(obj("hostId" to hostId, "password" to password))

        val session = withTimeout(30_000) { incoming.receive() }
        assertEquals(ctrlId, session.peerId)
        assertEquals("temp", session.credential)
        assertEquals("JS controller", session.peerName)
        assertEquals("linux", session.peerPlatform)
        assertEquals("1.2.0", session.peerVersion)
        assertEquals("control", session.kind)
        signaling.notifyWaiting(session.sid)
        delay(50)
        signaling.accept(session.sid, SessionMessages.accepted("Kotlin host ✓", Caps(control = true, clipboard = true), "1.2.0"))
        signaling.send(session.sid, SessionMessages.signal(SessionDescription("offer", OFFER_SDP)))
        val answer = withTimeout(15_000) { session.messages.receive() }
        assertEquals(SessionDescription("answer", ANSWER_SDP), SessionMessages.descriptionOf(answer))
        signaling.close(session.sid, "interop-done")
        val result = peer.finish()
        assertEquals(session.sid, result.str("sid"))
        assertEquals(0, signaling.limiter.recentFailures)
    }

    @Test
    fun `JS controller to Kotlin host over MQTT`() = jsControllerToKotlinHost("mqtt")

    @Test
    fun `JS controller to Kotlin host through the PairDesk server`() = jsControllerToKotlinHost("ws")

    // ───────────────────────────── wrong passwords ─────────────────────────────

    @Test
    fun `Kotlin controller with a wrong password is refused by the JS host`() = test {
        val peer = NodePeer("--role", "host", "--transport", "mqtt", "--expect", "auth")
        val ready = peer.next()
        val hostId = ready.text("id")
        val myId = Protocol.generateDeviceId()
        val signaling = Signaling(kotlinTransport(ready, myId), myId, log = log).also { closers += { it.dispose() } }
        val code = try {
            signaling.connect(hostId, Prs.derive(ready.text("password") + "x", hostId))
            null
        } catch (e: SignalingException) {
            e.code
        }
        assertEquals("auth", code)
        val result = peer.finish()
        assertEquals("true", result["authFailed"]?.jsonPrimitive?.content)
    }

    @Test
    fun `JS controller with a wrong password is refused by the Kotlin host`() = test {
        val peer = NodePeer("--role", "controller", "--transport", "mqtt", "--expect", "auth")
        val ready = peer.next()
        val hostId = Protocol.generateDeviceId()
        val password = Protocol.generatePassword()
        val limiter = AuthLimiter()
        val creds = listOf(Credential("temp", Prs.derive(password, hostId)))
        val signaling = Signaling(kotlinTransport(ready, hostId), hostId, { creds }, limiter = limiter, log = log)
            .also { closers += { it.dispose() } }
        val events = CopyOnWriteArrayList<SignalingEvent>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { s -> closers += { s.cancel() } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { signaling.events.collect { events += it } }
        peer.write(obj("hostId" to hostId, "password" to password))
        val result = peer.finish()
        assertEquals("auth", result.str("error"))
        waitUntil(10_000) { events.any { it is SignalingEvent.AuthFailed } }
        assertEquals(ready.text("id"), (events.first { it is SignalingEvent.AuthFailed } as SignalingEvent.AuthFailed).peerId)
        assertEquals(1, limiter.recentFailures)
        assertTrue(events.none { it is SignalingEvent.Incoming })
    }

    // ───────────────────────────── MQTT transport ─────────────────────────────

    @Test
    fun `MQTT transport - redundant brokers, de-duplication and a Kotlin handshake`() = test {
        val peer = NodePeer("--role", "broker")
        val ready = peer.next()
        val brokers = relayOf(ready)!! + "ws://127.0.0.1:9/unreachable"
        fun transport() = MqttTransport(brokers = brokers, prefix = "test/", log = log, reconnectDelayMs = 500)
            .also { t -> closers += { t.dispose() } }
        val ht = transport()
        val ct = transport()
        ht.start("123123123")
        ct.start("321321321")
        withTimeout(15_000) { ht.state.first { it == TransportState.ONLINE } }
        withTimeout(15_000) { ct.state.first { it == TransportState.ONLINE } }
        waitUntil(15_000) { ht.connectedBrokers.size == 2 && ct.connectedBrokers.size == 2 }

        val received = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        ht.addListener { from, data -> received += from to data }
        ct.send("123123123", obj("t" to "probe", "sid" to "mqtt-test-1")).await()
        waitUntil { received.isNotEmpty() }
        delay(300)
        assertEquals(1, received.size, "delivered once despite two brokers")
        assertEquals("321321321" to obj("t" to "probe", "sid" to "mqtt-test-1"), received[0])

        // Envelopes over 64 KiB are refused before publishing.
        val tooLarge = obj("t" to "x", "sid" to "big-message", "pad" to "x".repeat(70_000))
        val tooLargeCode = try {
            ct.send("123123123", tooLarge).await()
            null
        } catch (e: SignalingException) {
            e.code
        }
        assertEquals("too-large", tooLargeCode)

        val prs = Prs.derive("abc234", "123123123", iterations = 1000)
        val host = Signaling(ht, "123123123", { listOf(Credential("temp", prs)) }).also { closers += { it.dispose() } }
        val ctrl = Signaling(ct, "321321321").also { closers += { it.dispose() } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { s -> closers += { s.cancel() } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            host.events.collect { if (it is SignalingEvent.Incoming) host.accept(it.session.sid, obj("name" to "mqtt-host")) }
        }
        val session = ctrl.connect("123123123", prs)
        assertEquals("mqtt-host", session.peerName)

        // Stopping the transport: sends fail with network.
        ct.stop()
        val code = try {
            ct.send("123123123", obj("t" to "probe", "sid" to "after-stop")).await()
            null
        } catch (e: SignalingException) {
            e.code
        }
        assertEquals("network", code)
        assertEquals(TransportState.OFFLINE, ct.state.value)
        peer.closeInput()
        peer.finish()
    }

    /** Two Kotlin transports on the relay, the relay drops them, they come back and still talk. */
    private fun reconnectsAfterRelayDrop(transportKind: String) = test {
        val peer = NodePeer("--role", "broker", "--transport", transportKind)
        val ready = peer.next()
        val a = kotlinTransport(ready, "111222333")
        val b = kotlinTransport(ready, "333222111")
        val received = CopyOnWriteArrayList<JsonObject>()
        b.addListener { _, data -> received += data }
        a.send("333222111", obj("t" to "probe", "sid" to "before-drop")).await()
        waitUntil { received.size == 1 }

        val seen = CopyOnWriteArrayList<TransportState>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { s -> closers += { s.cancel() } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { a.state.collect { seen += it } }
        peer.writeLine("kick")
        val kicked = peer.next()
        assertTrue((kicked["kicked"]?.jsonPrimitive?.content?.toInt() ?: 0) >= 2, "$kicked")
        waitUntil(10_000) { TransportState.CONNECTING in seen }
        a.reconnectNow() // harmless while a retry is pending
        withTimeout(20_000) { a.state.first { it == TransportState.ONLINE } }
        withTimeout(20_000) { b.state.first { it == TransportState.ONLINE } }
        if (a is MqttTransport && b is MqttTransport) waitUntil(20_000) { a.connectedBrokers.size == 2 && b.connectedBrokers.size == 2 }
        // The server may still hold the dropped registration for a moment: retry until delivered.
        withTimeout(20_000) {
            while (true) {
                try {
                    a.send("333222111", obj("t" to "probe", "sid" to "after-drop")).await()
                    break
                } catch (e: SignalingException) {
                    delay(200)
                }
            }
        }
        waitUntil { received.any { it.str("sid") == "after-drop" } }
        peer.closeInput()
        peer.finish()
    }

    @Test
    fun `MQTT transport reconnects after the relay drops the connection`() = reconnectsAfterRelayDrop("mqtt")

    @Test
    fun `server transport reconnects after the relay drops the connection`() = reconnectsAfterRelayDrop("ws")

    @Test
    fun `server transport - offline peers and unknown ids`() = test {
        val peer = NodePeer("--role", "broker", "--transport", "ws")
        val ready = peer.next()
        val myId = Protocol.generateDeviceId()
        val transport = kotlinTransport(ready, myId) as WsTransport
        val code = try {
            transport.send("199999999", obj("t" to "probe", "sid" to "offline-peer")).await()
            null
        } catch (e: SignalingException) {
            e.code
        }
        assertEquals("offline", code)
        val signaling = Signaling(transport, myId).also { closers += { it.dispose() } }
        val probeCode = try {
            signaling.probe("199999999")
            null
        } catch (e: SignalingException) {
            e.code
        }
        assertEquals("offline", probeCode)
        assertNotNull(transport.iceServers.value)
        peer.closeInput()
        peer.finish()
    }

    companion object {
        private val nodeAvailable: Boolean by lazy {
            try {
                val p = ProcessBuilder("node", "--version").redirectErrorStream(true).start()
                p.inputStream.readBytes()
                p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
            } catch (_: Exception) {
                false
            }
        }
    }
}
