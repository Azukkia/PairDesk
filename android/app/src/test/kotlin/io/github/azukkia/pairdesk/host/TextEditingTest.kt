package io.github.azukkia.pairdesk.host

import io.github.azukkia.pairdesk.core.json.JsonJs
import kotlinx.serialization.json.JsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextEditingTest {
    private fun f(text: String, start: Int, end: Int = start) = TextField(text, start, end)

    @Test
    fun `the selection reported by a node is normalized`() {
        assertEquals(f("abc", 3), TextEditing.field("abc", -1, -1))
        assertEquals(f("abc", 1, 2), TextEditing.field("abc", 2, 1))
        assertEquals(f("abc", 3), TextEditing.field("abc", 7, 9))
    }

    @Test
    fun `typing replaces the selection and moves the cursor after the text`() {
        assertEquals(f("abXYc", 4), TextEditing.insert(f("abc", 2), "XY"))
        assertEquals(f("aZ", 2), TextEditing.insert(f("abc", 1, 3), "Z"))
        assertEquals(f("é😀", 3), TextEditing.insert(f("", 0), "é😀"))
    }

    @Test
    fun `backspace and delete never split a surrogate pair`() {
        assertEquals(f("a", 1), TextEditing.backspace(f("a😀", 3)))
        assertEquals(f("ab", 1), TextEditing.delete(f("a😀b", 1)))
        assertEquals(f("ac", 1), TextEditing.backspace(f("abc", 1, 2)))
        assertEquals(f("ac", 1), TextEditing.delete(f("abc", 1, 2)))
        assertNull(TextEditing.backspace(f("abc", 0)))
        assertNull(TextEditing.delete(f("abc", 3)))
        assertEquals(1 until 3, TextEditing.backspaceRange(f("a😀", 3)))
    }

    @Test
    fun `cursor keys`() {
        val text = "a😀b"
        assertEquals(f(text, 1), TextEditing.move(f(text, 3), CaretMove.LEFT, false))
        assertEquals(f(text, 3), TextEditing.move(f(text, 1), CaretMove.RIGHT, false))
        assertEquals(f(text, 1), TextEditing.move(f(text, 1, 3), CaretMove.LEFT, false))
        assertEquals(f(text, 3), TextEditing.move(f(text, 1, 3), CaretMove.RIGHT, false))
        assertEquals(f(text, 1, 3), TextEditing.move(f(text, 3), CaretMove.LEFT, true))
        assertEquals(f(text, 3, 4), TextEditing.move(f(text, 3), CaretMove.RIGHT, true))
        assertEquals(f(text, 0), TextEditing.move(f(text, 2), CaretMove.HOME, false))
        assertEquals(f(text, 4), TextEditing.move(f(text, 2), CaretMove.END, false))
        assertEquals(f(text, 0, 3), TextEditing.move(f(text, 3), CaretMove.HOME, true))
        assertEquals(f(text, 0), TextEditing.move(f(text, 0), CaretMove.LEFT, false))
        assertNull(TextEditing.move(f(text, 0), CaretMove.UP, false))
        assertEquals(f(text, 0, 4), TextEditing.selectAll(f(text, 2)))
    }
}

class PointerSequencerTest {
    private val seq = PointerSequencer()

    private fun ok(json: String) = seq.accept(JsonJs.parse(json) as JsonArray)

    @Test
    fun `same rules as src-main-input-pointer-seq_js`() {
        assertTrue(ok("""["m",0.1,0.1,1,0]"""))
        assertFalse(ok("""["m",0.1,0.1,1,0]"""), "duplicate")
        assertTrue(ok("""["d",0,0.1,0.1,3]"""))
        assertFalse(ok("""["m",0.2,0.2,2,0]"""), "older than the press")
        assertFalse(ok("""["m",0.2,0.2,5,4]"""), "overtook a release")
        assertTrue(ok("""["u",0,0.1,0.1,4]"""))
        assertTrue(ok("""["m",0.2,0.2,5,4]"""))
        assertTrue(ok("""["w",0,100,6]"""))
        assertFalse(ok("""["m",0.2,0.2,6,4]"""))
        assertTrue(ok("""["m",0.2,0.2]"""), "unnumbered (viewers older than 1.1)")
        assertTrue(ok("""["m",0.2,0.2,7.5]"""), "not a safe integer: applied as it comes")
        assertTrue(ok("""["k","KeyA",1]"""))
        seq.reset()
        assertTrue(ok("""["m",0.1,0.1,1,0]"""))
    }
}

class CaptureSizeTest {
    @Test
    fun `screens are captured at most 1920 pixels long, sides multiple of 16`() {
        assertEquals(864 to 1920, CaptureSize.forScreen(1080, 2400))
        assertEquals(1920 to 864, CaptureSize.forScreen(2400, 1080))
        assertEquals(720 to 1600, CaptureSize.forScreen(720, 1600))
        assertEquals(1072 to 1920, CaptureSize.forScreen(1080, 1920))
        assertEquals(16 to 16, CaptureSize.forScreen(0, 0))
    }
}
