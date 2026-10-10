package io.github.azukkia.pairdesk.core.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * JSON helpers with JavaScript semantics, so that validation matches the
 * desktop code exactly (`typeof x === 'string'`, `Number.isInteger(x)`…).
 *
 * Serialization uses kotlinx.serialization's [JsonElement] tree: objects keep
 * their insertion order and [JsonElement.toString] produces compact JSON like
 * `JSON.stringify` (same escaping of strings, no spaces).
 */
object JsonJs {
    private val json = Json { isLenient = false }

    val EMPTY: JsonObject = JsonObject(emptyMap())

    /** `JSON.parse`; throws on invalid input. */
    fun parse(text: String): JsonElement = json.parseToJsonElement(text)

    /** Parses [text] and returns it when it is a JSON object, null otherwise (never throws). */
    fun parseObjectOrNull(text: String): JsonObject? = try {
        parse(text) as? JsonObject
    } catch (_: Exception) {
        null
    }

    /** `JSON.stringify` for a JSON tree. */
    fun stringify(element: JsonElement): String = element.toString()

    /** `{...a, ...b}`: keys of [b] override those of [a], keeping their first position. */
    fun merge(a: JsonObject, b: JsonObject): JsonObject {
        val map = LinkedHashMap<String, JsonElement>(a)
        for ((k, v) in b) map[k] = v
        return JsonObject(map)
    }
}

private const val MAX_SAFE_INTEGER = 9007199254740991.0

/** `typeof x === 'string' ? x : null`. */
fun JsonElement?.jsStringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** True when this is a JSON number (`typeof x === 'number'`). */
fun JsonElement?.isJsNumber(): Boolean {
    val p = this as? JsonPrimitive ?: return false
    if (p.isString || p is JsonNull) return false
    if (p.booleanOrNull != null) return false
    return p.content.toDoubleOrNull() != null
}

/** The number as a Double when `typeof x === 'number'`, null otherwise. */
fun JsonElement?.jsNumberOrNull(): Double? = if (isJsNumber()) (this as JsonPrimitive).content.toDouble() else null

/** `Number.isSafeInteger(x) ? x : null`. */
fun JsonElement?.jsSafeIntegerOrNull(): Long? {
    val p = this as? JsonPrimitive ?: return null
    if (!isJsNumber()) return null
    p.content.toLongOrNull()?.let { return if (kotlin.math.abs(it.toDouble()) <= MAX_SAFE_INTEGER) it else null }
    val d = p.content.toDouble()
    if (!d.isFinite() || d != kotlin.math.floor(d) || kotlin.math.abs(d) > MAX_SAFE_INTEGER) return null
    return d.toLong()
}

/** JavaScript truthiness of a JSON value (undefined/null/false/0/NaN/"" are falsy). */
fun JsonElement?.jsTruthy(): Boolean = when (this) {
    null, JsonNull -> false
    is JsonObject, is JsonArray -> true
    is JsonPrimitive -> when {
        isString -> content.isNotEmpty()
        booleanOrNull != null -> booleanOrNull!!
        else -> content.toDoubleOrNull()?.let { it != 0.0 && !it.isNaN() } ?: false
    }
}

/** `String(x || fallback)` for reason fields. */
fun JsonElement?.jsReason(fallback: String): String {
    if (!jsTruthy()) return fallback
    return when (val v = this) {
        is JsonPrimitive -> v.content
        is JsonObject -> "[object Object]"
        is JsonArray -> v.joinToString(",") { e -> if (e is JsonPrimitive && e !is JsonNull) e.content else if (e is JsonNull) "" else e.toString() }
        else -> fallback
    }
}

/** Shortcut for `obj[key]` as a string. */
fun JsonObject.str(key: String): String? = this[key].jsStringOrNull()
