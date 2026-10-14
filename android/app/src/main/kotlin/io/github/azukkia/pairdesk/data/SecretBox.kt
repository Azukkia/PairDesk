package io.github.azukkia.pairdesk.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the secrets kept in the settings (PRS of the permanent password,
 * of the temporary password and of the remembered partners). The desktop
 * uses the OS keychain for the same purpose (safeStorage).
 */
interface SecretBox {
    /** Encrypted, printable form of [plain]; null when encryption is unavailable. */
    fun seal(plain: ByteArray): String?

    /** The plaintext of [sealed], or null when it cannot be decrypted (e.g. key lost). */
    fun open(sealed: String): ByteArray?
}

/**
 * AES-256-GCM with a non-exportable key of the Android Keystore. Sealed form:
 * `"k1:" + base64(iv(12) ‖ ciphertext ‖ tag(16))`. When the key disappears
 * (app data restored on another device, keystore reset) old secrets simply
 * fail to open: the user types the passwords again.
 */
class KeystoreSecretBox(private val alias: String = "pairdesk.secrets.v1") : SecretBox {
    private val lock = Any()

    private fun key(): SecretKey = synchronized(lock) {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generator.generateKey()
    }

    override fun seal(plain: ByteArray): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        PREFIX + Base64.getEncoder().encodeToString(iv + ct)
    } catch (e: Exception) {
        null
    }

    override fun open(sealed: String): ByteArray? {
        if (!sealed.startsWith(PREFIX)) return null
        return try {
            val raw = Base64.getDecoder().decode(sealed.substring(PREFIX.length))
            if (raw.size < IV_LENGTH + TAG_BITS / 8) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, raw, 0, IV_LENGTH))
            cipher.doFinal(raw, IV_LENGTH, raw.size - IV_LENGTH)
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFIX = "k1:"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
