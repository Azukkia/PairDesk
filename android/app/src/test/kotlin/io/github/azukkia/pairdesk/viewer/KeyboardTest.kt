package io.github.azukkia.pairdesk.viewer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

class KeyboardTest {
    private val clock = ManualScheduler()
    private val sink = RecordingSink()
    private val sender = InputSender(sink, clock)
    private val modifiers = ModifierKeys()
    private var layout = RemoteLayout.QWERTY
    private var control = true
    private val keyboard = KeyboardController(sender, modifiers, layout = { layout }, canControl = { control })

    private fun k(code: String, down: Int) = "[\"k\",\"$code\",$down]"
    private fun tap(code: String) = "[${k(code, 1)},${k(code, 0)}]"

    // ───────────────────────────── key tables ─────────────────────────────

    private fun desktopFile(path: String): String? {
        val root = System.getProperty("pairdesk.repoRoot") ?: return null
        return File(root, path).takeIf { it.isFile }?.readText()
    }

    @Test
    fun `the supported codes are exactly the desktop KEYMAP, with the same evdev codes`() {
        val js = desktopFile("src/shared/keymap.js")
        assumeTrue(js != null, "desktop sources not available")
        val keymap = js!!.substringAfter("export const KEYMAP = {").substringBefore("};")
        val entries = Regex("""(\w+): K\(([^,]+), (\d+)\)""").findAll(keymap).associate { it.groupValues[1] to it.groupValues[3].toInt() }
        assertTrue(entries.size > 100, "parsed ${entries.size} keys")
        assertEquals(entries.keys, KeyCodes.SUPPORTED)
        for ((code, evdev) in entries) assertEquals(evdev, KeyCodes.evdevOf(code), code)
    }

    @Test
    fun `the combinations are the desktop KEY_COMBOS, in the same order`() {
        val js = desktopFile("src/shared/keymap.js")
        assumeTrue(js != null, "desktop sources not available")
        val block = js!!.substringAfter("export const KEY_COMBOS = {").substringBefore("};")
        val combos = Regex("""(\w+): \[([^\]]*)]""").findAll(block).map { m ->
            val name = m.groupValues[1].replace(Regex("([a-z])([A-Z0-9])"), "$1_$2").uppercase(Locale.ROOT)
            name to Regex("'([^']+)'").findAll(m.groupValues[2]).map { it.groupValues[1] }.toList()
        }.toList()
        assertEquals(combos.map { it.first }, KeyCombo.entries.map { it.name })
        for ((name, codes) in combos) assertEquals(codes, KeyCombo.valueOf(name).codes, name)
    }

    @Test
    fun `hardware keys go by physical position, phone keys stay on the phone`() {
        // KEYCODE_A typed on a key whose scan code is the QWERTY "Q" position (AZERTY keyboard).
        assertEquals("KeyQ", KeyCodes.forKeyEvent(29, 16))
        assertEquals("KeyA", KeyCodes.forKeyEvent(29, 0))
        assertEquals("Enter", KeyCodes.forKeyEvent(66, 999))
        assertEquals("ControlRight", KeyCodes.forKeyEvent(114, 97))
        assertEquals("MetaLeft", KeyCodes.forKeyEvent(117, 0))
        assertEquals("F11", KeyCodes.forKeyEvent(141, 87))
        assertEquals("Backspace", KeyCodes.forKeyCode(67))
        assertEquals("Delete", KeyCodes.forKeyCode(112))
        assertEquals("ArrowLeft", KeyCodes.forKeyCode(21))
        assertEquals("Numpad5", KeyCodes.forKeyCode(149))
        assertNull(KeyCodes.forKeyEvent(4, 158)) // BACK
        assertNull(KeyCodes.forKeyEvent(24, 115)) // VOLUME_UP
        assertNull(KeyCodes.forKeyEvent(26, 116)) // POWER
        assertTrue(KeyCodes.isPhoneKey(3))
        assertNull(KeyCodes.forKeyCode(1000))
        // Every Android key code mapped has a code the host knows.
        for (keyCode in 0..400) KeyCodes.forKeyCode(keyCode)?.let { assertTrue(it in KeyCodes.SUPPORTED, "$keyCode → $it") }
    }

