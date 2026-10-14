package io.github.azukkia.pairdesk.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Vector drawables are parsed only when they are first drawn: a typo in a
 * path would crash the app on a screen the build never opened. Checks the
 * syntax of every `android:pathData` (commands and argument counts).
 */
class DrawablesTest {
    private val arity = mapOf('M' to 2, 'L' to 2, 'H' to 1, 'V' to 1, 'C' to 6, 'S' to 4, 'Q' to 4, 'T' to 2, 'A' to 7, 'Z' to 0)
    private val number = Regex("""[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""")

    private fun check(path: String): String? {
        var i = 0
        var command: Char? = null
        var args = 0
        fun close(): String? {
            val c = command ?: return null
            val n = arity.getValue(c.uppercaseChar())
            if (n == 0 && args != 0) return "Z takes no argument"
            if (n > 0 && (args == 0 || args % n != 0)) return "$c with $args arguments"
            return null
        }
        while (i < path.length) {
            val ch = path[i]
            when {
                ch.isWhitespace() || ch == ',' -> i++
                ch.uppercaseChar() in arity -> {
                    close()?.let { return it }
                    if (command == null && ch.uppercaseChar() != 'M') return "must start with M"
                    command = ch
                    args = 0
                    i++
                }
                else -> {
                    // Arc flags may be written without separators ("0,0 1,1 0").
                    val m = number.find(path, i)?.takeIf { it.range.first == i } ?: return "unexpected '$ch' at $i"
                    args++
                    i = m.range.last + 1
                }
            }
        }
        return close()
    }

    @Test
    fun `every path of every drawable parses`() {
        val dir = File(System.getProperty("user.dir"), "src/main/res/drawable")
        val files = dir.listFiles { f -> f.extension == "xml" }.orEmpty()
        assertTrue(files.size >= 10, "drawables in $dir")
        var paths = 0
        for (f in files) {
            for (m in Regex("""android:pathData="([^"]*)"""").findAll(f.readText())) {
                paths++
                assertEquals(null, check(m.groupValues[1]), "${f.name}: ${m.groupValues[1].take(60)}")
            }
        }
        assertTrue(paths >= files.size - 1)
    }

    @Test
    fun `the checker catches mistakes`() {
        assertEquals(null, check("M0,0L1,1z"))
        assertEquals(null, check("M20.38,8.57a8,8 0,0 1,-0.22 7.58z"))
        assertTrue(check("M0,0L1z") != null)
        assertTrue(check("L1,1") != null)
        assertTrue(check("M0,0X1,1") != null)
    }
}
