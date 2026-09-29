package com.honerai.admin.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/** Цвета — те же, что HonerColors в приложении Honer AI (тёмная тема), плюс красные метки админки. */
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
    val adminRed: Color,
    val online: Color,
    val away: Color,
    val danger: Color,
    val outgoing: Color,
)

val DarkHonerColors = HonerColors(
    accent = Color(0xFF6E99FA), background = Color(0xFF101010), sidebar = Color(0xFF0C0C0C),
    surface = Color(0xFF191919), raised = Color(0xFF292929), bubble = Color(0xFF313131),
    foreground = Color(0xFFEDEDEF), secondary = Color(0xFF919596), divider = Color(0xFF303030),
    adminRed = Color(0xFFE5283C), online = Color(0xFF34C759), away = Color(0xFFFFC53D), danger = Color(0xFFFF5A5F),
    outgoing = Color(0xFF2B4580),
)

val LocalHonerColors = staticCompositionLocalOf { DarkHonerColors }

/** Язык интерфейса: русский по умолчанию, английский — переключатель в настройках. */
val LocalEnglish = staticCompositionLocalOf { false }

object HonerTheme {
    val colors: HonerColors
        @Composable @ReadOnlyComposable get() = LocalHonerColors.current
}

/** Строка на языке интерфейса. */
@Composable
@ReadOnlyComposable
fun tr(ru: String, en: String): String = if (LocalEnglish.current) en else ru

@Composable
fun HonerAdminTheme(english: Boolean, content: @Composable () -> Unit) {
    val colors = DarkHonerColors
    val scheme = darkColorScheme(
        primary = colors.accent, background = colors.background, surface = colors.surface,
        onBackground = colors.foreground, onSurface = colors.foreground, surfaceVariant = colors.raised,
        onSurfaceVariant = colors.secondary, outline = colors.divider, secondary = colors.accent,
        surfaceContainer = colors.surface, surfaceContainerHigh = colors.raised, surfaceContainerHighest = colors.raised,
        surfaceContainerLow = colors.surface, error = colors.danger,
    )
    CompositionLocalProvider(LocalHonerColors provides colors, LocalEnglish provides english) {
        MaterialTheme(colorScheme = scheme, typography = HonerTypography, content = content)
    }
}

/** Типографика без разрядки Material — как в Honer AI (HonerTypography). */
private val HonerTypography: Typography = Typography().let { base ->
    fun TextStyle.tight() = copy(letterSpacing = 0.sp)
    base.copy(
        displayLarge = base.displayLarge.tight(), displayMedium = base.displayMedium.tight(), displaySmall = base.displaySmall.tight(),
        headlineLarge = base.headlineLarge.tight(), headlineMedium = base.headlineMedium.tight(), headlineSmall = base.headlineSmall.tight(),
        titleLarge = base.titleLarge.tight(), titleMedium = base.titleMedium.tight(), titleSmall = base.titleSmall.tight(),
        bodyLarge = base.bodyLarge.tight(), bodyMedium = base.bodyMedium.tight(), bodySmall = base.bodySmall.tight(),
        labelLarge = base.labelLarge.tight(), labelMedium = base.labelMedium.tight(), labelSmall = base.labelSmall.tight(),
    )
}
