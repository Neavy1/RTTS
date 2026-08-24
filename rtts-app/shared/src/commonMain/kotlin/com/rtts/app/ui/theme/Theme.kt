package com.rtts.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand: deep aviation blue, used for app bar / primary actions.
val RttsBlue = Color(0xFF1B3A63)
val RttsBlueLight = Color(0xFF3E6DA6)
val RttsAmber = Color(0xFFDDA125) // accent, evokes runway/radio lighting

private val LightColors = lightColorScheme(
    primary = RttsBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E3F5),
    onPrimaryContainer = RttsBlue,
    secondary = RttsAmber,
    onSecondary = Color(0xFF3A2900),
    background = Color(0xFFF7F8FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFE9EDF3),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = RttsBlueLight,
    onPrimary = Color.White,
    primaryContainer = RttsBlue,
    onPrimaryContainer = Color(0xFFD7E3F5),
    secondary = RttsAmber,
    background = Color(0xFF10141B),
    surface = Color(0xFF1A2029),
    surfaceVariant = Color(0xFF2A3140),
    error = Color(0xFFFFB4AB),
)

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
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = RttsTypography, content = content)
}
