package app.letnievpn.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Палитра 1 в 1 из прототипа letnievpn.html (светлая и тёмная). */
class Palette(
    val dark: Boolean,
    val primary: Color,
    val onPrimary: Color,
    val bg: Color,
    val surface: Color,
    val text: Color,
    val text2: Color,
    val ga: Color,
    val gb: Color,
    val rimHi: Color,
    val rimLo: Color,
)

val LightPalette = Palette(
    dark = false,
    primary = Color(0xFFD4527F),
    onPrimary = Color(0xFFFFFFFF),
    bg = Color(0xFFFBF4F5),
    surface = Color(0xFFF3E8EB),
    text = Color(0xFF25181C),
    text2 = Color(0xFF85727A),
    ga = Color(0xFFF2E2E7),
    gb = Color(0xFFFFFDFD),
    rimHi = Color(0xFFFFFFFF),
    rimLo = Color(0x99FFFFFF),
)

val DarkPalette = Palette(
    dark = true,
    primary = Color(0xFFF4A6C0),
    onPrimary = Color(0xFF2A0E18),
    bg = Color(0xFF141012),
    surface = Color(0xFF221B1E),
    text = Color(0xFFF6EEF0),
    text2 = Color(0xFFA39499),
    ga = Color(0xFF3A2D32),
    gb = Color(0xFF1E1719),
    rimHi = Color(0x4DFFFFFF),
    rimLo = Color(0x0AFFFFFF),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

@Composable
fun LetnieTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) DarkPalette else LightPalette
    val scheme = if (p.dark) {
        darkColorScheme(primary = p.primary, background = p.bg, surface = p.surface, onSurface = p.text)
    } else {
        lightColorScheme(primary = p.primary, background = p.bg, surface = p.surface, onSurface = p.text)
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
