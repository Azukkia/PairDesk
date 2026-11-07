package io.github.azukkia.pairdesk.host

/**
 * The text of an editable field and its selection ([start] ≤ [end]; equal
 * when it is a cursor), as an accessibility service reads it.
 */
data class TextField(val text: String, val start: Int, val end: Int) {
    val hasSelection: Boolean get() = start != end
}

/**
 * Text edits computed from what the accessibility service reads of the
 * focused field, applied with `ACTION_SET_TEXT` + `ACTION_SET_SELECTION`
 * (used when the field cannot be reached through an input connection).
 * Positions are UTF-16 indexes; a surrogate pair is never split.
 */
object TextEditing {
    /**
     * [text] with the selection reported by the node: an unknown selection
     * (-1) puts the cursor at the end, a reversed one is put in order.
     */
    fun field(text: String, selectionStart: Int, selectionEnd: Int): TextField {
        val len = text.length
        if (selectionStart < 0 || selectionEnd < 0) return TextField(text, len, len)
        val a = selectionStart.coerceIn(0, len)
        val b = selectionEnd.coerceIn(0, len)
        return TextField(text, minOf(a, b), maxOf(a, b))
    }

    /** Types [inserted] in place of the selection. */
    fun insert(f: TextField, inserted: String): TextField {
        val text = f.text.substring(0, f.start) + inserted + f.text.substring(f.end)
        val cursor = f.start + inserted.length
        return TextField(text, cursor, cursor)
    }

    /** What Backspace removes (the selection, else the character before the cursor); null when nothing. */
    fun backspaceRange(f: TextField): IntRange? {
        if (f.hasSelection) return f.start until f.end
        if (f.start == 0) return null
        return previous(f.text, f.start) until f.start
    }

    /** What Delete removes (the selection, else the character after the cursor); null when nothing. */
    fun deleteRange(f: TextField): IntRange? {
        if (f.hasSelection) return f.start until f.end
        if (f.end >= f.text.length) return null
        return f.end until next(f.text, f.end)
    }

    fun backspace(f: TextField): TextField? = backspaceRange(f)?.let { remove(f, it) }

    fun delete(f: TextField): TextField? = deleteRange(f)?.let { remove(f, it) }

    private fun remove(f: TextField, range: IntRange): TextField {
        val text = f.text.removeRange(range)
        return TextField(text, range.first, range.first)
    }

    /**
     * The selection after a cursor key: Left / Right move by one character
     * (or collapse a selection to its side), Home / End go to the start / end
     * of the text. With [extend] the selection grows (Left and Home move its
     * start, Right and End its end). Up / Down are not computed here (they
     * need the layout): null.
     */
    fun move(f: TextField, move: CaretMove, extend: Boolean): TextField? {
        val len = f.text.length
        return when (move) {
            CaretMove.LEFT -> when {
                extend -> f.copy(start = previous(f.text, f.start))
                f.hasSelection -> f.copy(end = f.start)
                else -> previous(f.text, f.start).let { f.copy(start = it, end = it) }
            }
            CaretMove.RIGHT -> when {
                extend -> f.copy(end = next(f.text, f.end))
                f.hasSelection -> f.copy(start = f.end)
                else -> next(f.text, f.end).let { f.copy(start = it, end = it) }
            }
            CaretMove.HOME -> if (extend) f.copy(start = 0) else f.copy(start = 0, end = 0)
            CaretMove.END -> if (extend) f.copy(end = len) else f.copy(start = len, end = len)
            CaretMove.UP, CaretMove.DOWN -> null
        }
    }

    /** All the text selected. */
    fun selectAll(f: TextField): TextField = f.copy(start = 0, end = f.text.length)

    private fun previous(text: String, index: Int): Int {
        if (index <= 0) return 0
        var i = index - 1
        if (i > 0 && Character.isLowSurrogate(text[i]) && Character.isHighSurrogate(text[i - 1])) i--
        return i
    }

    private fun next(text: String, index: Int): Int {
        if (index >= text.length) return text.length
        var i = index + 1
        if (i < text.length && Character.isHighSurrogate(text[i - 1]) && Character.isLowSurrogate(text[i])) i++
        return i
    }
}
