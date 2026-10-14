package io.github.azukkia.pairdesk.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import io.github.azukkia.pairdesk.core.Protocol

/**
 * The partner ID field: the user types (or pastes "123 456 789",
 * "123-456-789") digits, shown grouped by three like the desktop.
 */
object IdFormat {
    const val LENGTH = 9

    /** What the field keeps of [input]: its digits, at most [LENGTH]. */
    fun sanitize(input: String): String = Protocol.normalizeId(input).take(LENGTH)

    /** "123456789" → "123 456 789" (partial input too: "1234" → "123 4"). */
    fun format(digits: String): String = Protocol.formatId(digits)

    /** Position in the formatted text of the cursor after [offset] digits. */
    fun originalToTransformed(offset: Int, length: Int): Int {
        val o = offset.coerceIn(0, length)
        // One space before each group of three after the first, once a digit of that group is there.
        val spaces = if (o == 0) 0 else (o - 1) / 3
        return o + spaces.coerceAtMost(if (length == 0) 0 else (length - 1) / 3)
    }

    /** Number of digits before position [offset] of the formatted text. */
    fun transformedToOriginal(offset: Int, length: Int): Int {
        val formattedLength = format("0".repeat(length)).length
        val o = offset.coerceIn(0, formattedLength)
        return (o - o / 4).coerceIn(0, length)
    }

    /** Groups the digits by three on screen; the field's value stays digits only. */
    val visualTransformation = VisualTransformation { text ->
        val digits = text.text
        TransformedText(
            AnnotatedString(format(digits)),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = originalToTransformed(offset, digits.length)

                override fun transformedToOriginal(offset: Int) = transformedToOriginal(offset, digits.length)
            },
        )
    }
}
