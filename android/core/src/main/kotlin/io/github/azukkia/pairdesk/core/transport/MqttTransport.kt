package io.github.azukkia.pairdesk.core.transport

import io.github.azukkia.pairdesk.core.PairDeskDefaults
import io.github.azukkia.pairdesk.core.Protocol
import io.github.azukkia.pairdesk.core.crypto.Hex
import io.github.azukkia.pairdesk.core.crypto.Rng
import io.github.azukkia.pairdesk.core.crypto.sha256
import io.github.azukkia.pairdesk.core.json.JsonJs
import io.github.azukkia.pairdesk.core.json.jsStringOrNull
import io.github.azukkia.pairdesk.core.signaling.SignalingException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

/**
 * Signaling over public MQTT brokers (src/main/signaling/transport-mqtt.js).
 *
 * Every device listens on a topic derived from its ID. Messages are published
 * to all connected brokers at once (redundancy) and de-duplicated on
 * reception. The brokers only see PAKE public values and ciphertexts.
 * MQTT 3.1.1 over (secure) WebSocket, clean session, keepalive 30 s, QoS 1.
 */
class MqttTransport(
    val brokers: List<String> = PairDeskDefaults.PUBLIC_BROKERS,
    val prefix: String = PairDeskDefaults.MQTT_TOPIC_PREFIX,
    private val log: Logger = Logger.NONE,
    private val reconnectDelayMs: Long = 5_000,
    private val connectTimeoutSeconds: Int = 12,
    private val publishTimeoutMs: Long = PUBLISH_TIMEOUT_MS,
    /** Optional socket factory for wss:// (default: the platform SSLSocketFactory). */
    private val socketFactory: SocketFactory? = null,
) : SignalingTransport {

    override val kind = TransportKind.PUBLIC

    private val _state = MutableStateFlow(TransportState.OFFLINE)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    override val iceServers: StateFlow<JsonArray> = MutableStateFlow(JsonArray(emptyList()))

    private val listeners = CopyOnWriteArrayList<TransportListener>()
    private val entries = CopyOnWriteArrayList<Entry>()
    private val seen = LinkedHashMap<String, Long>()
    private val timers: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "pairdesk-mqtt-timer").apply { isDaemon = true }
    }

    @Volatile
    var myId: String? = null
        private set

    @Volatile
    private var topic: String? = null

    @Volatile
    private var running = false

    private inner class Entry(val url: String) {
        @Volatile
        var client: MqttAsyncClient? = null

        /** Connected and subscribed. */
        @Volatile
        var connected = false

        @Volatile
        var active = true

        /** A connection attempt is in progress (at most one at a time). */
        val connecting = AtomicBoolean(false)

        /** The scheduled reconnection, if any (at most one at a time). */
        @Volatile
        var retry: ScheduledFuture<*>? = null
    }

    /** URLs of the brokers currently connected and subscribed. */
    val connectedBrokers: List<String> get() = entries.filter { it.connected }.map { it.url }

    @Synchronized
    override fun start(myId: String, deviceKey: String?) {
        if (running) stop()
        this.myId = myId
        topic = topicFor(prefix, myId)
        running = true
        setState(TransportState.CONNECTING)
        for (url in brokers) {
            val entry = Entry(url)
            entries.add(entry)
            connect(entry)
        }
    }

    @Synchronized
    override fun stop() {
        running = false
        for (entry in entries) {
            entry.active = false
            entry.connected = false
            entry.retry?.cancel(false)
            val client = entry.client ?: continue
            try {
                client.disconnectForcibly(0, 1000, false)
            } catch (_: Exception) {
                // ignore
            }
            try {
                client.close(true)
            } catch (_: Exception) {
                // ignore
            }
        }
        entries.clear()
        setState(TransportState.OFFLINE)
    }

    /** Reconnects the brokers that are down right away (e.g. when the network comes back). */
    override fun reconnectNow() {
        if (!running) return
        for (entry in entries) {
            if (entry.connected || !entry.active) continue
            entry.retry?.cancel(false)
            entry.retry = null
            connect(entry)
        }
    }

    private fun connect(entry: Entry) {
        if (!entry.active || !running) return
        if (!entry.connecting.compareAndSet(false, true)) return
        val client = try {
            entry.client ?: MqttAsyncClient(entry.url, "pd_" + Rng.hex(8), MemoryPersistence()).also { c ->
                entry.client = c
                c.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        if (entry.connected) log.info("[mqtt] disconnected from ${entry.url}")
                        entry.connected = false
                        updateState()
                        scheduleReconnect(entry)
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        message?.payload?.let(::onPayload)
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
            }
        } catch (e: Exception) {
            log.warn("[mqtt] invalid broker URL ${entry.url}: ${e.message}")
            entry.connecting.set(false)
            return
        }
        if (client.isConnected) {
            // Connected but not subscribed (a subscription failure is handled by dropAndRetry).
            entry.connecting.set(false)
            return
        }
        val options = MqttConnectOptions().apply {
            isCleanSession = true
            keepAliveInterval = 30
            connectionTimeout = connectTimeoutSeconds
            isAutomaticReconnect = false
            mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
            maxInflight = 100
            isHttpsHostnameVerificationEnabled = true
            this@MqttTransport.socketFactory?.let { socketFactory = it }
        }
        try {
            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    entry.connecting.set(false)
                    subscribe(entry, client)
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    entry.connecting.set(false)
                    log.warn("[mqtt] ${entry.url}: ${exception?.message ?: "connection failed"}")
                    scheduleReconnect(entry)
                }
            })
        } catch (e: Exception) {
            entry.connecting.set(false)
            log.warn("[mqtt] ${entry.url}: ${e.message}")
            scheduleReconnect(entry)
        }
    }

    private fun subscribe(entry: Entry, client: MqttAsyncClient) {
        val t = topic ?: return
        try {
            client.subscribe(t, 1, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    if (!entry.active) return
                    // A broker refusing the subscription answers 0x80 in SUBACK.
                    val granted = asyncActionToken?.grantedQos?.firstOrNull()
                    if (granted == 0x80) {
                        log.warn("[mqtt] subscribe refused by ${entry.url}")
                        dropAndRetry(entry, client)
                        return
                    }
                    entry.connected = true
                    log.info("[mqtt] connected to ${entry.url}")
                    updateState()
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    log.warn("[mqtt] subscribe failed on ${entry.url}: ${exception?.message}")
                    dropAndRetry(entry, client)
                }
            })
        } catch (e: Exception) {
            log.warn("[mqtt] subscribe failed on ${entry.url}: ${e.message}")
            dropAndRetry(entry, client)
        }
    }

    private fun dropAndRetry(entry: Entry, client: MqttAsyncClient) {
        entry.connected = false
        updateState()
        try {
            client.disconnectForcibly(0, 1000, false)
        } catch (_: Exception) {
            // ignore
        }
        scheduleReconnect(entry)
    }

    private fun scheduleReconnect(entry: Entry) {
        if (!entry.active || !running) return
        synchronized(entry) {
            if (entry.retry?.isDone == false) return
            entry.retry = try {
                timers.schedule({
                    entry.retry = null
                    if (entry.active && running && !entry.connected) connect(entry)
                }, reconnectDelayMs, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                null // executor shut down
            }
        }
    }

    private fun onPayload(payload: ByteArray) {
        if (payload.size > MAX_PAYLOAD) return
        val msg = JsonJs.parseObjectOrNull(String(payload, Charsets.UTF_8)) ?: return
        val mid = msg["mid"].jsStringOrNull() ?: return
        if (mid.length > 64) return
        val from = msg["from"].jsStringOrNull()
        if (!Protocol.isValidId(from)) return
        val data = msg["data"] as? JsonObject ?: return
        synchronized(seen) {
            if (seen.containsKey(mid)) return
            seen[mid] = System.currentTimeMillis()
            if (seen.size > 4000) {
                // LinkedHashMap keeps insertion order: drop the oldest half.
                val it = seen.keys.iterator()
                var drop = 2000
                while (drop-- > 0 && it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
        }
        for (l in listeners) {
            try {
                l.onMessage(from!!, data)
            } catch (e: Exception) {
                log.warn("[mqtt] listener failed: ${e.message}")
            }
        }
    }

    private fun updateState() {
        if (!running) return
        setState(if (entries.any { it.connected }) TransportState.ONLINE else TransportState.CONNECTING)
    }

    private fun setState(s: TransportState) {
        if (_state.value == s) return
        _state.value = s
    }

    override fun send(to: String, data: JsonObject): Deferred<Unit> {
        val live = entries.filter { it.connected }
        val me = myId
        if (live.isEmpty() || me == null) return failedDeferred("network", "Not connected to the PairDesk network")
        val envelope = JsonObject(
            linkedMapOf("mid" to JsonPrimitive(Rng.b64u(12)), "from" to JsonPrimitive(me), "data" to data),
        )
        val payload = JsonJs.stringify(envelope).toByteArray(Charsets.UTF_8)
        if (payload.size > MAX_PAYLOAD) return failedDeferred("too-large", "Message too large")
        val destination = topicFor(prefix, to)
        val result = CompletableDeferred<Unit>()
        val remaining = AtomicInteger(live.size)
        for (entry in live) {
            val settled = AtomicBoolean(false)
            val fail = {
                if (settled.compareAndSet(false, true) && remaining.decrementAndGet() == 0) {
                    result.completeExceptionally(SignalingException("network", "Unable to deliver the message"))
                }
            }
            val timeout = try {
                timers.schedule(fail, publishTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
                null
            }
            try {
                val client = entry.client ?: throw IllegalStateException("no client")
                client.publish(destination, payload, 1, false, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        timeout?.cancel(false)
                        if (settled.compareAndSet(false, true)) result.complete(Unit)
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        timeout?.cancel(false)
                        fail()
                    }
                })
            } catch (e: Exception) {
                timeout?.cancel(false)
                fail()
            }
        }
        return result
    }

    override fun addListener(listener: TransportListener) {
        listeners.addIfAbsent(listener)
    }

    override fun removeListener(listener: TransportListener) {
        listeners.remove(listener)
    }

    /** Stops the transport and its timer thread for good. */
    fun dispose() {
        stop()
        timers.shutdownNow()
    }

    companion object {
        const val MAX_PAYLOAD = 64 * 1024
        const val PUBLISH_TIMEOUT_MS = 10_000L

        /** `prefix + hex(SHA-256("pairdesk:" + id))[0:32]`. */
        fun topicFor(prefix: String, id: String): String =
            prefix + Hex.encode(sha256(("pairdesk:$id").toByteArray(Charsets.UTF_8))).substring(0, 32)
    }
}
