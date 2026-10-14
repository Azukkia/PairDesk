package io.github.azukkia.pairdesk.core

import java.security.SecureRandom
import java.text.Normalizer

/**
 * Constants and helpers shared by every part of the protocol.
 * Mirrors src/shared/protocol.js of the desktop application.
 */
object Protocol {
    const val APP_NAME = "PairDesk"

    /** Bumped when the signaling / session protocol changes incompatibly. */
    const val PROTOCOL_VERSION = 1

    /** Readable alphabet for generated passwords: no 0/o/1/l/i. */
    const val PASSWORD_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

    const val DEFAULT_PASSWORD_LENGTH = 6
    const val MIN_PERMANENT_PASSWORD_LENGTH = 8

    /** Platform reported in intro / accepted messages by the Android app. */
    const val PLATFORM_ANDROID = "android"

    private val ID_REGEX = Regex("^[1-9][0-9]{8}$")
    private val random = SecureRandom()

    /** Generates a 9-digit device ID that never starts with 0. */
    fun generateDeviceId(rnd: SecureRandom = random): String {
        val sb = StringBuilder(9)
        sb.append(1 + rnd.nextInt(9))
        repeat(8) { sb.append(rnd.nextInt(10)) }
        return sb.toString()
    }

    /** Generates a temporary password from [PASSWORD_ALPHABET]. */
    fun generatePassword(length: Int = DEFAULT_PASSWORD_LENGTH, rnd: SecureRandom = random): String {
        val sb = StringBuilder(length)
        repeat(length) { sb.append(PASSWORD_ALPHABET[rnd.nextInt(PASSWORD_ALPHABET.length)]) }
        return sb.toString()
    }

    /** Device key proving ownership of the ID to a private PairDesk server (32 random bytes, b64u). */
    fun generateDeviceKey(rnd: SecureRandom = random): String {
        val key = ByteArray(32)
        rnd.nextBytes(key)
        return io.github.azukkia.pairdesk.core.crypto.B64u.encode(key)
    }

    /** Keeps only ASCII digits: users may type "123 456 789" or "123-456-789". */
    fun normalizeId(input: String?): String = (input ?: "").filter { it in '0'..'9' }

    fun isValidId(id: String?): Boolean = id != null && ID_REGEX.matches(id)

    /** "123456789" -> "123 456 789". */
    fun formatId(id: String?): String = normalizeId(id).chunked(3).joinToString(" ")

    /**
     * Passwords are compared exactly, apart from surrounding whitespace:
     * `String(password).normalize('NFC').trim()` in JavaScript.
     */
    fun normalizePassword(password: String?): String =
        jsTrim(Normalizer.normalize(password ?: "", Normalizer.Form.NFC))

    /** `String.prototype.trim()`: strips ECMAScript WhiteSpace and LineTerminator code points. */
    fun jsTrim(s: String): String {
        var start = 0
        var end = s.length
        while (start < end && isJsWhitespace(s[start])) start++
        while (end > start && isJsWhitespace(s[end - 1])) end--
        return s.substring(start, end)
    }

    private fun isJsWhitespace(c: Char): Boolean = when (c) {
        '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u0020', '\u00A0', '\u2028', '\u2029', '\uFEFF' -> true
        else -> Character.getType(c) == Character.SPACE_SEPARATOR.toInt()
    }

    /** True when [version] ("1.2.3") is at least [min]; false when unknown. */
    fun versionAtLeast(version: String?, min: String): Boolean {
        if (version.isNullOrEmpty()) return false
        fun parse(v: String) = v.split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val a = parse(version)
        val b = parse(min)
        for (i in 0 until 3) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return true
    }
}

/**
 * Defaults of pairdesk.config.json (public MQTT relays and STUN servers).
 * A unit test checks that they match the desktop configuration.
 */
object PairDeskDefaults {
    val PUBLIC_BROKERS: List<String> = listOf(
        "wss://broker.emqx.io:8084/mqtt",
        "wss://broker.hivemq.com:8884/mqtt",
        "wss://test.mosquitto.org:8081/mqtt",
        "wss://mqtt.eclipseprojects.io:443/mqtt",
    )

    /** ICE servers, as lists of URLs (one entry per RTCIceServer). */
    val ICE_SERVERS: List<List<String>> = listOf(
        listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"),
        listOf("stun:stun.cloudflare.com:3478"),
    )

    const val MQTT_TOPIC_PREFIX = "pairdesk/v1/"
}