    @Test
    fun `remote layouts give the physical key of a character`() {
        assertEquals(KeyStroke("KeyA"), RemoteLayout.QWERTY.strokeFor('a'))
        assertEquals(KeyStroke("KeyQ"), RemoteLayout.AZERTY.strokeFor('a'))
        assertEquals(KeyStroke("KeyW", shift = true), RemoteLayout.AZERTY.strokeFor('Z'))
        assertEquals(KeyStroke("Semicolon"), RemoteLayout.AZERTY.strokeFor('m'))
        assertEquals(KeyStroke("KeyY"), RemoteLayout.QWERTZ.strokeFor('z'))
        assertEquals(KeyStroke("Digit1"), RemoteLayout.AZERTY.strokeFor('1'))
        assertNull(RemoteLayout.AZERTY.strokeFor('é'))
        assertEquals(RemoteLayout.AZERTY, RemoteLayout.guess(Locale.FRANCE))
        assertEquals(RemoteLayout.QWERTY, RemoteLayout.guess(Locale.CANADA_FRENCH))
        assertEquals(RemoteLayout.QWERTZ, RemoteLayout.guess(Locale.GERMANY))
        assertEquals(RemoteLayout.QWERTY, RemoteLayout.guess(Locale.US))
        assertEquals(RemoteLayout.AZERTY, RemoteLayout.fromWire("azerty"))
    }

    // ───────────────────────────── sticky modifiers ─────────────────────────────

    @Test
    fun `a tap arms a modifier for one key, a long press locks it`() {
        modifiers.tap(StickyModifier.CTRL)
        assertEquals(StickyState.ONCE, modifiers.state(StickyModifier.CTRL))
        modifiers.lock(StickyModifier.ALT)
        assertEquals(listOf(StickyModifier.CTRL, StickyModifier.ALT), modifiers.active())
        modifiers.consume()
        assertEquals(StickyState.OFF, modifiers.state(StickyModifier.CTRL))
        assertEquals(StickyState.LOCKED, modifiers.state(StickyModifier.ALT))
        modifiers.tap(StickyModifier.ALT)
        assertFalse(modifiers.any)
    }

    // ───────────────────────────── keyboard controller ─────────────────────────────

    @Test
    fun `typed text goes as text, line breaks and tabs as keys`() {
        keyboard.text("Bonjour à tous")
        assertEquals("[[\"t\",\"Bonjour à tous\"]]", sink.reliable.single())
        sink.clear()
        keyboard.text("a\r\nb\tc")
        assertEquals(listOf("[[\"t\",\"a\"]]", tap("Enter"), "[[\"t\",\"b\"]]", tap("Tab"), "[[\"t\",\"c\"]]"), sink.reliable)
    }

    @Test
    fun `with Ctrl armed, a typed letter becomes the shortcut`() {
        modifiers.tap(StickyModifier.CTRL)
        keyboard.text("c")
        assertEquals("[${k("ControlLeft", 1)},${k("KeyC", 1)},${k("KeyC", 0)},${k("ControlLeft", 0)}]", sink.reliable.single())
        assertFalse(modifiers.any)
        sink.clear()
        keyboard.text("c")
        assertEquals("[[\"t\",\"c\"]]", sink.reliable.single())
    }

    @Test
    fun `shortcuts follow the remote layout`() {
        layout = RemoteLayout.AZERTY
        modifiers.lock(StickyModifier.CTRL)
        keyboard.text("a")
        keyboard.text("Z")
        assertEquals(
            listOf(
                "[${k("ControlLeft", 1)},${k("KeyQ", 1)},${k("KeyQ", 0)},${k("ControlLeft", 0)}]",
                "[${k("ControlLeft", 1)},${k("ShiftLeft", 1)},${k("KeyW", 1)},${k("KeyW", 0)},${k("ShiftLeft", 0)},${k("ControlLeft", 0)}]",
            ),
            sink.reliable,
        )
        assertEquals(StickyState.LOCKED, modifiers.state(StickyModifier.CTRL))
        sink.clear()
        // A character the layout cannot press goes as text.
        keyboard.text("é")
        assertEquals("[[\"t\",\"é\"]]", sink.reliable.single())
    }

    @Test
    fun `Shift alone types capitals`() {
        modifiers.tap(StickyModifier.SHIFT)
        keyboard.text("a")
        assertEquals("[[\"t\",\"A\"]]", sink.reliable.single())
        assertFalse(modifiers.any)
    }

    @Test
    fun `special keys are pressed and released, with the armed modifiers`() {
        keyboard.key("Backspace")
        modifiers.tap(StickyModifier.ALT)
        modifiers.tap(StickyModifier.SHIFT)
        keyboard.key("Tab")
        assertEquals(
            listOf(
                tap("Backspace"),
                "[${k("AltLeft", 1)},${k("ShiftLeft", 1)},${k("Tab", 1)},${k("Tab", 0)},${k("ShiftLeft", 0)},${k("AltLeft", 0)}]",
            ),
            sink.reliable,
        )
    }

