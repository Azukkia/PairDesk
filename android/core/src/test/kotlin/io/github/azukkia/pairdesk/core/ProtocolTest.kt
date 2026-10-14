package io.github.azukkia.pairdesk.core

import io.github.azukkia.pairdesk.core.json.JsonJs
import io.github.azukkia.pairdesk.core.signaling.AuthLimiter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ProtocolTest {
    @Test
    fun `device ids are 9 digits not starting with 0`() {
        repeat(2000) {
            val id = Protocol.generateDeviceId()
            assertTrue(Protocol.isValidId(id), id)
        }
        assertTrue(Protocol.isValidId("123456789"))
        for (bad in listOf(null, "", "012345678", "12345678", "1234567890", "12345678a", "١٢٣٤٥٦٧٨٩", " 123456789")) {
            assertFalse(Protocol.isValidId(bad), "$bad")
        }
    }

    @Test
    fun `ids typed with spaces or dashes are normalized and formatted`() {
        assertEquals("123456789", Protocol.normalizeId(" 123 456-789 "))
        assertEquals("", Protocol.normalizeId(null))
        assertEquals("123 456 789", Protocol.formatId("123456789"))
        assertEquals("123 45", Protocol.formatId("12345"))
        assertEquals("123 456 789 0", Protocol.formatId("1234567890"))
    }

    @Test
    fun `temporary passwords use the readable alphabet`() {
        repeat(500) {
            val pw = Protocol.generatePassword()
            assertEquals(6, pw.length)
            assertTrue(pw.all { it in Protocol.PASSWORD_ALPHABET }, pw)
        }
        assertEquals(10, Protocol.generatePassword(10).length)
        assertFalse(Protocol.PASSWORD_ALPHABET.any { it in "0o1li" })
    }

    @Test
    fun `password normalization is NFC plus JavaScript trim`() {
        assertEquals("abc", Protocol.normalizePassword(" \t\nabc\r\n "))
        assertEquals("é", Protocol.normalizePassword("é"))
        // ECMAScript WhiteSpace and LineTerminator code points are trimmed…
        assertEquals("pw", Protocol.normalizePassword(" ﻿  　    \u000b\u000cpw "))
        // …but not the zero width space (a format character for JavaScript too).
        assertEquals("​pw", Protocol.normalizePassword("​pw"))
        assertEquals("a b", Protocol.normalizePassword(" a b "))
        assertEquals("", Protocol.normalizePassword(null))
    }

    @Test
    fun `versionAtLeast compares the first three numbers`() {
        assertTrue(Protocol.versionAtLeast("1.2.0", "1.2"))
        assertTrue(Protocol.versionAtLeast("1.10.0", "1.2.0"))
        assertTrue(Protocol.versionAtLeast("2", "1.9.9"))
        assertTrue(Protocol.versionAtLeast("1.2.0-beta.1", "1.2.0"))
        assertFalse(Protocol.versionAtLeast("1.1.9", "1.2.0"))
        assertFalse(Protocol.versionAtLeast(null, "1.0.0"))
        assertFalse(Protocol.versionAtLeast("", "1.0.0"))
    }

    @Test
    fun `defaults match pairdesk_config_json of the desktop app`() {
        val config = JsonJs.parse(File(TestFiles.repoRoot, "pairdesk.config.json").readText()) as JsonObject
        val brokers = (config["publicBrokers"] as JsonArray).map { it.jsonPrimitive.content }
        assertEquals(brokers, PairDeskDefaults.PUBLIC_BROKERS)
        val ice = (config["iceServers"] as JsonArray).map { server ->
            when (val urls = (server as JsonObject)["urls"]) {
                is JsonArray -> urls.map { it.jsonPrimitive.content }
                is JsonPrimitive -> listOf(urls.content)
                else -> emptyList()
            }
        }
        assertEquals(ice, PairDeskDefaults.ICE_SERVERS)
    }

    @Test
    fun `protocol version matches the desktop`() {
        val shared = File(TestFiles.repoRoot, "src/shared/protocol.js").readText()
        val version = Regex("""PROTOCOL_VERSION\s*=\s*(\d+)""").find(shared)!!.groupValues[1].toInt()
        assertEquals(version, Protocol.PROTOCOL_VERSION)
        val alphabet = Regex("""PASSWORD_ALPHABET\s*=\s*'([^']+)'""").find(shared)!!.groupValues[1]
        assertEquals(alphabet, Protocol.PASSWORD_ALPHABET)
    }
}

class AuthLimiterTest {
    private var now = 1_000_000L
    private val limiter = AuthLimiter(now = { now })

    @Test
    fun `five failures lock for 30 s, doubling up to 30 min`() {
        repeat(4) { limiter.recordFailure() }
        assertEquals(0, limiter.lockedFor())
        limiter.recordFailure()
        assertEquals(30_000, limiter.lockedFor())
        now += 10_000
        assertEquals(20_000, limiter.lockedFor())
        limiter.recordFailure()
        assertEquals(60_000, limiter.lockedFor())
        repeat(10) { limiter.recordFailure() }
        assertEquals(30 * 60_000L, limiter.lockedFor())
        assertEquals(16, limiter.recentFailures)
        assertEquals(30, limiter.retryInSeconds(29_001))
    }

    @Test
    fun `failures expire after the window and success resets`() {
        repeat(5) { limiter.recordFailure() }
        now += 30 * 60_000L
        assertEquals(0, limiter.recentFailures)
        assertEquals(0, limiter.lockedFor())
        repeat(5) { limiter.recordFailure() }
        assertTrue(limiter.lockedFor() > 0)
        limiter.recordSuccess()
        assertEquals(0, limiter.lockedFor())
        assertEquals(0, limiter.recentFailures)
    }
}
