package io.github.azukkia.pairdesk.core.crypto

import cafe.cryptography.curve25519.CompressedRistretto
import cafe.cryptography.curve25519.InvalidEncodingException
import cafe.cryptography.curve25519.RistrettoElement
import cafe.cryptography.curve25519.Scalar
import java.security.SecureRandom

/** hash_to_ristretto255 of RFC 9380 (expand_message_xmd with SHA-512, one-way map of RFC 9496). */
object HashToRistretto {
    private const val B_IN_BYTES = 64 // SHA-512 output
    private const val S_IN_BYTES = 128 // SHA-512 block size

    /** expand_message_xmd(msg, DST, lenInBytes) with SHA-512 (RFC 9380 section 5.3.1). */
    fun expandMessageXmdSha512(msg: ByteArray, dst: ByteArray, lenInBytes: Int): ByteArray {
        val dstEff = if (dst.size > 255) sha512(utf8("H2C-OVERSIZE-DST-"), dst) else dst
        val ell = (lenInBytes + B_IN_BYTES - 1) / B_IN_BYTES
        require(ell <= 255 && lenInBytes <= 65535 && lenInBytes > 0) { "invalid expand_message_xmd length" }
        val dstPrime = dstEff + byteArrayOf(dstEff.size.toByte())
        val zPad = ByteArray(S_IN_BYTES)
        val libStr = byteArrayOf((lenInBytes ushr 8).toByte(), lenInBytes.toByte())
        val b0 = sha512(zPad, msg, libStr, byteArrayOf(0), dstPrime)
        val out = ByteArray(lenInBytes)
        var prev = sha512(b0, byteArrayOf(1), dstPrime)
        System.arraycopy(prev, 0, out, 0, minOf(B_IN_BYTES, lenInBytes))
        for (i in 2..ell) {
            val x = ByteArray(B_IN_BYTES) { j -> (b0[j].toInt() xor prev[j].toInt()).toByte() }
            prev = sha512(x, byteArrayOf(i.toByte()), dstPrime)
            val off = (i - 1) * B_IN_BYTES
            System.arraycopy(prev, 0, out, off, minOf(B_IN_BYTES, lenInBytes - off))
        }
        return out
    }

    fun hashToRistretto255(msg: ByteArray, dst: String): RistrettoElement =
        RistrettoElement.fromUniformBytes(expandMessageXmdSha512(msg, utf8(dst), 64))
}

class CPaceException(message: String) : Exception(message)

/** The keys of a session (see deriveSessionKeys in src/main/crypto/cpace.js). */
class SessionKeys(
    val transcript: ByteArray,
    val tagKeyHost: ByteArray,
    val tagKeyCtrl: ByteArray,
    val encHostToCtrl: ByteArray,
    val encCtrlToHost: ByteArray,
)

/** Our secret scalar and the public share to send to the peer (32-byte ristretto255 encoding). */
class CPaceShare(val scalar: Scalar, val share: ByteArray)

/**
 * CPace balanced PAKE over ristretto255 (src/main/crypto/cpace.js).
 *
 * Both peers derive a password-dependent generator G = H2C(PRS, CI, sid) and
 * exchange Y = y·G; K = y·Y' only matches when both used the same password.
 */
object CPace {
    const val DST = "PairDesk-v1-CPace-ristretto255"
    const val ISK_LABEL = "PairDesk-v1-CPace-ISK"
    const val TRANSCRIPT_LABEL = "PairDesk-v1-transcript"
    const val KEY_SCHEDULE_INFO = "PairDesk v1 key schedule"
    const val HOST_CONFIRM = "host-confirm"
    const val CTRL_CONFIRM = "ctrl-confirm"

    fun channelIdentifier(ctrlId: String, hostId: String): ByteArray = lv("ctrl", ctrlId, "host", hostId)

    fun generatorInput(prs: ByteArray, ci: ByteArray, sid: String): ByteArray = lv(prs, ci, sid)

    fun generator(prs: ByteArray, ci: ByteArray, sid: String): RistrettoElement =
        HashToRistretto.hashToRistretto255(generatorInput(prs, ci, sid), DST)

    /** 64 random bytes read as a little-endian integer, reduced modulo L; never 0. */
    fun randomScalar(rnd: SecureRandom = Rng.secure): Scalar {
        while (true) {
            val s = Scalar.fromBytesModOrderWide(Rng.bytes(64, rnd))
            if (s.ctEquals(Scalar.ZERO) == 0) return s
        }
    }

    /** Scalar from its big-endian hexadecimal representation (test vectors). */
    fun scalarFromHexBigEndian(hex: String): Scalar {
        val be = Hex.decode(hex.padStart(64, '0'))
        require(be.size == 32) { "scalar too large" }
        return Scalar.fromCanonicalBytes(be.reversedArray())
    }

    fun share(prs: ByteArray, ci: ByteArray, sid: String, rnd: SecureRandom = Rng.secure): CPaceShare =
        shareWithScalar(prs, ci, sid, randomScalar(rnd))

    fun shareWithScalar(prs: ByteArray, ci: ByteArray, sid: String, scalar: Scalar): CPaceShare =
        CPaceShare(scalar, generator(prs, ci, sid).multiply(scalar).compress().toByteArray())

    /** K from our scalar and the peer's share; throws [CPaceException] on invalid input. */
    fun secret(scalar: Scalar, peerShare: ByteArray?): ByteArray {
        if (peerShare == null || peerShare.size != 32) throw CPaceException("invalid share length")
        val point = try {
            CompressedRistretto(peerShare).decompress() // rejects non-canonical encodings
        } catch (e: InvalidEncodingException) {
            throw CPaceException("invalid share encoding")
        }
        if (point.ctEquals(RistrettoElement.IDENTITY) == 1) throw CPaceException("identity share")
        val k = point.multiply(scalar)
        if (k.ctEquals(RistrettoElement.IDENTITY) == 1) throw CPaceException("identity secret")
        return k.compress().toByteArray()
    }

    /** ya: controller share, yb: host share. */
    fun deriveSessionKeys(sid: String, ctrlId: String, hostId: String, ya: ByteArray, yb: ByteArray, k: ByteArray): SessionKeys {
        val transcript = lv(TRANSCRIPT_LABEL, sid, ctrlId, hostId, ya, yb)
        val isk = sha512(lv(ISK_LABEL, sid, k, ya, yb))
        val okm = hkdfSha256(isk, utf8(sid), utf8(KEY_SCHEDULE_INFO), 128)
        return SessionKeys(
            transcript = transcript,
            tagKeyHost = okm.copyOfRange(0, 32),
            tagKeyCtrl = okm.copyOfRange(32, 64),
            encHostToCtrl = okm.copyOfRange(64, 96),
            encCtrlToHost = okm.copyOfRange(96, 128),
        )
    }

    fun confirmTag(key: ByteArray, label: String, transcript: ByteArray, extra: String = ""): ByteArray =
        hmacSha256(key, lv(label, transcript, extra))

    fun hostTag(keys: SessionKeys): ByteArray = confirmTag(keys.tagKeyHost, HOST_CONFIRM, keys.transcript)

    fun ctrlTag(keys: SessionKeys): ByteArray = confirmTag(keys.tagKeyCtrl, CTRL_CONFIRM, keys.transcript)
}
