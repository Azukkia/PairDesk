package io.github.azukkia.pairdesk.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Colors of the desktop application (src/renderer/common/base.css).
private val BrandBlue = Color(0xFF3B82F6)
private val BrandViolet = Color(0xFF6D4CF6)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F62F5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE5FF),
    onPrimaryContainer = Color(0xFF0B2A86),
    secondary = Color(0xFF6D5CF6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E5FF),
    onSecondaryContainer = Color(0xFF2B1C88),
    background = Color(0xFFF4F6FB),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF4F6FB),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFECEFF6),
    onSurfaceVariant = Color(0xFF475069),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF9FAFD),
    surfaceContainerHigh = Color(0xFFECEFF6),
    surfaceContainerHighest = Color(0xFFE3E7F0),
    outline = Color(0xFFC5CBD9),
    outlineVariant = Color(0xFFDDE1EA),
    error = Color(0xFFD92D2D),
    onError = Color.White,
    errorContainer = Color(0xFFFDE7E7),
    onErrorContainer = Color(0xFF7A1010),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4F7BFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDDE5FF),
    secondary = Color(0xFF8B7CFF),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF34297A),
    onSecondaryContainer = Color(0xFFE9E5FF),
    background = Color(0xFF0E1322),
    onBackground = Color(0xFFE6E9F2),
    surface = Color(0xFF0E1322),
    onSurface = Color(0xFFE6E9F2),
    surfaceVariant = Color(0xFF1C2338),
    onSurfaceVariant = Color(0xFFA8B0C5),
    surfaceContainerLowest = Color(0xFF0A0F1C),
    surfaceContainerLow = Color(0xFF161C2E),
    surfaceContainer = Color(0xFF161C2E),
    surfaceContainerHigh = Color(0xFF1C2338),
    surfaceContainerHighest = Color(0xFF242C44),
    outline = Color(0xFF3A4462),
    outlineVariant = Color(0xFF2A3350),
    error = Color(0xFFF05252),
    onError = Color.White,
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFDADA),
)

/** Colors outside the Material scheme (status dot, success). */
@Immutable
data class PairDeskColors(
    val success: Color,
    val warning: Color,
    val danger: Color,
    val brand: Brush,
)

private val LightExtra = PairDeskColors(
    success = Color(0xFF15A34A),
    warning = Color(0xFFC97A06),
    danger = Color(0xFFD92D2D),
    brand = Brush.linearGradient(listOf(BrandBlue, BrandViolet)),
)

private val DarkExtra = PairDeskColors(
    success = Color(0xFF34C46A),
    warning = Color(0xFFF0A42A),
    danger = Color(0xFFF05252),
    brand = Brush.linearGradient(listOf(BrandBlue, BrandViolet)),
)

val LocalPairDeskColors = staticCompositionLocalOf { LightExtra }

private val BaseTypography = Typography()

/** The ID and the password are read aloud and copied by hand: monospaced, spaced digits. */
val CredentialTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.SemiBold,
    fontSize = 28.sp,
    letterSpacing = 1.sp,
)

@Composable
fun PairDeskTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalPairDeskColors provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = BaseTypography,
            content = content,
        )
    }
}
