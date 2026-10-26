/*
 * OmniTV Glass UI Theme
 */
package app.omnitv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Premium translucent frosted glass palette
object OmniGlass {
    val Background = Color(0xFF06080E)
    
    // Glass surface layers (pre-blended for zero GPU penalty)
    val GlassCard = Color(0x66182236)
    val GlassCardFocused = Color(0xFFFFFFFF)
    val GlassCardHovered = Color(0x80334E7A)
    val GlassCardPlaying = Color(0x991E3A6E)
    val GlassCardPlayingFocused = Color(0xFFFFFFFF)
    
    val GlassBorder = Color(0x2EFFFFFF)
    val GlassBorderFocused = Color(0xFFFFFFFF)
    val GlassBorderHovered = Color(0xFF7C93FF)
    val GlassBorderPlaying = Color(0xFF5B7FFF)

    val AccentNeon = Color(0xFF7C93FF)
    val AccentPill = Color(0x337C93FF)

    val TextPrimary = Color(0xFFF6F8FC)
    val TextSecondary = Color(0xFF8E9AAF)
    val TextDark = Color(0xFF090C15)

    val OverlayGradient = androidx.compose.ui.graphics.Brush.verticalGradient(
        listOf(
            Color(0xE606080E),
            Color(0x3306080E),
            Color(0xF506080E)
        )
    )
}

private val DarkScheme = darkColorScheme(
    primary = OmniGlass.AccentNeon,
    onPrimary = OmniGlass.TextDark,
    secondary = OmniGlass.TextSecondary,
    background = OmniGlass.Background,
    onBackground = OmniGlass.TextPrimary,
    surface = OmniGlass.GlassCard,
    onSurface = OmniGlass.TextPrimary,
    surfaceVariant = Color(0x331E293B),
    onSurfaceVariant = OmniGlass.TextSecondary,
    outline = OmniGlass.GlassBorder,
    error = Color(0xFFFF5252),
    onError = Color(0xFF1E0505),
)

private val OmniTvTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 32.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun OmniTvTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = OmniTvTypography,
        content = content,
    )
}
