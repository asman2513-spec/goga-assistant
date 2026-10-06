package app.goga.assistant.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Pine = Color(0xFF1B6B4A)
private val PineDark = Color(0xFF8FCBB0)
private val Ink = Color(0xFF10231B)
private val Paper = Color(0xFFF4F7F5)

private val LightColors = lightColorScheme(
    primary = Pine,
    onPrimary = Color.White,
    background = Paper,
    surface = Color.White,
    onBackground = Ink,
    onSurface = Ink,
)

private val DarkColors = darkColorScheme(
    primary = PineDark,
    onPrimary = Ink,
    background = Color(0xFF0E1713),
    surface = Color(0xFF17241E),
    onBackground = Color(0xFFE7F2EC),
    onSurface = Color(0xFFE7F2EC),
)

@Composable
fun GogaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
