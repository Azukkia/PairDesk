package io.github.azukkia.pairdesk.ui

import io.github.azukkia.pairdesk.R
import io.github.azukkia.pairdesk.ui.settings.PermanentPasswordRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PermanentPasswordRulesTest {
    @Test
    fun rules() {
        assertEquals(UiText(R.plurals.settings_permanent_too_short, listOf(8), quantity = 8), PermanentPasswordRules.validate("short", "short"))
        assertEquals(UiText(R.plurals.settings_permanent_too_short, listOf(8), quantity = 8), PermanentPasswordRules.validate("  1234567  ", "  1234567  "))
        assertEquals(UiText(R.string.settings_permanent_mismatch), PermanentPasswordRules.validate("long enough", "long enougH"))
        assertNull(PermanentPasswordRules.validate("long enough", "long enough"))
    }
}
