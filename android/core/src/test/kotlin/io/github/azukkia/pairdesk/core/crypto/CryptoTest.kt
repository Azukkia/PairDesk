package io.github.azukkia.pairdesk.core.crypto

import io.github.azukkia.pairdesk.core.json.JsonJs
import io.github.azukkia.pairdesk.core.signaling.Signaling.Companion.obj
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class CryptoTest {
    private fun hex(b: ByteArray) = Hex.encode(b)

    @Test
    fun `lv encodes lengths so that concatenations are unambiguous`() {
        assertEquals("000000026162" + "0000000163", hex(lv("ab", "c")))
        assertNotEquals(hex(lv("ab", "c")), hex(lv("a", "bc")))
        assertEquals("00000000" + "00000002c3a9", hex(lv("", "é")))
        assertEquals("00000003010203", hex(lv(byteArrayOf(1, 2, 3))))
    }

    @Test
    fun `base64url decoding behaves like Node's Buffer_from(s, 'base64url')`() {
        // Expected values produced with Node 22.
        val cases = mapOf(
            "rOCH" to "ace087",
            "rO!CH" to "ace087",
            "rO CH" to "ace087",
            "rO\nCH" to "ace087",
            "rO=CH" to "ac",
            "rOC" to "ace0",
            "rOCHm" to "ace087",
            "rO+/" to "acefbf",
            "rO-_" to "acefbf",
            "rOCH==" to "ace087",
            "r" to "",
            "" to "",
            "ab=cd=ef" to "69",
            "rOCHmA==xyz" to "ace08798",
            "rOC=H" to "ace0",
        )
        for ((input, expected) in cases) assertEquals(expected, hex(B64u.decode(input)), "decode(\"$input\")")
    }

    @Test
    fun `base64url encoding has no padding and round-trips`() {
        val rnd = java.util.Random(42)
        for (n in 0..70) {
            val bytes = ByteArray(n).also(rnd::nextBytes)
            val text = B64u.encode(bytes)
            assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), text)
            assertFalse(text.contains('='))
            assertArrayEquals(bytes, B64u.decode(text))
        }
    }

    @Test
    fun `HKDF-SHA256 matches RFC 5869 test case 1`() {
        val okm = hkdfSha256(
            ikm = ByteArray(22) { 0x0b },
            salt = Hex.decode("000102030405060708090a0b0c"),
            info = Hex.decode("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", hex(okm))
    }

    @Test
    fun `PBKDF2-HMAC-SHA256 matches RFC 7914 and the JDK`() {
        val dk = pbkdf2HmacSha256("passwd".toByteArray(), "salt".toByteArray(), 1, 64)
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            hex(dk),
        )
        val jdk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec("abc234".toCharArray(), "PairDesk-PRS-v1|987654321".toByteArray(), 1000, 256)).encoded
        assertArrayEquals(jdk, pbkdf2HmacSha256("abc234".toByteArray(), "PairDesk-PRS-v1|987654321".toByteArray(), 1000, 32))
    }

    @Test
    fun `PRS ignores surrounding whitespace and Unicode normalization form, not case`() {
        val iterations = 1000
        val base = Prs.derive("Mot de passé", "123456789", iterations)
        assertArrayEquals(base, Prs.derive("  Mot de passé\n", "123456789", iterations))
        assertArrayEquals(base, Prs.derive(" Mot de passé　", "123456789", iterations))
        assertFalse(base.contentEquals(Prs.derive("mot de passé", "123456789", iterations)))
        assertFalse(base.contentEquals(Prs.derive("Mot de passé", "123456780", iterations)))
        assertEquals(32, base.size)
    }

    @Test
    fun `CPace - same password gives the same secret, different passwords do not`() {
        val prs = Rng.bytes(32)
        val ci = CPace.channelIdentifier("123456789", "987654321")
        val sid = "test-session-1234"
        val a = CPace.share(prs, ci, sid)
        val b = CPace.share(prs, ci, sid)
        assertArrayEquals(CPace.secret(a.scalar, b.share), CPace.secret(b.scalar, a.share))
        val c = CPace.share(Rng.bytes(32), ci, sid)
        assertFalse(CPace.secret(a.scalar, c.share).contentEquals(CPace.secret(c.scalar, a.share)))
        // Another session id or channel gives another generator.
        assertFalse(CPace.generator(prs, ci, sid) == CPace.generator(prs, ci, "$sid-2"))
    }

    @Test
    fun `CPace - invalid, non-canonical or identity shares are rejected`() {
        val a = CPace.share(Rng.bytes(32), CPace.channelIdentifier("123456789", "987654321"), "test-session-1234")
        assertThrows(CPaceException::class.java) { CPace.secret(a.scalar, null) }
        assertThrows(CPaceException::class.java) { CPace.secret(a.scalar, ByteArray(31)) }
        assertThrows(CPaceException::class.java) { CPace.secret(a.scalar, ByteArray(33)) }
        assertThrows(CPaceException::class.java) { CPace.secret(a.scalar, ByteArray(32)) } // identity
        assertThrows(CPaceException::class.java) { CPace.secret(a.scalar, ByteArray(32) { 0xff.toByte() }) }
        // A canonical encoding with the sign bit set is not a valid ristretto255 point.
        val negative = a.share.copyOf().also { it[0] = (it[0].toInt() or 1).toByte() }
        if (!negative.contentEquals(a.share)) assertThrows(CPaceException::class.java) { CPace.secret(a.scalar, negative) }
    }

    @Test
    fun `random scalars are reduced little-endian 64-byte values`() {
        repeat(20) {
            val s = CPace.randomScalar()
            assertEquals(32, s.toByteArray().size)
            assertTrue(s.toByteArray().any { it.toInt() != 0 })
        }
        // Big-endian hex of the vectors → canonical little-endian scalar bytes.
        val s = CPace.scalarFromHexBigEndian("0ebb36bb286b35aaca1854fc44df7303b7c241914eab2fcadd1148e313f25a5f")
        assertEquals("5f5af213e34811ddca2fab4e9141c2b70373df44fc5418caaa356b28bb36bb0e", hex(s.toByteArray()))
    }

    @Test
    fun `confirmation tags differ per role and per transcript`() {
        val keys = CPace.deriveSessionKeys("session-abcdef", "123456789", "987654321", Rng.bytes(32), Rng.bytes(32), Rng.bytes(32))
        assertFalse(CPace.hostTag(keys).contentEquals(CPace.ctrlTag(keys)))
        assertEquals(32, CPace.hostTag(keys).size)
        assertTrue(constantTimeEquals(CPace.hostTag(keys), CPace.hostTag(keys)))
        assertFalse(constantTimeEquals(CPace.hostTag(keys), CPace.hostTag(keys).copyOf(31)))
        assertFalse(constantTimeEquals(CPace.hostTag(keys), null))
    }

    @Test
    fun `nonce layout is uint32 direction, two zero bytes, uint48 counter`() {
        assertEquals("000000010000000000000001", hex(SecureChannel.nonce(1, 1)))
        assertEquals("000000020000010203040506", hex(SecureChannel.nonce(2, 0x010203040506L)))
    }

    private fun channels(sid: String = "channel-session-1"): Pair<SecureChannel, SecureChannel> {
        val keys = CPace.deriveSessionKeys(sid, "123456789", "987654321", Rng.bytes(32), Rng.bytes(32), Rng.bytes(32))
        return SecureChannel.forHost(keys, sid) to SecureChannel.forController(keys, sid)
    }

    @Test
    fun `SecureChannel round-trips both ways with increasing counters`() {
        val (host, ctrl) = channels()
        val m1 = ctrl.seal(obj("type" to "signal", "data" to obj("sdp" to "v=0 é😀")))
        val m2 = ctrl.seal(obj("type" to "chat"))
        assertEquals(1, m1.n)
        assertEquals(2, m2.n)
        // Out of order delivery is fine, replays are not.
        assertEquals("chat", (host.open(m2.toJson()) as JsonObject)["type"]!!.let { (it as JsonPrimitive).content })
        assertEquals(obj("type" to "signal", "data" to obj("sdp" to "v=0 é😀")), host.open(m1.toJson()))
        assertThrows(ChannelException::class.java) { host.open(m1.toJson()) }
        val back = host.seal(obj("type" to "accepted"))
        assertEquals(1, back.n)
        assertEquals(obj("type" to "accepted"), ctrl.open(back.toJson()))
    }

    @Test
    fun `SecureChannel rejects tampering, wrong direction, wrong session and bad counters`() {
        val (host, ctrl) = channels()
        val sealed = ctrl.seal(obj("type" to "x"))
        val body = B64u.decode(sealed.ct)
        body[3] = (body[3].toInt() xor 1).toByte()
        assertThrows(ChannelException::class.java) { host.open(Sealed(sealed.n, B64u.encode(body)).toJson()) }
        // Same counter but read as the other direction.
        assertThrows(ChannelException::class.java) { ctrl.open(sealed.toJson()) }
        // Another counter than the one used to seal.
        assertThrows(ChannelException::class.java) { host.open(Sealed(sealed.n + 1, sealed.ct).toJson()) }
        // Same keys but another sid (AAD).
        val keys = CPace.deriveSessionKeys("s1-session", "123456789", "987654321", ByteArray(32), ByteArray(32), ByteArray(32))
        val other = SecureChannel.forController(keys, "s1-session").seal(obj("type" to "x"))
        assertThrows(ChannelException::class.java) { SecureChannel.forHost(keys, "s2-session").open(other.toJson()) }
        // Counter validation (Number.isSafeInteger, 0 < n < 2^48).
        for (n in listOf("0", "-1", "1.5", "\"1\"", "281474976710656", "null", "true")) {
            val msg = JsonJs.parse("""{"n":$n,"ct":"${sealed.ct}"}""") as JsonObject
            assertThrows(ChannelException::class.java, { host.open(msg) }, "n=$n")
        }
        assertThrows(ChannelException::class.java) { host.open(JsonJs.parse("""{"n":5,"ct":"AAAA"}""") as JsonObject) }
        assertThrows(ChannelException::class.java) { host.open(JsonJs.parse("""{"n":6}""") as JsonObject) }
        assertThrows(ChannelException::class.java) { host.open(JsonJs.parse("""{"n":7,"ct":12345}""") as JsonObject) }
        // The genuine message still opens afterwards (failed attempts are not marked as seen).
        assertEquals(obj("type" to "x"), host.open(sealed.toJson()))
    }
}
