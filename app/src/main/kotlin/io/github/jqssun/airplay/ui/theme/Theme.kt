package io.github.jqssun.airplay.ui.theme

import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AirTvColors = darkColorScheme(
    primary = Color(0xFFAEEDCE), onPrimary = Color(0xFF10271D),
    background = Color(0xFF0C1012), onBackground = Color(0xFFF1F4F3),
    surface = Color(0xFF161C1F), onSurface = Color(0xFFF1F4F3),
    surfaceVariant = Color(0xFF20282C), onSurfaceVariant = Color(0xFF9BA8AD),
    outline = Color(0xFF3B484E), error = Color(0xFFFFB4AB)
)

@Composable
fun AirPlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AirTvColors) {
        Surface(color = AirTvColors.background, contentColor = AirTvColors.onBackground) {
            content()
        }
    }
}
