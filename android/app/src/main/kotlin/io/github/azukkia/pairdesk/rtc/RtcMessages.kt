package io.github.azukkia.pairdesk.rtc

import io.github.azukkia.pairdesk.core.json.jsNumberOrNull
import io.github.azukkia.pairdesk.core.json.jsStringOrNull
import io.github.azukkia.pairdesk.core.json.jsTruthy
import io.github.azukkia.pairdesk.core.signaling.SessionDescription
import io.github.azukkia.pairdesk.core.signaling.SessionMessages
import io.github.azukkia.pairdesk.core.signaling.Signaling.Companion.obj
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The `signal` session messages exchanged by [RtcSession], with the JSON
 * shapes Chrome produces (`RTCIceCandidate.toJSON()`,
 * `RTCSessionDescription.toJSON()`), so that the desktop applies them as is.
 */
object RtcSignals {
    /**
     * `{type:'signal', data:{candidate:{candidate, sdpMid, sdpMLineIndex, usernameFragment}}}`;
     * `usernameFragment` is read from the candidate line (libwebrtc always
     * writes `ufrag`) and left out when absent.
     */
    fun candidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?): JsonObject {
        val fields = linkedMapOf<String, JsonElement>(
            "candidate" to JsonPrimitive(candidate),
            "sdpMid" to (sdpMid?.let(::JsonPrimitive) ?: JsonNull),
            "sdpMLineIndex" to (sdpMLineIndex?.let(::JsonPrimitive) ?: JsonNull),
        )
        ufragOf(candidate)?.let { fields["usernameFragment"] = JsonPrimitive(it) }
        return obj("type" to "signal", "data" to obj("candidate" to JsonObject(fields)))
    }

    /** `{type:'signal', data:{description:{type, sdp}}}`. */
    fun description(type: String, sdp: String): JsonObject = SessionMessages.signal(SessionDescription(type, sdp))

    /** The `ufrag` attribute of an ICE candidate line, if any. */
    fun ufragOf(candidate: String): String? = attribute(candidate, "ufrag")

    /** `host`, `srflx`, `prflx` or `relay` (logs). */
    fun typeOf(candidate: String): String? = attribute(candidate, "typ")

    private fun attribute(candidate: String, name: String): String? {
        // "candidate:<foundation> <component> <transport> <priority> <address> <port> typ <type> [name value]…"
        val tokens = candidate.trim().split(WHITESPACE)
        val index = tokens.indexOf(name)
        if (index < 0 || index + 1 >= tokens.size) return null
        return tokens[index + 1].takeIf { it.isNotEmpty() }
    }

    private val WHITESPACE = Regex("\\s+")
}

/** Answers to control messages handled by [RtcSession] itself (rtc.js #onControl). */
object RtcControl {
    /** `{type:'pong', t}` for a `ping` (`t` left out when the ping has none, like JSON.stringify). */
    fun pong(ping: JsonObject): JsonObject {
        val t = ping["t"]
        return if (t == null) obj("type" to "pong") else obj("type" to "pong", "t" to t)
    }

    /**
     * `{type:'file-reject', fid}` for a valid `file-offer` (file transfers are
     * not supported by this app yet); null for an invalid offer, which the
     * desktop ignores as well (`!msg.fid || !Number.isSafeInteger(size) || size < 0`).
     */
    fun fileReject(offer: JsonObject): JsonObject? {
        val fid = offer["fid"]
        if (!fid.jsTruthy()) return null
        val size = jsNumber(offer["size"]) ?: return null
        if (size < 0 || size != kotlin.math.floor(size) || size > MAX_SAFE_INTEGER) return null
        return obj("type" to "file-reject", "fid" to fid)
    }

    /** `Number(x)` for numbers and numeric strings (null for anything else). */
    private fun jsNumber(value: JsonElement?): Double? =
        value.jsNumberOrNull() ?: value.jsStringOrNull()?.trim()?.let { s -> if (s.isEmpty()) 0.0 else s.toDoubleOrNull() }

    private const val MAX_SAFE_INTEGER = 9007199254740991.0
}
