package dev.nixi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
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

// ── Szkło: wspólne tokeny dla pigułki rozmowy, paska nawigacji i kart ──
// Wartości są półprzezroczyste celowo: tło pod spodem ma zostać widoczne
// (użytkownik nie chce przyciemnionego ekranu).
val NixiGlass = Color(0x7A120C22)
val NixiGlassSoft = Color(0x59120C22)
val NixiGlassDeep = Color(0x6607040F)
val NixiGlassDeepSoft = Color(0x4D07040F)
val NixiGlassEdge = Color(0x2EFFFFFF)
val NixiGlassEdgeStrong = Color(0x47FFFFFF)
val NixiPurpleSoft = Color(0x338F6FFF)
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

/**
 * Tło aplikacji: jednolity, ciemny granat u góry (dokładnie taki sam jak pasek
 * statusu — dzięki temu nie ma widocznego „szwu”) przechodzący w prawie czerń
 * na dole. Zero grafik, więc nie waży nic i nie mruga przy starcie.
 */
val NixiBgGradient = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to NixiBg,
        0.45f to Color(0xFF0E0A1E),
        1.0f to Color(0xFF07040F),
    )
)

@Composable
fun NixiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content
    )
}
