package io.github.azukkia.pairdesk.core

import io.github.azukkia.pairdesk.core.crypto.Hex
import io.github.azukkia.pairdesk.core.json.JsonJs
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Files of the repository used by the tests (paths set by core/build.gradle.kts). */
object TestFiles {
    /** Root of the PairDesk repository (desktop sources, Node interop peer). */
    val repoRoot: File = (System.getProperty("pairdesk.repoRoot")?.let(::File) ?: File("../..")).canonicalFile

    /** Test vectors produced by the JavaScript implementation (scripts/protocol-vectors.mjs). */
    val vectorsFile: File = System.getProperty("pairdesk.vectors")?.let(::File) ?: File(repoRoot, "android/protocol-vectors.json")

    val vectors: JsonObject by lazy { JsonJs.parse(vectorsFile.readText()) as JsonObject }

    fun vectorList(name: String): List<JsonObject> = (vectors[name] as JsonArray).map { it as JsonObject }
}

fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.content ?: error("missing $key")

fun JsonObject.bytes(key: String): ByteArray = Hex.decode(text(key))

fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: error("missing object $key")

/** Polls [condition] until it holds (no fixed sleeps: CI machines can be slow). */
suspend fun waitUntil(timeoutMs: Long = 10_000, message: String = "condition", condition: () -> Boolean) {
    withTimeout(timeoutMs) {
        while (!condition()) delay(5)
    }
    check(condition()) { message }
}
