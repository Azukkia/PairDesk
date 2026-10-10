package io.github.azukkia.pairdesk

import io.github.azukkia.pairdesk.core.crypto.sha256
import io.github.azukkia.pairdesk.data.SecretBox
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.Base64

/** Polls [condition] until it holds (no fixed sleeps: CI machines can be slow). */
suspend fun waitUntil(timeoutMs: Long = 10_000, message: String = "condition", condition: () -> Boolean) {
    withTimeout(timeoutMs) {
        while (!condition()) delay(5)
    }
    check(condition()) { message }
}

/** Reversible "encryption" for tests (the Android Keystore is not available on the JVM). */
class FakeSecretBox(var available: Boolean = true) : SecretBox {
    override fun seal(plain: ByteArray): String? = if (available) "t:" + Base64.getEncoder().encodeToString(plain) else null

    override fun open(sealed: String): ByteArray? =
        if (sealed.startsWith("t:")) Base64.getDecoder().decode(sealed.substring(2)) else null
}

/** A fast stand-in for the PBKDF2 PRS (200 000 iterations), same inputs. */
fun fakePrs(password: String, hostId: String): ByteArray =
    sha256("fake-prs|$hostId|${io.github.azukkia.pairdesk.core.Protocol.normalizePassword(password)}".toByteArray())
