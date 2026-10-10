package io.github.azukkia.pairdesk.core.transport

import io.github.azukkia.pairdesk.core.Protocol
import io.github.azukkia.pairdesk.core.crypto.Rng
import io.github.azukkia.pairdesk.core.json.JsonJs
import io.github.azukkia.pairdesk.core.json.jsStringOrNull
import io.github.azukkia.pairdesk.core.json.str
import io.github.azukkia.pairdesk.core.signaling.SignalingException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Signaling through a self-hosted PairDesk server (src/main/signaling/transport-ws.js,
 * server/src/server.js). The server routes messages between registered IDs and may
 * hand out TURN credentials; it never sees passwords nor decrypted data.
 */
class WsTransport(
    val url: String,
    private val log: Logger = Logger.NONE,
    /** Called when the server refuses our ID (registered by another device key). */
    private val onIdTaken: (() -> Unit)? = null,
    client: OkHttpClient? = null,
) : SignalingTransport {

    override val kind = TransportKind.SERVER

    private val _state = MutableStateFlow(TransportState.OFFLINE)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _ice = MutableStateFlow(JsonArray(emptyList()))
    override val iceServers: StateFlow<JsonArray> = _ice.asStateFlow()

    /** Last error reported by the server or the connection (e.g. `id-taken`). */
    @Volatile
    var lastError: String? = null
        private set

    private val http: OkHttpClient = (client ?: OkHttpClient()).newBuilder()
        .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .connectTimeout(HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val listeners = CopyOnWriteArrayList<TransportListener>()
    private val pending = ConcurrentHashMap<String, Pending>()
    private val timers: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "pairdesk-ws-timer").apply { isDaemon = true }
    }

    private class Pending(val result: CompletableDeferred<Unit>, val timer: ScheduledFuture<*>?)

    private val lock = Any()
    private var ws: WebSocket? = null
    private var retryMs = 1000L
    private var retryTimer: ScheduledFuture<*>? = null
    private var stopped = true
    private var myId: String? = null
    private var deviceKey: String? = null

    /** [deviceKey] is required: it proves the ownership of [myId] to the server. */
    override fun start(myId: String, deviceKey: String?) {
        require(deviceKey != null) { "the PairDesk server transport needs the device key" }
        synchronized(lock) {
            this.myId = myId
            this.deviceKey = deviceKey
            stopped = false
        }
        open()
    }

    override fun stop() {
        val socket: WebSocket?
        synchronized(lock) {
            stopped = true
            retryTimer?.cancel(false)
            retryTimer = null
            socket = ws
            ws = null
        }
        socket?.cancel()
        failPending("network")
        setState(TransportState.OFFLINE)
    }

    override fun reconnectNow() {
        synchronized(lock) {
            if (stopped || ws != null) return
            retryTimer?.cancel(false)
            retryTimer = null
            retryMs = 1000
        }
        open()
    }

    /** Stops the transport and releases its threads. */
    fun dispose() {
        stop()
        timers.shutdownNow()
    }

    private fun open() {
        val request = synchronized(lock) {
            if (stopped) return
            try {
                Request.Builder().url(url).build()
            } catch (e: IllegalArgumentException) {
                lastError = e.message
                log.warn("[ws] invalid server URL $url: ${e.message}")
                setState(TransportState.ERROR)
                return
            }
        }
        setState(TransportState.CONNECTING)
        val listener = Listener()
        val socket = http.newWebSocket(request, listener)
        synchronized(lock) {
            if (stopped) {
                socket.cancel()
                return
            }
            ws = socket
        }
        // Handshake timeout (10 s), like the desktop client.
        listener.handshakeTimer = schedule(HANDSHAKE_TIMEOUT_MS) { if (!listener.opened) socket.cancel() }
    }

    private inner class Listener : WebSocketListener() {
        @Volatile
        var opened = false

        @Volatile
        var handshakeTimer: ScheduledFuture<*>? = null

        private var closedHandled = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened = true
            handshakeTimer?.cancel(false)
            val register = JsonObject(
                linkedMapOf(
                    "t" to JsonPrimitive("register"),
                    "id" to JsonPrimitive(myId),
                    "key" to JsonPrimitive(deviceKey),
                    "v" to JsonPrimitive(Protocol.PROTOCOL_VERSION),
                ),
            )
            webSocket.send(JsonJs.stringify(register))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.length > MAX_MESSAGE) return
            val msg = JsonJs.parseObjectOrNull(text) ?: return
            onServerMessage(msg)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            onDown(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onDown(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!synchronized(lock) { stopped }) {
                lastError = t.message
                log.warn("[ws] ${t.message}")
            }
            onDown(webSocket)
        }

        @Synchronized
        private fun onDown(webSocket: WebSocket) {
            if (closedHandled) return
            closedHandled = true
            handshakeTimer?.cancel(false)
            val delay: Long
            synchronized(lock) {
                if (ws === webSocket) ws = null
                if (stopped) {
                    delay = -1
                } else {
                    delay = retryMs
                    retryMs = minOf(retryMs * 2, 30_000L)
                }
            }
            failPending("network")
            if (delay < 0) return
            if (_state.value != TransportState.ERROR) setState(TransportState.CONNECTING)
            synchronized(lock) {
                retryTimer = schedule(delay) { open() }
            }
        }
    }

    private fun onServerMessage(msg: JsonObject) {
        when (msg.str("t")) {
            "registered" -> {
                synchronized(lock) { retryMs = 1000 }
                lastError = null
                _ice.value = msg["iceServers"] as? JsonArray ?: JsonArray(emptyList())
                setState(TransportState.ONLINE)
            }
            "error" -> {
                val code = msg["code"].jsStringOrNull() ?: "error"
                val mid = msg["mid"].jsStringOrNull()
                if (mid != null && pending.containsKey(mid)) {
                    settle(mid, SignalingException(code))
                } else {
                    lastError = code
                    log.warn("[ws] server error: $code")
                    if (code == "id-taken") onIdTaken?.invoke()
                }
            }
            "ack" -> msg["mid"].jsStringOrNull()?.let { settle(it, null) }
            "msg" -> {
                val from = msg["from"].jsStringOrNull()
                val data = msg["data"] as? JsonObject
                if (from != null && data != null) {
                    for (l in listeners) {
                        try {
                            l.onMessage(from, data)
                        } catch (e: Exception) {
                            log.warn("[ws] listener failed: ${e.message}")
                        }
                    }
                }
            }
            "ice-servers" -> _ice.value = msg["iceServers"] as? JsonArray ?: JsonArray(emptyList())
            else -> Unit
        }
    }

    private fun settle(mid: String, error: SignalingException?) {
        val p = pending.remove(mid) ?: return
        p.timer?.cancel(false)
        if (error != null) p.result.completeExceptionally(error) else p.result.complete(Unit)
    }

    private fun failPending(code: String) {
        for (mid in pending.keys.toList()) settle(mid, SignalingException(code))
    }

    private fun setState(s: TransportState) {
        _state.value = s
    }

    private fun schedule(delayMs: Long, block: () -> Unit): ScheduledFuture<*>? = try {
        timers.schedule(block, delayMs, TimeUnit.MILLISECONDS)
    } catch (_: Exception) {
        null
    }

    override fun send(to: String, data: JsonObject): Deferred<Unit> {
        val socket = synchronized(lock) { ws }
        if (socket == null || _state.value != TransportState.ONLINE) {
            return failedDeferred("network", "Not connected to the PairDesk server")
        }
        val mid = Rng.b64u(9)
        val result = CompletableDeferred<Unit>()
        val timer = schedule(SEND_TIMEOUT_MS) { settle(mid, SignalingException("network", "timeout")) }
        pending[mid] = Pending(result, timer)
        val frame = JsonObject(
            linkedMapOf("t" to JsonPrimitive("send"), "to" to JsonPrimitive(to), "mid" to JsonPrimitive(mid), "data" to data),
        )
        if (!socket.send(JsonJs.stringify(frame))) settle(mid, SignalingException("network"))
        return result
    }

    override fun addListener(listener: TransportListener) {
        listeners.addIfAbsent(listener)
    }

    override fun removeListener(listener: TransportListener) {
        listeners.remove(listener)
    }

    companion object {
        const val SEND_TIMEOUT_MS = 10_000L
        const val PING_INTERVAL_MS = 25_000L
        const val HANDSHAKE_TIMEOUT_MS = 10_000L
        const val MAX_MESSAGE = 256 * 1024
    }
}
