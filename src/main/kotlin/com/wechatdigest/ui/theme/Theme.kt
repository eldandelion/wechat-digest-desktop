package com.wechatdigest.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import com.wechatdigest.app.AppThemeMode
import com.wechatdigest.core.model.EventPriority

/** CompositionLocal exposing whether dark theme is currently active across [WeChatDigestTheme]. */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

// Priority-specific Badge Colors
object PriorityColors {
    val CriticalLight = Color(0xFFBA1A1A)
    val CriticalLightContainer = Color(0xFFFFDAD6)
    val CriticalDark = Color(0xFFFFB4AB)
    val CriticalDarkContainer = Color(0xFF93000A)

    val ImportantLight = Color(0xFFC05600)
    val ImportantLightContainer = Color(0xFFFFDCC2)
    val ImportantDark = Color(0xFFFFB77C)
    val ImportantDarkContainer = Color(0xFF6B3000)

    val NormalLight = Color(0xFF006A60)
    val NormalLightContainer = Color(0xFF73F8E7)
    val NormalDark = Color(0xFF53DBCB)
    val NormalDarkContainer = Color(0xFF005048)

    val LowLight = Color(0xFF5C5F62)
    val LowLightContainer = Color(0xFFE1E2E5)
    val LowDark = Color(0xFFC5C6C9)
    val LowDarkContainer = Color(0xFF44474A)

    fun getColor(priority: EventPriority, isDark: Boolean): Color {
        return when (priority) {
            EventPriority.CRITICAL -> if (isDark) CriticalDark else CriticalLight
            EventPriority.IMPORTANT -> if (isDark) ImportantDark else ImportantLight
            EventPriority.NORMAL -> if (isDark) NormalDark else NormalLight
            EventPriority.LOW -> if (isDark) LowDark else LowLight
        }
    }

    fun getContainerColor(priority: EventPriority, isDark: Boolean): Color {
        return when (priority) {
            EventPriority.CRITICAL -> if (isDark) CriticalDarkContainer else CriticalLightContainer
            EventPriority.IMPORTANT -> if (isDark) ImportantDarkContainer else ImportantLightContainer
            EventPriority.NORMAL -> if (isDark) NormalDarkContainer else NormalLightContainer
            EventPriority.LOW -> if (isDark) LowDarkContainer else LowLightContainer
        }
    }
}

val RobotoFontFamily = FontFamily(
    Font(resource = "fonts/Roboto-Thin.ttf", weight = FontWeight.Thin, style = FontStyle.Normal),
    Font(resource = "fonts/Roboto-Light.ttf", weight = FontWeight.Light, style = FontStyle.Normal),
    Font(resource = "fonts/Roboto-Regular.ttf", weight = FontWeight.Normal, style = FontStyle.Normal),
    Font(resource = "fonts/Roboto-Italic.ttf", weight = FontWeight.Normal, style = FontStyle.Italic),
    Font(resource = "fonts/Roboto-Medium.ttf", weight = FontWeight.Medium, style = FontStyle.Normal),
    Font(resource = "fonts/Roboto-Medium.ttf", weight = FontWeight.SemiBold, style = FontStyle.Normal),
    Font(resource = "fonts/Roboto-Bold.ttf", weight = FontWeight.Bold, style = FontStyle.Normal)
)

private val BaseTypography = Typography()

val WeChatDigestTypography = Typography(
    displayLarge = BaseTypography.displayLarge.copy(fontFamily = RobotoFontFamily),
    displayMedium = BaseTypography.displayMedium.copy(fontFamily = RobotoFontFamily),
    displaySmall = BaseTypography.displaySmall.copy(fontFamily = RobotoFontFamily),
    headlineLarge = BaseTypography.headlineLarge.copy(fontFamily = RobotoFontFamily),
    headlineMedium = BaseTypography.headlineMedium.copy(fontFamily = RobotoFontFamily),
    headlineSmall = BaseTypography.headlineSmall.copy(fontFamily = RobotoFontFamily),
    titleLarge = BaseTypography.titleLarge.copy(fontFamily = RobotoFontFamily),
    titleMedium = BaseTypography.titleMedium.copy(fontFamily = RobotoFontFamily),
    titleSmall = BaseTypography.titleSmall.copy(fontFamily = RobotoFontFamily),
    bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = RobotoFontFamily),
    bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = RobotoFontFamily),
    bodySmall = BaseTypography.bodySmall.copy(fontFamily = RobotoFontFamily),
    labelLarge = BaseTypography.labelLarge.copy(fontFamily = RobotoFontFamily),
    labelMedium = BaseTypography.labelMedium.copy(fontFamily = RobotoFontFamily),
    labelSmall = BaseTypography.labelSmall.copy(fontFamily = RobotoFontFamily)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF006A60),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF73F8E7),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E2),
    onSecondaryContainer = Color(0xFF05201C),
    background = Color(0xFFEEF5F2),
    onBackground = Color(0xFF161D1B),
    surface = Color(0xFFEEF5F2),
    onSurface = Color(0xFF161D1B),
    surfaceVariant = Color(0xFFDAE5E1),
    onSurfaceVariant = Color(0xFF3F4946),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6FBF8),
    surfaceContainer = Color(0xFFE8F0ED),
    surfaceContainerHigh = Color(0xFFE0EAE6),
    surfaceContainerHighest = Color(0xFFD8E3DF),
    outline = Color(0xFF6F7976)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF53DBCB),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFF73F8E7),
    secondary = Color(0xFFB1CCC6),
    onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF334B47),
    onSecondaryContainer = Color(0xFFCCE8E2),
    background = Color(0xFF181D1B),
    onBackground = Color(0xFFDEE4E1),
    surface = Color(0xFF181D1B),
    onSurface = Color(0xFFDEE4E1),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBFC9C5),
    surfaceContainerLowest = Color(0xFF0E1311),
    surfaceContainerLow = Color(0xFF141A18),
    surfaceContainer = Color(0xFF1D2421),
    surfaceContainerHigh = Color(0xFF262E2B),
    surfaceContainerHighest = Color(0xFF303936),
    outline = Color(0xFF89938F)
)

/**
 * Root Material 3 theme wrapper applying [LightColorScheme] or [DarkColorScheme] based on
 * the persisted [themeMode] (or explicit [darkTheme] override) and providing [LocalIsDarkTheme].
 */
@Composable
fun WeChatDigestTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    darkTheme: Boolean = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    },
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = WeChatDigestTypography,
            content = content
        )
    }
}