    @Test
    fun `the Win key opens the Start menu, or combines when armed`() {
        keyboard.windowsKey()
        assertEquals(tap("MetaLeft"), sink.reliable.single())
        sink.clear()
        modifiers.tap(StickyModifier.META)
        keyboard.text("r")
        assertEquals("[${k("MetaLeft", 1)},${k("KeyR", 1)},${k("KeyR", 0)},${k("MetaLeft", 0)}]", sink.reliable.single())
        sink.clear()
        modifiers.tap(StickyModifier.CTRL)
        keyboard.windowsKey()
        assertEquals("[${k("ControlLeft", 1)},${k("MetaLeft", 1)},${k("MetaLeft", 0)},${k("ControlLeft", 0)}]", sink.reliable.single())
    }

    @Test
    fun `combinations ignore the sticky modifiers`() {
        modifiers.lock(StickyModifier.SHIFT)
        keyboard.combo(KeyCombo.WIN_D)
        assertEquals("[${k("MetaLeft", 1)},${k("KeyD", 1)},${k("KeyD", 0)},${k("MetaLeft", 0)}]", sink.reliable.single())
    }

    @Test
    fun `hardware keys go down and up as they happen`() {
        keyboard.hardwareKey("ControlLeft", true)
        keyboard.hardwareKey("KeyV", true)
        keyboard.hardwareKey("KeyV", false)
        control = false
        keyboard.hardwareKey("KeyX", true)
        keyboard.hardwareKey("ControlLeft", false) // still released: it was held
        assertEquals(
            listOf("[${k("ControlLeft", 1)}]", "[${k("KeyV", 1)}]", "[${k("KeyV", 0)}]", "[${k("ControlLeft", 0)}]"),
            sink.reliable,
        )
    }

    @Test
    fun `nothing is typed without control`() {
        control = false
        keyboard.text("abc")
        keyboard.key("Enter")
        keyboard.windowsKey()
        keyboard.combo(KeyCombo.CTRL_ALT_DEL)
        keyboard.androidAction("home")
        assertTrue(sink.all.isEmpty())
    }

    @Test
    fun `android hosts get navigation actions`() {
        keyboard.androidAction("back")
        assertEquals("[[\"a\",\"back\"]]", sink.reliable.single())
    }

    // ───────────────────────────── soft keyboard ─────────────────────────────

    private val typed = ArrayList<String>()
    private val soft = SoftKeyboard(object : SoftKeyboardOutput {
        override fun text(text: String) {
            typed += "t:$text"
        }

        override fun key(code: String) {
            typed += "k:$code"
        }
    })

    @Test
    fun `committed text is typed at once`() {
        soft.commitText("a")
        soft.commitText("é")
        assertEquals(listOf("t:a", "t:é"), typed)
    }

    @Test
    fun `composing text is typed as it grows`() {
        soft.setComposingText("h")
        soft.setComposingText("he")
        soft.setComposingText("hel")
        soft.commitText("hello ")
        assertEquals(listOf("t:h", "t:e", "t:l", "t:lo "), typed)
        assertEquals("", soft.composingText)
    }

    @Test
    fun `an autocorrection replaces only what differs`() {
        soft.setComposingText("teh")
        soft.commitText("the ")
        assertEquals(listOf("t:teh", "k:Backspace", "k:Backspace", "t:he "), typed)
    }

    @Test
    fun `backspace in a composing word removes its end`() {
        soft.setComposingText("abc")
        soft.setComposingText("ab")
        soft.finishComposingText()
        soft.commitText("x")
        assertEquals(listOf("t:abc", "k:Backspace", "t:x"), typed)
    }

    @Test
    fun `emoji count as one character`() {
        soft.setComposingText("😀")
        soft.setComposingText("😀a")
        soft.commitText("😀")
        assertEquals(listOf("t:😀", "t:a", "k:Backspace"), typed)
    }

    @Test
    fun `deletions and key events from the input method`() {
        soft.deleteSurroundingText(2, 1)
        assertTrue(soft.keyDown(67, 0))
        assertTrue(soft.keyDown(66, 10))
        assertTrue(soft.keyDown(29, 'a'.code))
        assertTrue(soft.keyDown(131, 0))
        assertFalse(soft.keyDown(59, 0)) // Shift alone: nothing
        assertFalse(soft.keyDown(4, 0)) // Back: the phone's
        soft.editorAction()
        assertEquals(
            listOf("k:Backspace", "k:Backspace", "k:Delete", "k:Backspace", "k:Enter", "t:a", "k:F1", "k:Enter"),
            typed,
        )
    }

    @Test
    fun `soft keyboard and controller together`() {
        val viaController = SoftKeyboard(keyboard)
        viaController.setComposingText("bo")
        viaController.commitText("bon ")
        assertEquals(listOf("[[\"t\",\"bo\"]]", "[[\"t\",\"n \"]]"), sink.reliable)
        assertNotNull(viaController)
    }
}
