package io.github.azukkia.pairdesk.core.transport

import io.github.azukkia.pairdesk.core.signaling.SignalingException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

enum class TransportState(val wire: String) {
    OFFLINE("offline"),
    CONNECTING("connecting"),
    ONLINE("online"),

    /** Unrecoverable configuration error (e.g. invalid server URL). */
    ERROR("error"),
}

enum class TransportKind(val wire: String) {
    /** Public MQTT relays (default, zero configuration). */
    PUBLIC("public"),

    /** Self-hosted PairDesk server. */
    SERVER("server"),

    /** In-process bus (tests). */
    MEMORY("memory"),
}

/** Receives `{from, data}` messages; called on a transport thread, must return quickly. */
fun interface TransportListener {
    fun onMessage(from: String, data: JsonObject)
}

/** Diagnostics sink (the app maps it to android.util.Log). Must be thread-safe. */
interface Logger {
    fun info(message: String) {}
    fun warn(message: String) {}

    companion object {
        val NONE: Logger = object : Logger {}
    }
}

/**
 * Delivers JSON objects between device IDs (docs/PROTOCOL.md section 2).
 * Implementations are thread-safe.
 */
interface SignalingTransport {
    val kind: TransportKind

    val state: StateFlow<TransportState>

    /** ICE servers handed out by a private server (RTCIceServer objects); empty otherwise. */
    val iceServers: StateFlow<JsonArray>

    /** Starts listening as [myId]; [deviceKey] is needed by the private server transport. */
    fun start(myId: String, deviceKey: String? = null)

    fun stop()

    /**
     * Retries the connections that are down now instead of waiting for the
     * next scheduled attempt (call it when the device's network comes back).
     */
    fun reconnectNow() {}

    /**
     * Queues [data] for [to] and returns immediately: messages sent from one
     * thread leave in order. The result completes when the message was
     * accepted by the relay, or fails with a [SignalingException] whose code
     * is `network`, `offline` (private server: peer not connected) or `too-large`.
     */
    fun send(to: String, data: JsonObject): Deferred<Unit>

    fun addListener(listener: TransportListener)

    fun removeListener(listener: TransportListener)
}

internal fun failedDeferred(code: String, message: String? = null): Deferred<Unit> =
    CompletableDeferred<Unit>().apply { completeExceptionally(SignalingException(code, message)) }
