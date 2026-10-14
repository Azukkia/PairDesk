package io.github.azukkia.pairdesk.net

import io.github.azukkia.pairdesk.core.PairDeskDefaults
import io.github.azukkia.pairdesk.core.json.jsStringOrNull
import io.github.azukkia.pairdesk.core.json.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** One `RTCIceServer` (STUN or TURN), independent of the WebRTC library. */
data class IceServerSpec(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

object IceServers {
    private val ICE_URL = Regex("^(stun|stuns|turn|turns):\\S+$", RegexOption.IGNORE_CASE)

    /** The STUN servers of pairdesk.config.json. */
    val DEFAULT: List<IceServerSpec> = PairDeskDefaults.ICE_SERVERS.map { IceServerSpec(it) }

    /**
     * Parses the `iceServers` handed out by a private PairDesk server
     * (`[{urls: string | string[], username?, credential?}]`). Entries without a
     * usable URL are dropped; never throws.
     */
    fun parse(array: JsonArray?): List<IceServerSpec> {
        if (array == null) return emptyList()
        return array.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val raw = o["urls"] ?: o["url"]
            val urls = when (raw) {
                is JsonArray -> raw.mapNotNull { it.jsStringOrNull() }
                else -> listOfNotNull(raw.jsStringOrNull())
            }.map { it.trim() }.filter { ICE_URL.matches(it) }
            if (urls.isEmpty()) return@mapNotNull null
            IceServerSpec(urls, o.str("username"), o.str("credential"))
        }
    }

    /** Defaults first, then the servers of the private server (TURN), like the desktop. */
    fun combine(serverIce: JsonArray?): List<IceServerSpec> = DEFAULT + parse(serverIce)
}
