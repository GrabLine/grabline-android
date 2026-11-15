package dev.grabline.app.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode { System, Light, Dark }

/*
 * Dark: the brand blue #2563eb reaches only 3.8:1 as text on #07090e, and
 * white on any blue light enough to pass as text drops below 4.5:1. So text
 * buttons and links (M3 `primary`) use the lighter accent tint with dark text
 * on fills, and the brand blue stays as `primaryContainer` (the + button,
 * selected states) with white text (QA-44).
 */
val GrabLineDarkScheme: ColorScheme = darkColorScheme(
    primary = GrabLineColors.DarkAccentText,
    onPrimary = GrabLineColors.DarkBackground,
    primaryContainer = GrabLineColors.DarkPrimary,
    onPrimaryContainer = GrabLineColors.DarkOnPrimary,
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

/* Light: the desktop accent is 4.3:1 as text on the light background, so text
 * roles use the deeper accent tint; the desktop accent stays as the container. */
val GrabLineLightScheme: ColorScheme = lightColorScheme(
    primary = GrabLineColors.LightAccentText,
    onPrimary = GrabLineColors.LightOnPrimary,
    primaryContainer = GrabLineColors.LightPrimary,
    onPrimaryContainer = GrabLineColors.LightOnPrimary,
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
    val reduced = rememberReducedMotion()
    CompositionLocalProvider(LocalReducedMotion provides reduced) {
        MaterialTheme(colorScheme = scheme, typography = GrabLineTypography, content = content)
    }
}

/** True when the user turned animations off (animator scale 0, or "Remove animations"). */
val LocalReducedMotion = compositionLocalOf { false }

private fun animationsOff(context: Context): Boolean = try {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
} catch (_: Exception) {
    false
}

@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    var reduced by remember { mutableStateOf(animationsOff(context)) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = animationsOff(context)
            }
        }
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        try {
            context.contentResolver.registerContentObserver(uri, false, observer)
        } catch (_: Exception) {
        }
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return reduced
}

/** Accent colour that is safe for small text (>= 4.5:1) on this scheme's background. */
val ColorScheme.accentText: Color
    get() = if (background.luminance() < 0.5f) GrabLineColors.DarkAccentText else GrabLineColors.LightAccentText
