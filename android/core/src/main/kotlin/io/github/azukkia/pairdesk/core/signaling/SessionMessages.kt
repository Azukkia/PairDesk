package io.github.azukkia.pairdesk.core.signaling

import io.github.azukkia.pairdesk.core.Protocol
import io.github.azukkia.pairdesk.core.json.jsSafeIntegerOrNull
import io.github.azukkia.pairdesk.core.json.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Host capabilities announced in `accepted.caps` (docs/PROTOCOL.md 3.3). */
data class Caps(
    val control: Boolean = false,
    val files: Boolean = false,
    val clipboard: Boolean = false,
    val audio: Boolean = false,
) {
    fun toJson(): JsonObject = Signaling.obj("control" to control, "files" to files, "clipboard" to clipboard, "audio" to audio)

    companion object {
        /** Reads `caps` like the desktop (`Boolean(caps.x)`): anything but `true` is false. */
        fun from(value: JsonElement?): Caps {
            val o = value as? JsonObject ?: return Caps()
            fun flag(key: String) = (o[key] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } ?: false
            return Caps(flag("control"), flag("files"), flag("clipboard"), flag("audio"))
        }
    }
}

/** An SDP offer or answer (`RTCSessionDescriptionInit`). */
data class SessionDescription(val type: String, val sdp: String)

/** A trickled ICE candidate (`RTCIceCandidateInit`). */
data class IceCandidate(
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int?,
    val usernameFragment: String? = null,
)

/**
 * Builders and parsers of the sealed session messages exchanged through
 * [Signaling] (docs/PROTOCOL.md 3.3). Unknown fields are ignored on input.
 */
object SessionMessages {
    const val KIND_CONTROL = "control"
    const val KIND_CAMERA = "camera"

    /** Fields of `intro` for [Signaling.connect] (`type` is added by [Signaling]). */
    fun intro(
        name: String,
        appVersion: String,
        kind: String = KIND_CONTROL,
        platform: String = Protocol.PLATFORM_ANDROID,
    ): JsonObject = Signaling.obj("name" to name, "platform" to platform, "appVersion" to appVersion, "kind" to kind)

    /** Fields of `accepted` for [Signaling.accept]; 1.2+ hosts echo the session [kind]. */
    fun accepted(
        name: String,
        caps: Caps,
        appVersion: String,
        kind: String? = null,
        platform: String = Protocol.PLATFORM_ANDROID,
    ): JsonObject {
        val pairs = mutableListOf<Pair<String, Any?>>(
            "name" to name,
            "caps" to caps.toJson(),
            "platform" to platform,
            "appVersion" to appVersion,
        )
        if (kind != null) pairs += "kind" to kind
        return Signaling.obj(*pairs.toTypedArray())
    }

    /** `{type:'signal', data:{description:{type, sdp}}}`. */
    fun signal(description: SessionDescription): JsonObject = Signaling.obj(
        "type" to "signal",
        "data" to Signaling.obj("description" to Signaling.obj("type" to description.type, "sdp" to description.sdp)),
    )

    /** `{type:'signal', data:{candidate:{candidate, sdpMid, sdpMLineIndex, usernameFragment}}}`. */
    fun signal(candidate: IceCandidate): JsonObject = Signaling.obj(
        "type" to "signal",
        "data" to Signaling.obj(
            "candidate" to Signaling.obj(
                "candidate" to candidate.candidate,
                "sdpMid" to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex,
                "usernameFragment" to candidate.usernameFragment,
            ),
        ),
    )

    /** The description carried by a `signal` message, if any. */
    fun descriptionOf(message: JsonObject): SessionDescription? {
        if (message.str("type") != "signal") return null
        val d = (message["data"] as? JsonObject)?.get("description") as? JsonObject ?: return null
        val type = d.str("type") ?: return null
        return SessionDescription(type, d.str("sdp") ?: "")
    }

    /** The ICE candidate carried by a `signal` message, if any. */
    fun candidateOf(message: JsonObject): IceCandidate? {
        if (message.str("type") != "signal") return null
        val c = (message["data"] as? JsonObject)?.get("candidate") as? JsonObject ?: return null
        val candidate = c.str("candidate") ?: return null
        val index = c["sdpMLineIndex"].takeUnless { it == null || it is JsonNull }.jsSafeIntegerOrNull()
        return IceCandidate(candidate, c.str("sdpMid"), index?.toInt(), c.str("usernameFragment"))
    }
}
