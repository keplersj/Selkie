package io.github.keplersj.selkie.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Tokens from the redesign canvas (m3.css). The fallback scheme is seeded from sea teal; on Android 12+ the
// wallpaper's dynamic colours still win, as before.

internal val SeaTealLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF006A60), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9EF2E4), onPrimaryContainer = Color(0xFF00201C),
    inversePrimary = Color(0xFF82D5C8),
    secondary = Color(0xFF4A635F), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E2), onSecondaryContainer = Color(0xFF05201C),
    tertiary = Color(0xFF3F5F7A), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCBE6FF), onTertiaryContainer = Color(0xFF001E30),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF4FBF8), onBackground = Color(0xFF161D1B),
    surface = Color(0xFFF4FBF8), onSurface = Color(0xFF161D1B),
    surfaceVariant = Color(0xFFDCE4E1), onSurfaceVariant = Color(0xFF3F4947),
    surfaceTint = Color(0xFF006A60),
    inverseSurface = Color(0xFF2B3230), inverseOnSurface = Color(0xFFECF2EF),
    outline = Color(0xFF6F7977), outlineVariant = Color(0xFFBEC9C6),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF4FBF8), surfaceDim = Color(0xFFD5DBD9),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFEEF5F2),
    surfaceContainer = Color(0xFFE8EFEC), surfaceContainerHigh = Color(0xFFE2EAE6),
    surfaceContainerHighest = Color(0xFFDCE4E1),
)

internal val SeaTealDark: ColorScheme = darkColorScheme(
    primary = Color(0xFF82D5C8), onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048), onPrimaryContainer = Color(0xFF9EF2E4),
    inversePrimary = Color(0xFF006A60),
    secondary = Color(0xFFB1CCC6), onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF334B47), onSecondaryContainer = Color(0xFFCCE8E2),
    tertiary = Color(0xFFA9CAE8), onTertiary = Color(0xFF0E3349),
    tertiaryContainer = Color(0xFF27485F), onTertiaryContainer = Color(0xFFCBE6FF),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1513), onBackground = Color(0xFFDDE4E1),
    surface = Color(0xFF0E1513), onSurface = Color(0xFFDDE4E1),
    surfaceVariant = Color(0xFF2F3635), onSurfaceVariant = Color(0xFFBEC9C6),
    surfaceTint = Color(0xFF82D5C8),
    inverseSurface = Color(0xFFDDE4E1), inverseOnSurface = Color(0xFF2B3230),
    outline = Color(0xFF899391), outlineVariant = Color(0xFF3F4947),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF343B39), surfaceDim = Color(0xFF0E1513),
    surfaceContainerLowest = Color(0xFF090F0E), surfaceContainerLow = Color(0xFF161D1B),
    surfaceContainer = Color(0xFF1A2120), surfaceContainerHigh = Color(0xFF252B2A),
    surfaceContainerHighest = Color(0xFF2F3635),
)

/** The canvas's type scale: t-display, t-headline, t-title, t-title-m, t-body-l, t-body, t-label. */
internal val SelkieTypography: Typography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Medium,
            letterSpacing = (-0.3).sp),
        headlineMedium = t.headlineMedium.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium),
        titleLarge = t.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
        titleMedium = t.titleMedium.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium,
            letterSpacing = 0.sp),
        bodyLarge = t.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = t.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelMedium = t.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
            letterSpacing = 0.4.sp),
    )
}

/** Canvas radii: chips 8, keys and fields 12, marks 16, groups 24, cards 28. */
internal val SelkieShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** The terminal's own palette. It stays dark in light mode, as on the canvas. */
@Immutable
data class TerminalColors(val background: Color, val foreground: Color, val dim: Color, val accent: Color)

internal val DefaultTerminalColors = TerminalColors(
    background = Color(0xFF0B1110), foreground = Color(0xFFD7E0DD), dim = Color(0xFF8FA19C), accent = Color(0xFF82D5C8),
)

internal val LocalTerminalColors = staticCompositionLocalOf { DefaultTerminalColors }

/** Image addresses, digests and terminal text. */
internal val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp)

object SelkieTheme {
    val terminal: TerminalColors
        @Composable @ReadOnlyComposable get() = LocalTerminalColors.current
}

@Composable
fun SelkieTheme(
    dark: Boolean = isSystemInDarkTheme(),
    /** Previews pass false so screenshots don't depend on the wallpaper. */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> SeaTealDark
        else -> SeaTealLight
    }
    CompositionLocalProvider(LocalTerminalColors provides DefaultTerminalColors) {
        MaterialTheme(colorScheme = scheme, typography = SelkieTypography, shapes = SelkieShapes, content = content)
    }
}
