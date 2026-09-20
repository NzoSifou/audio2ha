package fr.nzosifou.audio2ha.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FA8FF),
    onPrimary = Color(0xFF00243F),
    secondary = Color(0xFF9BE8B4),
    background = Color(0xFF12141A),
    onBackground = Color(0xFFE8EAF0),
    surface = Color(0xFF1C1F28),
    onSurface = Color(0xFFE8EAF0),
    surfaceVariant = Color(0xFF262A35),
    onSurfaceVariant = Color(0xFFB6BCCB),
    error = Color(0xFFFF8A8A),
)

@Composable
fun Audio2HATheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography(),
        content = content,
    )
}
