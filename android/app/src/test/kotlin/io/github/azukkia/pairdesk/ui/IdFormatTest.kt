package io.github.azukkia.pairdesk.ui

import androidx.compose.ui.text.AnnotatedString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IdFormatTest {
    @Test
    fun `sanitize keeps at most nine digits`() {
        assertEquals("123456789", IdFormat.sanitize("123 456 789"))
        assertEquals("123456789", IdFormat.sanitize("123-456-789"))
        assertEquals("123456789", IdFormat.sanitize("ID: 123 456 7890"))
        assertEquals("", IdFormat.sanitize("abc"))
    }

    @Test
    fun `format groups by three`() {
        assertEquals("", IdFormat.format(""))
        assertEquals("12", IdFormat.format("12"))
        assertEquals("123 4", IdFormat.format("1234"))
        assertEquals("123 456 789", IdFormat.format("123456789"))
    }

    @Test
    fun `offset mapping is consistent`() {
        for (length in 0..9) {
            val formatted = IdFormat.format("1".repeat(length))
            for (o in 0..length) {
                val t = IdFormat.originalToTransformed(o, length)
                assert(t in 0..formatted.length) { "o=$o len=$length t=$t" }
                assertEquals(o, IdFormat.transformedToOriginal(t, length), "round trip o=$o len=$length")
                // The cursor sits right after the o-th digit.
                assertEquals(o, formatted.substring(0, t).count { it.isDigit() })
            }
            for (t in 0..formatted.length) {
                assert(IdFormat.transformedToOriginal(t, length) in 0..length)
            }
        }
    }

    @Test
    fun `visual transformation`() {
        val transformed = IdFormat.visualTransformation.filter(AnnotatedString("1234567"))
        assertEquals("123 456 7", transformed.text.text)
        assertEquals(9, transformed.offsetMapping.originalToTransformed(7))
        assertEquals(4, transformed.offsetMapping.transformedToOriginal(5))
    }
}
