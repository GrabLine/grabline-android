package dev.grabline.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode { System, Light, Dark }

val GrabLineDarkScheme: ColorScheme = darkColorScheme(
    primary = GrabLineColors.DarkPrimary,
    onPrimary = GrabLineColors.DarkOnPrimary,
    background = GrabLineColors.DarkBackground,
    onBackground = GrabLineColors.DarkOnBackground,
    surface = GrabLineColors.DarkSurface,
    onSurface = GrabLineColors.DarkOnBackground,
    surfaceVariant = GrabLineColors.DarkSurface,
    onSurfaceVariant = GrabLineColors.DarkOnSurfaceVariant,
    surfaceContainer = GrabLineColors.DarkSurface,
    surfaceContainerHigh = GrabLineColors.DarkSurface,
    outline = GrabLineColors.DarkOutline,
    outlineVariant = GrabLineColors.DarkOutline,
)

val GrabLineLightScheme: ColorScheme = lightColorScheme(
    primary = GrabLineColors.LightPrimary,
    onPrimary = GrabLineColors.LightOnPrimary,
    background = GrabLineColors.LightBackground,
    onBackground = GrabLineColors.LightOnBackground,
    surface = GrabLineColors.LightSurface,
    onSurface = GrabLineColors.LightOnBackground,
    surfaceVariant = GrabLineColors.LightSurface,
    onSurfaceVariant = GrabLineColors.LightOnSurfaceVariant,
    surfaceContainer = GrabLineColors.LightSurface,
    surfaceContainerHigh = GrabLineColors.LightSurface,
    outline = GrabLineColors.LightOutline,
    outlineVariant = GrabLineColors.LightOutline,
)

@Composable
fun GrabLineTheme(
    themeMode: ThemeMode = ThemeMode.System,
    useWallpaperColors: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val context = LocalContext.current
    val scheme = when {
        useWallpaperColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> GrabLineDarkScheme
        else -> GrabLineLightScheme
    }
    MaterialTheme(colorScheme = scheme, typography = GrabLineTypography, content = content)
}
