package io.github.azukkia.pairdesk.data

import io.github.azukkia.pairdesk.core.Protocol
import io.github.azukkia.pairdesk.core.json.JsonJs
import io.github.azukkia.pairdesk.core.json.jsSafeIntegerOrNull
import io.github.azukkia.pairdesk.core.json.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A recent partner as stored: [sealedPrs] is the PRS encrypted by a [SecretBox]. */
data class StoredRecent(
    val id: String,
    val name: String,
    val lastAt: Long,
    val count: Int,
    val sealedPrs: String?,
)

/** A recent partner for the UI (no secret). */
data class RecentPartner(
    val id: String,
    val name: String,
    val lastAt: Long,
    val count: Int,
    val hasPassword: Boolean,
)

/** What to do with the remembered password of a partner (`touchRecent` of the desktop). */
sealed interface PrsUpdate {
    /** Keep the current one (the connection used it). */
    data object Keep : PrsUpdate

    /** Forget it (the user typed a password without "remember"). */
    data object Clear : PrsUpdate

    /** Remember this PRS. */
    class Set(val prs: ByteArray) : PrsUpdate
}

/**
 * Recent partners list (Settings.touchRecent / forgetRecent of the desktop):
 * most recent first, at most [MAX] entries.
 */
object Recents {
    const val MAX = 30

    /**
     * Moves [id] to the top. [name] replaces the stored name unless blank;
     * [sealedPrs] is the new sealed PRS for [PrsUpdate.Set] (already encrypted).
     */
    fun touch(
        list: List<StoredRecent>,
        id: String,
        name: String?,
        update: PrsUpdate,
        sealedPrs: String?,
        now: Long,
    ): List<StoredRecent> {
        val previous = list.firstOrNull { it.id == id }
        val entry = StoredRecent(
            id = id,
            name = name?.takeIf { it.isNotEmpty() } ?: previous?.name ?: "",
            lastAt = now,
            count = (previous?.count ?: 0) + 1,
            sealedPrs = when (update) {
                PrsUpdate.Keep -> previous?.sealedPrs
                PrsUpdate.Clear -> null
                is PrsUpdate.Set -> sealedPrs
            },
        )
        return (listOf(entry) + list.filter { it.id != id }).take(MAX)
    }

    fun forget(list: List<StoredRecent>, id: String): List<StoredRecent> = list.filter { it.id != id }

    fun forgetPassword(list: List<StoredRecent>, id: String): List<StoredRecent> =
        list.map { if (it.id == id) it.copy(sealedPrs = null) else it }

    fun toPublic(list: List<StoredRecent>): List<RecentPartner> =
        list.map { RecentPartner(it.id, it.name, it.lastAt, it.count, it.sealedPrs != null) }

    fun encode(list: List<StoredRecent>): String = JsonJs.stringify(
        JsonArray(
            list.map { r ->
                JsonObject(
                    linkedMapOf<String, JsonElement>(
                        "id" to JsonPrimitive(r.id),
                        "name" to JsonPrimitive(r.name),
                        "lastAt" to JsonPrimitive(r.lastAt),
                        "count" to JsonPrimitive(r.count),
                        "prs" to (r.sealedPrs?.let(::JsonPrimitive) ?: JsonNull),
                    ),
                )
            },
        ),
    )

    /** Parses [text] leniently: invalid entries are dropped, never throws. */
    fun decode(text: String?): List<StoredRecent> {
        if (text.isNullOrEmpty()) return emptyList()
        val array = try {
            JsonJs.parse(text) as? JsonArray
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return array.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id")?.takeIf(Protocol::isValidId) ?: return@mapNotNull null
            StoredRecent(
                id = id,
                name = o.str("name")?.take(64) ?: "",
                lastAt = o["lastAt"].jsSafeIntegerOrNull() ?: 0L,
                count = (o["count"].jsSafeIntegerOrNull() ?: 0L).toInt().coerceAtLeast(0),
                sealedPrs = o.str("prs"),
            )
        }.distinctBy { it.id }.take(MAX)
    }
}
