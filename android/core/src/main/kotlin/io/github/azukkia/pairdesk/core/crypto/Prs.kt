package io.github.azukkia.pairdesk.core.crypto

import io.github.azukkia.pairdesk.core.Protocol

/**
 * Password-related string (PRS): both peers derive the same 32 bytes from the
 * password and the host ID, so the host never stores the password itself.
 *
 * `PBKDF2-HMAC-SHA256(NFC(trim(password)), "PairDesk-PRS-v1|" + hostId, 200000, 32)`
 * (src/main/crypto/prs.js). Implemented on top of [javax.crypto.Mac] so that the
 * password is always encoded as UTF-8, whatever the platform's PBKDF2 provider does.
 * Slow by design (a few hundred milliseconds): call it off the main thread.
 */
object Prs {
    const val ITERATIONS = 200_000
    const val LENGTH = 32
    const val SALT_PREFIX = "PairDesk-PRS-v1|"

    fun derive(password: String, hostId: String, iterations: Int = ITERATIONS): ByteArray =
        pbkdf2HmacSha256(utf8(Protocol.normalizePassword(password)), utf8(SALT_PREFIX + hostId), iterations, LENGTH)
}

/** PBKDF2 (RFC 8018) with HMAC-SHA256. */
fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
    require(iterations >= 1) { "iterations must be positive" }
    require(length >= 1) { "length must be positive" }
    val mac = hmacSha256(password)
    val hLen = mac.macLength
    val blocks = (length + hLen - 1) / hLen
    val out = ByteArray(length)
    val u = ByteArray(hLen)
    val t = ByteArray(hLen)
    for (block in 1..blocks) {
        mac.update(salt)
        mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
        mac.doFinal(u, 0)
        System.arraycopy(u, 0, t, 0, hLen)
        for (i in 1 until iterations) {
            mac.update(u)
            mac.doFinal(u, 0)
            for (j in 0 until hLen) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
        }
        val off = (block - 1) * hLen
        System.arraycopy(t, 0, out, off, minOf(hLen, length - off))
    }
    return out
}
