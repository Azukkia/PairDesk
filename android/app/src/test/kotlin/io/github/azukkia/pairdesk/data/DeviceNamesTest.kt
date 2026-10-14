package io.github.azukkia.pairdesk.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DeviceNamesTest {
    @Test
    fun `pretty names`() {
        assertEquals("Samsung SM-G991B", DeviceNames.pretty("samsung", "SM-G991B"))
        assertEquals("Google Pixel 8", DeviceNames.pretty("Google", "Pixel 8"))
        assertEquals("OnePlus 12", DeviceNames.pretty("OnePlus", "OnePlus 12"))
        assertEquals("Xiaomi", DeviceNames.pretty("xiaomi", ""))
        assertEquals("Android", DeviceNames.pretty(null, null))
        assertEquals(SettingsStore.MAX_NAME, DeviceNames.pretty("m", "x".repeat(100)).length)
    }
}
