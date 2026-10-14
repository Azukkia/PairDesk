package io.github.azukkia.pairdesk.data

import io.github.azukkia.pairdesk.FakeSecretBox
import io.github.azukkia.pairdesk.core.Protocol
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.SecureRandom

class SettingsStoreTest {
    private val kv = MemoryKeyValueStore()
    private val secrets = FakeSecretBox()
    private var now = 1_000L

    private fun store() = SettingsStore(kv, secrets, defaultDisplayName = "Google Pixel 8", clock = { now })

    @Test
    fun `generates an identity like the desktop`() {
        repeat(200) {
            val s = SettingsStore(MemoryKeyValueStore(), secrets, "Phone", SecureRandom())
            assertTrue(Regex("^[1-9][0-9]{8}$").matches(s.deviceId), s.deviceId)
            assertTrue(s.deviceKey.length >= 32)
            val password = s.tempPassword.value
            assertEquals(Protocol.DEFAULT_PASSWORD_LENGTH, password.length)
            assertTrue(password.all { it in Protocol.PASSWORD_ALPHABET }, password)
            assertTrue(password.none { it in "01ilo" })
        }
    }

    @Test
    fun `identity and temporary password survive a restart`() {
        val first = store()
        val second = store()
        assertEquals(first.deviceId, second.deviceId)
        assertEquals(first.deviceKey, second.deviceKey)
        assertEquals(first.tempPassword.value, second.tempPassword.value)
    }

    @Test
    fun `an invalid stored id is replaced and its PRS dropped`() {
        kv.edit {
            putString("device_id", "012345678")
            putString("permanent_prs", secrets.seal(ByteArray(32) { 1 }))
        }
        val s = store()
        assertTrue(Protocol.isValidId(s.deviceId))
        assertNull(s.permanentPrs())
        assertFalse(s.settings.value.hasPermanentPassword)
    }

    @Test
    fun `regenerating the password invalidates its PRS`() {
        val s = store()
        val password = s.tempPassword.value
        val prs = ByteArray(32) { 7 }
        assertTrue(s.storeTempPrs(password, prs))
        assertArrayEquals(prs, s.tempPrs())
        assertArrayEquals(prs, store().tempPrs(), "kept across restarts")

        val next = s.regenerateTempPassword()
        assertEquals(next, s.tempPassword.value)
        assertNull(s.tempPrs())
        assertFalse(s.storeTempPrs(password, prs), "PRS of a stale password")
    }

    @Test
    fun `permanent password is only stored as a sealed PRS`() {
        val s = store()
        val prs = ByteArray(32) { 3 }
        assertTrue(s.setPermanentPrs(prs))
        assertTrue(s.settings.value.hasPermanentPassword)
        assertArrayEquals(prs, store().permanentPrs())
        assertTrue((kv.values["permanent_prs"] as String).startsWith("t:"))

        assertTrue(s.setPermanentPrs(null))
        assertFalse(s.settings.value.hasPermanentPassword)
        assertNull(s.permanentPrs())

        secrets.available = false
        assertFalse(s.setPermanentPrs(prs), "never stored in clear")
        assertFalse(s.settings.value.hasPermanentPassword)
    }

    @Test
    fun `display name defaults to the device model`() {
        val s = store()
        assertEquals("Google Pixel 8", s.displayName)
        s.setDisplayName("  Téléphone de Marie  ")
        assertEquals("Téléphone de Marie", s.displayName)
        assertEquals("Téléphone de Marie", store().displayName)
        s.setDisplayName("x".repeat(100))
        assertEquals(SettingsStore.MAX_NAME, s.displayName.length)
        s.setDisplayName("   ")
        assertEquals("Google Pixel 8", s.displayName)
    }

    @Test
    fun `network settings validate the server URL`() {
        val s = store()
        assertEquals(NetworkMode.PUBLIC, s.settings.value.networkMode)
        assertFalse(s.setNetwork(NetworkMode.SERVER, "https://example.com"))
        assertFalse(s.setNetwork(NetworkMode.SERVER, "wss://bad url"))
        assertEquals(NetworkMode.PUBLIC, s.settings.value.networkMode)

        assertTrue(s.setNetwork(NetworkMode.SERVER, " wss://pairdesk.example.com/ws "))
        assertEquals(NetworkMode.SERVER, store().settings.value.networkMode)
        assertEquals("wss://pairdesk.example.com/ws", store().settings.value.serverUrl)

        assertTrue(s.setNetwork(NetworkMode.PUBLIC, "not a url"))
        assertEquals(NetworkMode.PUBLIC, s.settings.value.networkMode)
        assertEquals("", s.settings.value.serverUrl)
    }

    @Test
    fun `incoming and control switches persist`() {
        val s = store()
        assertTrue(s.settings.value.allowIncoming)
        assertTrue(s.settings.value.allowControl)
        s.setAllowIncoming(false)
        s.setAllowControl(false)
        assertFalse(store().settings.value.allowIncoming)
        assertFalse(store().settings.value.allowControl)
    }

    @Test
    fun `recent partners keep an encrypted PRS`() {
        val s = store()
        val prs = ByteArray(32) { 9 }
        s.touchRecent("123456789", "Bureau", PrsUpdate.Set(prs))
        now = 2_000
        s.touchRecent("987654321", "Portable", PrsUpdate.Clear)

        val recents = store().recents.value
        assertEquals(listOf("987654321", "123456789"), recents.map { it.id })
        assertTrue(recents[1].hasPassword)
        assertFalse(recents[0].hasPassword)
        assertArrayEquals(prs, store().savedPrs("123456789"))
        assertTrue(kv.values["recents"].toString().contains("\"prs\":\"t:"), "stored sealed")

        now = 3_000
        s.touchRecent("123456789", null, PrsUpdate.Keep)
        val again = s.recents.value
        assertEquals("123456789", again[0].id)
        assertEquals("Bureau", again[0].name)
        assertEquals(2, again[0].count)
        assertEquals(3_000, again[0].lastAt)
        assertTrue(again[0].hasPassword)

        s.forgetRecentPassword("123456789")
        assertNull(s.savedPrs("123456789"))
        s.forgetRecent("123456789")
        assertEquals(listOf("987654321"), s.recents.value.map { it.id })
    }

    @Test
    fun `generated passwords are not all the same`() {
        val s = store()
        val seen = (1..20).map { s.regenerateTempPassword() }.toSet()
        assertNotEquals(1, seen.size)
    }

    @Test
    fun `viewer preferences are remembered`() {
        val first = store()
        assertEquals(ViewerPrefs(), first.viewer.value)
        first.updateViewer { it.copy(inputMode = "direct", quality = "speed", clipboardSync = false, remoteLayout = "azerty", landscape = true) }
        val second = store()
        assertEquals("direct", second.viewer.value.inputMode)
        assertEquals("speed", second.viewer.value.quality)
        assertFalse(second.viewer.value.clipboardSync)
        assertEquals("azerty", second.viewer.value.remoteLayout)
        assertTrue(second.viewer.value.landscape)
        // An unknown quality falls back to the default.
        second.updateViewer { it.copy(quality = "ultra") }
        assertEquals("balanced", store().viewer.value.quality)
    }
}
