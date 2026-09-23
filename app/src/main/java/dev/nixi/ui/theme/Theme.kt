package dev.nixi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val NixiBg = Color(0xFF0B0716)
val NixiSurface = Color(0xFF171029)
val NixiSurfaceHi = Color(0xFF221741)
val NixiPurple = Color(0xFF8F6FFF)
val NixiPurpleDeep = Color(0xFF5B3DF5)
val NixiVioletDark = Color(0xFF1A0066)
val NixiText = Color(0xFFEFEDFF)
val NixiTextDim = Color(0xFFA99FC7)
val NixiOk = Color(0xFF63E6A8)
val NixiWarn = Color(0xFFFFC46B)
val NixiErr = Color(0xFFFF7A7A)

private val Dark = darkColorScheme(
    primary = NixiPurple,
    onPrimary = Color.White,
    secondary = NixiPurple,
    background = NixiBg,
    surface = NixiSurface,
    onBackground = NixiText,
    onSurface = NixiText,
    error = NixiErr,
)

private val Light = lightColorScheme(
    primary = NixiPurpleDeep,
    background = NixiBg,
    surface = NixiSurface,
    onBackground = NixiText,
    onSurface = NixiText,
)

@Composable
fun NixiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content
    )
}
