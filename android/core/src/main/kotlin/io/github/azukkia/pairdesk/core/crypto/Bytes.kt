package io.github.azukkia.pairdesk.core.crypto

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** base64url without padding (Node's `Buffer.toString('base64url')`). */
object B64u {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /**
     * Decodes like Node's `Buffer.from(text, 'base64url')`, so that peers
     * validate exactly the same inputs: both alphabets are accepted, invalid
     * characters (whitespace…) are skipped, decoding stops at the first `=`
     * and incomplete trailing bits are dropped. Never throws.
     */
    fun decode(text: String): ByteArray {
        val out = ByteArrayOutputStream(text.length * 3 / 4 + 1)
        var acc = 0
        var bits = 0
        for (c in text) {
            if (c == '=') break
            val v = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '-', '+' -> 62
                '_', '/' -> 63
                else -> -1
            }
            if (v < 0) continue
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc ushr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }
}

object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(DIGITS[(b.toInt() shr 4) and 0xf]).append(DIGITS[b.toInt() and 0xf])
        }
        return sb.toString()
    }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[2 * i], 16) shl 4) or Character.digit(hex[2 * i + 1], 16)).toByte()
        }
    }
}

internal fun utf8(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

private fun partBytes(part: Any): ByteArray = when (part) {
    is ByteArray -> part
    is String -> utf8(part)
    else -> throw IllegalArgumentException("expected String or ByteArray")
}

/** Length-prefixed concatenation: for each part, 4-byte big-endian length then the bytes (strings are UTF-8). */
fun lv(vararg parts: Any): ByteArray {
    val out = ByteArrayOutputStream()
    for (part in parts) {
        val b = partBytes(part)
        out.write(b.size ushr 24)
        out.write(b.size ushr 16)
        out.write(b.size ushr 8)
        out.write(b.size)
        out.write(b)
    }
    return out.toByteArray()
}

/** Constant-time comparison (time depends only on the lengths). */
fun constantTimeEquals(a: ByteArray?, b: ByteArray?): Boolean {
    if (a == null || b == null || a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}

fun sha256(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    for (p in parts) md.update(p)
    return md.digest()
}

fun sha512(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-512")
    for (p in parts) md.update(p)
    return md.digest()
}

/** HMAC-SHA256 instance keyed with [key] (an empty key is valid HMAC, unlike SecretKeySpec). */
internal fun hmacSha256(key: ByteArray): Mac {
    val mac = Mac.getInstance("HmacSHA256")
    // HMAC pads the key with zeros to the block size: an empty key equals a single zero byte.
    mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, "HmacSHA256"))
    return mac
}

fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = hmacSha256(key).doFinal(data)

/** HKDF-SHA256 (RFC 5869), like Node's `hkdfSync('sha256', ikm, salt, info, length)`. */
fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    require(length in 0..255 * 32) { "invalid HKDF length" }
    val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
    val mac = hmacSha256(prk)
    val out = ByteArray(length)
    var t = ByteArray(0)
    var off = 0
    var counter = 1
    while (off < length) {
        mac.update(t)
        mac.update(info)
        mac.update(counter.toByte())
        t = mac.doFinal()
        val n = minOf(t.size, length - off)
        System.arraycopy(t, 0, out, off, n)
        off += n
        counter++
    }
    return out
}

/** Process-wide CSPRNG. */
object Rng {
    val secure: SecureRandom = SecureRandom()

    fun bytes(n: Int, rnd: SecureRandom = secure): ByteArray = ByteArray(n).also { rnd.nextBytes(it) }

    /** `randomBytes(n).toString('base64url')`. */
    fun b64u(n: Int, rnd: SecureRandom = secure): String = B64u.encode(bytes(n, rnd))

    /** `randomBytes(n).toString('hex')`. */
    fun hex(n: Int, rnd: SecureRandom = secure): String = Hex.encode(bytes(n, rnd))
}
