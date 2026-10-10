package io.github.azukkia.pairdesk.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackStackTest {
    @Test
    fun `push pop and remove`() {
        val stack = BackStack()
        assertEquals(Screen.Home, stack.current)
        assertFalse(stack.pop(), "home stays")

        stack.push(Screen.Settings)
        stack.push(Screen.Viewer("a"))
        stack.push(Screen.Settings)
        assertEquals(listOf(Screen.Home, Screen.Viewer("a"), Screen.Settings), stack.screens.value, "no duplicates")

        stack.remove(Screen.Viewer("a"))
        assertEquals(listOf(Screen.Home, Screen.Settings), stack.screens.value)
        assertTrue(stack.pop())
        assertEquals(Screen.Home, stack.current)

        stack.push(Screen.HostSession("b"))
        stack.push(Screen.Camera("c"))
        stack.popToHome()
        assertEquals(listOf(Screen.Home), stack.screens.value)
        stack.remove(Screen.Home)
        assertEquals(listOf(Screen.Home), stack.screens.value, "never empty")
    }

    @Test
    fun `replace puts a reconnected session where the old one was`() {
        val stack = BackStack()
        stack.push(Screen.Settings)
        stack.push(Screen.Viewer("old"))
        stack.replace(Screen.Viewer("old"), Screen.Viewer("new"))
        assertEquals(listOf(Screen.Home, Screen.Settings, Screen.Viewer("new")), stack.screens.value)
        // The old one is gone already: the new one is shown.
        stack.replace(Screen.Viewer("gone"), Screen.Viewer("other"))
        assertEquals(Screen.Viewer("other"), stack.current)
        // No duplicate when the new screen was already there.
        stack.replace(Screen.Viewer("new"), Screen.Viewer("other"))
        assertEquals(listOf(Screen.Home, Screen.Settings, Screen.Viewer("other")), stack.screens.value)
    }

    @Test
    fun `keys are distinct`() {
        val keys = listOf(Screen.Home, Screen.Settings, Screen.Viewer("x"), Screen.Camera("x"), Screen.HostSession("x")).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
}
