package io.github.azukkia.pairdesk.core.crypto

import io.github.azukkia.pairdesk.core.json.JsonJs
import io.github.azukkia.pairdesk.core.json.jsSafeIntegerOrNull
import io.github.azukkia.pairdesk.core.json.jsStringOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A sealed message: `{n, ct}` (the `t:'sec'` envelope adds `t` and `sid`). */
data class Sealed(val n: Long, val ct: String) {
    fun toJson(): JsonObject = JsonObject(linkedMapOf("n" to JsonPrimitive(n), "ct" to JsonPrimitive(ct)))
}

class ChannelException(message: String) : Exception(message)

/**
 * Authenticated encryption of signaling messages once the session keys exist
 * (src/main/crypto/channel.js): AES-256-GCM, one key per direction,
 * nonce = uint32_be(direction) ‖ 0x0000 ‖ uint48_be(counter),
 * AAD = "PairDesk-v1|" + sid, plaintext = UTF-8 JSON.
 *
 * @param sendDir 1 (host → controller) or 2 (controller → host)
 */
class SecureChannel(
    private val sendKey: ByteArray,
    private val recvKey: ByteArray,
    private val sendDir: Int,
    sid: String,
) {
    private val recvDir = if (sendDir == 1) 2 else 1
    private val aad = utf8("PairDesk-v1|$sid")
    private var counter = 0L
    private val seen = HashSet<Long>()

    init {
        require(sendDir == 1 || sendDir == 2) { "sendDir must be 1 or 2" }
        require(sendKey.size == 32 && recvKey.size == 32) { "AES-256 keys expected" }
    }

    @Synchronized
    fun seal(message: JsonElement): Sealed {
        val n = ++counter
        if (n >= MAX_COUNTER) throw ChannelException("counter exhausted")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey, "AES"), GCMParameterSpec(128, nonce(sendDir, n)))
        cipher.updateAAD(aad)
        val body = cipher.doFinal(utf8(JsonJs.stringify(message)))
        return Sealed(n, B64u.encode(body))
    }

    /** Opens `{n, ct}` (extra fields such as `t` and `sid` are ignored). */
    fun open(sealed: JsonObject): JsonElement = open(sealed["n"], sealed["ct"])

    /** Decrypts and parses a message; throws [ChannelException] when it is invalid or replayed. */
    @Synchronized
    fun open(nValue: JsonElement?, ctValue: JsonElement?): JsonElement {
        val n = nValue.jsSafeIntegerOrNull()
        if (n == null || n <= 0 || n >= MAX_COUNTER) throw ChannelException("bad counter")
        if (n in seen) throw ChannelException("replayed message")
        // Buffer.from(String(ct), 'base64url'): anything but a string decodes to garbage.
        val ctText = ctValue.jsStringOrNull() ?: ctValue?.toString() ?: "undefined"
        val body = B64u.decode(ctText)
        if (body.size < 17) throw ChannelException("truncated message")
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(recvKey, "AES"), GCMParameterSpec(128, nonce(recvDir, n)))
            cipher.updateAAD(aad)
            cipher.doFinal(body)
        } catch (e: java.security.GeneralSecurityException) {
            throw ChannelException("authentication failed")
        }
        seen.add(n)
        return try {
            JsonJs.parse(String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            throw ChannelException("invalid JSON")
        }
    }

    companion object {
        const val MAX_COUNTER = 1L shl 48

        fun nonce(direction: Int, counter: Long): ByteArray {
            val iv = ByteArray(12)
            iv[0] = (direction ushr 24).toByte()
            iv[1] = (direction ushr 16).toByte()
            iv[2] = (direction ushr 8).toByte()
            iv[3] = direction.toByte()
            for (i in 0 until 6) iv[11 - i] = (counter ushr (8 * i)).toByte()
            return iv
        }

        /** Host side channel of a session. */
        fun forHost(keys: SessionKeys, sid: String) = SecureChannel(keys.encHostToCtrl, keys.encCtrlToHost, 1, sid)

        /** Controller side channel of a session. */
        fun forController(keys: SessionKeys, sid: String) = SecureChannel(keys.encCtrlToHost, keys.encHostToCtrl, 2, sid)
    }
}
