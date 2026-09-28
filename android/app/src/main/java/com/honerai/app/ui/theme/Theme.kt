package com.honerai.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Цвета Honer AI — те же, что HonorTheme на iPhone (тёмная и светлая тема). */
@Immutable
data class HonerColors(
    val accent: Color,
    val background: Color,
    val sidebar: Color,
    val surface: Color,
    val raised: Color,
    val bubble: Color,
    val foreground: Color,
    val secondary: Color,
    val divider: Color,
    val isDark: Boolean,
)

val DarkHonerColors = HonerColors(
    accent = Color(0xFF6E99FA), background = Color(0xFF101010), sidebar = Color(0xFF0C0C0C),
    surface = Color(0xFF191919), raised = Color(0xFF292929), bubble = Color(0xFF313131),
    foreground = Color(0xFFF5F5F5), secondary = Color(0xFF919596), divider = Color(0xFF303030), isDark = true,
)

val LightHonerColors = HonerColors(
    accent = Color(0xFF6E99FA), background = Color(0xFFFFFFFF), sidebar = Color(0xFFF4F4F6),
    surface = Color(0xFFF7F7F8), raised = Color(0xFFECECEE), bubble = Color(0xFFEDEDEF),
    foreground = Color(0xFF171719), secondary = Color(0xFF747478), divider = Color(0xFFDADADD), isDark = false,
)

val LocalHonerColors = staticCompositionLocalOf { DarkHonerColors }

/** Короткий доступ к цветам: HonerTheme.colors.accent. */
object HonerTheme {
    val colors: HonerColors
        @Composable get() = LocalHonerColors.current
}

/**
 * Тема приложения. [appearance]: "system", "light" или "dark" (по умолчанию тёмная, как на iPhone).
 */
@Composable
fun HonerAppTheme(appearance: String, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val colors = if (dark) DarkHonerColors else LightHonerColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accent, background = colors.background, surface = colors.surface,
            onBackground = colors.foreground, onSurface = colors.foreground, surfaceVariant = colors.raised,
            onSurfaceVariant = colors.secondary, outline = colors.divider, secondary = colors.accent,
        )
    } else {
        lightColorScheme(
            primary = colors.accent, background = colors.background, surface = colors.surface,
            onBackground = colors.foreground, onSurface = colors.foreground, surfaceVariant = colors.raised,
            onSurfaceVariant = colors.secondary, outline = colors.divider, secondary = colors.accent,
        )
    }
    CompositionLocalProvider(LocalHonerColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
