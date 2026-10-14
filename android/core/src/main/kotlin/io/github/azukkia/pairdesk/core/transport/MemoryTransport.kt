package io.github.azukkia.pairdesk.core.transport

import io.github.azukkia.pairdesk.core.signaling.SignalingException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * In-memory signaling network (tests, loopback). Several transports may listen
 * on one ID, like devices sharing a topic on a public relay. Messages are
 * delivered asynchronously, in order, on the bus thread.
 */
class MemoryBus {
    internal val listeners = ConcurrentHashMap<String, CopyOnWriteArraySet<MemoryTransport>>()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "pairdesk-memory-bus").apply { isDaemon = true } }

    /** Every message sent on the bus, in order (to check what a relay sees). */
    val log: MutableList<Triple<String, String, JsonObject>> = CopyOnWriteArrayList()

    fun transport(): MemoryTransport = MemoryTransport(this)

    internal fun deliver(block: () -> Unit) = executor.execute(block)

    fun shutdown() {
        executor.shutdownNow()
    }
}

class MemoryTransport internal constructor(private val bus: MemoryBus) : SignalingTransport {
    override val kind = TransportKind.MEMORY
    private val _state = MutableStateFlow(TransportState.OFFLINE)
    override val state: StateFlow<TransportState> = _state.asStateFlow()
    override val iceServers: StateFlow<JsonArray> = MutableStateFlow(JsonArray(emptyList()))
    private val listeners = CopyOnWriteArrayList<TransportListener>()

    @Volatile
    var myId: String? = null
        private set

    override fun start(myId: String, deviceKey: String?) {
        this.myId = myId
        bus.listeners.computeIfAbsent(myId) { CopyOnWriteArraySet() }.add(this)
        _state.value = TransportState.ONLINE
    }

    override fun stop() {
        myId?.let { bus.listeners[it]?.remove(this) }
        _state.value = TransportState.OFFLINE
    }

    override fun send(to: String, data: JsonObject): Deferred<Unit> {
        val from = myId ?: return failedDeferred("network")
        val targets = bus.listeners[to]
        if (targets.isNullOrEmpty()) return CompletableDeferred<Unit>().apply { completeExceptionally(SignalingException("offline")) }
        bus.log.add(Triple(from, to, data))
        for (t in targets) bus.deliver { t.receive(from, data) }
        return CompletableDeferred(Unit)
    }

    /** Injects a message as if it came from [from] (tests). */
    fun receive(from: String, data: JsonObject) {
        for (l in listeners) l.onMessage(from, data)
    }

    override fun addListener(listener: TransportListener) {
        listeners.addIfAbsent(listener)
    }

    override fun removeListener(listener: TransportListener) {
        listeners.remove(listener)
    }
}
