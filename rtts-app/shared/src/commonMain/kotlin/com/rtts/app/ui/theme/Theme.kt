package com.rtts.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Brand: deep aviation blue, used for app bar / primary actions.
val RttsBlue = Color(0xFF1B3A63)
val RttsBlueLight = Color(0xFF3E6DA6)
val RttsAmber = Color(0xFFDDA125) // accent, evokes runway/radio lighting

/** Cabin tokens translated directly from the designer's day/night palettes. */
data class RttsCabinColors(
    val background: Color, val panel: Color, val panel2: Color,
    val line: Color, val line2: Color, val text: Color, val text2: Color,
    val muted: Color, val amber: Color, val amberSoft: Color, val cyan: Color,
    val magenta: Color, val green: Color, val red: Color, val onAmber: Color,
)

val CabinNight = RttsCabinColors(
    Color(0xFF0B1420), Color(0xFF111D2C), Color(0xFF17263A),
    Color(0xFF24374F), Color(0xFF35506F), Color(0xFFEEF3F8), Color(0xFFB4C3D4),
    Color(0xFF8496AB), Color(0xFFFFB629), Color(0xFFFFB629).copy(alpha = .14f),
    Color(0xFF5FD4F0), Color(0xFFFF6FD0), Color(0xFF4ADE80), Color(0xFFFF7A70), Color(0xFF1C1300),
)
val CabinDay = RttsCabinColors(
    Color(0xFFEEF2F6), Color.White, Color(0xFFE6ECF3),
    Color(0xFFCFD9E4), Color(0xFFA9B9CB), Color(0xFF0C1A2A), Color(0xFF3A4D63),
    Color(0xFF5A6C82), Color(0xFF9A5B00), Color(0xFFBE7800).copy(alpha = .14f),
    Color(0xFF046A8A), Color(0xFFA8177D), Color(0xFF0F7A3C), Color(0xFFB3261E), Color.White,
)
val LocalRttsCabinColors = staticCompositionLocalOf { CabinNight }
val LocalCabinFontScale = staticCompositionLocalOf { 1f }

/** Fixed, high-contrast palette for coloring transcript segments by speaker. Cycled by index. */
val SpeakerPalette = listOf(
    Color(0xFF2E7D32), // green
    Color(0xFF1565C0), // blue
    Color(0xFF8E24AA), // purple
    Color(0xFFD84315), // deep orange
    Color(0xFF00838F), // teal
    Color(0xFF6D4C41), // brown
    Color(0xFFAD1457), // pink
    Color(0xFF9E9D24), // olive
)

fun colorForSpeaker(label: String): Color {
    val index = (label.hashCode().takeIf { it != Int.MIN_VALUE } ?: 0).let { kotlin.math.abs(it) }
    return SpeakerPalette[index % SpeakerPalette.size]
}

@Composable
fun RttsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) CabinNight else CabinDay
    val colors = if (dark) darkColorScheme(
        primary = c.amber, onPrimary = c.onAmber, secondary = c.cyan,
        primaryContainer = c.panel2, onPrimaryContainer = c.text,
        secondaryContainer = c.amberSoft, onSecondaryContainer = c.amber,
        surfaceTint = c.amber,
        background = c.background, onBackground = c.text, surface = c.panel,
        onSurface = c.text, surfaceVariant = c.panel2, onSurfaceVariant = c.text2,
        outline = c.line2, error = c.red,
    ) else lightColorScheme(
        primary = c.amber, onPrimary = c.onAmber, secondary = c.cyan,
        primaryContainer = c.panel2, onPrimaryContainer = c.text,
        secondaryContainer = c.amberSoft, onSecondaryContainer = c.amber,
        surfaceTint = c.amber,
        background = c.background, onBackground = c.text, surface = c.panel,
        onSurface = c.text, surfaceVariant = c.panel2, onSurfaceVariant = c.text2,
        outline = c.line2, error = c.red,
    )
    CompositionLocalProvider(LocalRttsCabinColors provides c) {
        MaterialTheme(colorScheme = colors, typography = RttsTypography, content = content)
    }
}
