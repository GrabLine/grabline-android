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
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode { System, Light, Dark }

/*
 * Every Material role is set: an unset role falls back to Material's baseline purple, which showed in the
 * bottom bar's active pill and selected rows. Text-sized accents (M3 `primary`: text buttons, links,
 * switches) use the readable accent tint; the logo blue is `primaryContainer` (filled buttons, the +
 * button) with white text (QA-44).
 */
val GrabLineDarkScheme: ColorScheme = darkColorScheme(
    primary = GrabLineColors.DarkAccentText,
    onPrimary = GrabLineColors.DarkBackground,
    primaryContainer = GrabLineColors.DarkPrimary,
    onPrimaryContainer = GrabLineColors.DarkOnPrimary,
    inversePrimary = GrabLineColors.Blue,
    secondary = GrabLineColors.DarkAccentText,
    onSecondary = GrabLineColors.DarkBackground,
    secondaryContainer = GrabLineColors.DarkSelected,
    onSecondaryContainer = GrabLineColors.DarkOnBackground,
    tertiary = GrabLineColors.DarkAccentText,
    onTertiary = GrabLineColors.DarkBackground,
    tertiaryContainer = GrabLineColors.DarkSelected,
    onTertiaryContainer = GrabLineColors.DarkOnBackground,
    background = GrabLineColors.DarkBackground,
    onBackground = GrabLineColors.DarkOnBackground,
    surface = GrabLineColors.DarkSurface,
    onSurface = GrabLineColors.DarkOnBackground,
    surfaceVariant = GrabLineColors.DarkSurface,
    onSurfaceVariant = GrabLineColors.DarkOnSurfaceVariant,
    surfaceTint = GrabLineColors.Blue,
    surfaceBright = GrabLineColors.DarkSurfaceHigh,
    surfaceDim = GrabLineColors.DarkBackground,
    surfaceContainerLowest = GrabLineColors.DarkBackground,
    surfaceContainerLow = GrabLineColors.DarkSurfaceLow,
    surfaceContainer = GrabLineColors.DarkSurface,
    surfaceContainerHigh = GrabLineColors.DarkSurface,
    surfaceContainerHighest = GrabLineColors.DarkSurfaceHigh,
    inverseSurface = GrabLineColors.DarkOnBackground,
    inverseOnSurface = GrabLineColors.DarkBackground,
    outline = GrabLineColors.DarkOutline,
    outlineVariant = GrabLineColors.DarkOutline,
)

val GrabLineLightScheme: ColorScheme = lightColorScheme(
    primary = GrabLineColors.LightAccentText,
    onPrimary = GrabLineColors.LightOnPrimary,
    primaryContainer = GrabLineColors.LightPrimary,
    onPrimaryContainer = GrabLineColors.LightOnPrimary,
    inversePrimary = GrabLineColors.DarkAccentText,
    secondary = GrabLineColors.LightAccentText,
    onSecondary = GrabLineColors.LightOnPrimary,
    secondaryContainer = GrabLineColors.LightSelected,
    onSecondaryContainer = GrabLineColors.LightOnBackground,
    tertiary = GrabLineColors.LightAccentText,
    onTertiary = GrabLineColors.LightOnPrimary,
    tertiaryContainer = GrabLineColors.LightSelected,
    onTertiaryContainer = GrabLineColors.LightOnBackground,
    background = GrabLineColors.LightBackground,
    onBackground = GrabLineColors.LightOnBackground,
    surface = GrabLineColors.LightSurface,
    onSurface = GrabLineColors.LightOnBackground,
    surfaceVariant = GrabLineColors.LightSurface,
    onSurfaceVariant = GrabLineColors.LightOnSurfaceVariant,
    surfaceTint = GrabLineColors.Blue,
    surfaceBright = GrabLineColors.LightSurface,
    surfaceDim = GrabLineColors.LightSurfaceHigh,
    surfaceContainerLowest = GrabLineColors.LightSurface,
    surfaceContainerLow = GrabLineColors.LightSurfaceLow,
    surfaceContainer = GrabLineColors.LightSurface,
    surfaceContainerHigh = GrabLineColors.LightSurface,
    surfaceContainerHighest = GrabLineColors.LightSurfaceHigh,
    inverseSurface = GrabLineColors.LightOnBackground,
    inverseOnSurface = GrabLineColors.LightBackground,
    outline = GrabLineColors.LightOutline,
    outlineVariant = GrabLineColors.LightOutline,
)

/**
 * The desktop app's corner scale (4/6/8 px) at phone size: controls 8dp, cards 12dp, dialogs and sheets
 * 16-20dp, instead of Material's pill buttons and 28dp sheets.
 */
val GrabLineShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun GrabLineTheme(
    themeMode: ThemeMode = ThemeMode.System,
    /** The `colours` setting: an [Accent] key, or "wallpaper" for the phone's own colours (Android 12+). */
    colours: String = Accent.Blue.key,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val context = LocalContext.current
    val scheme = when {
        colours == "wallpaper" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> brandScheme(dark, Accent.of(colours))
    }
    val reduced = rememberReducedMotion()
    CompositionLocalProvider(LocalReducedMotion provides reduced) {
        MaterialTheme(colorScheme = scheme, typography = GrabLineTypography, shapes = GrabLineShapes, content = content)
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

/** Accent colour that is safe for small text (>= 4.5:1) on this scheme's background: `primary` in every scheme. */
val ColorScheme.accentText: Color
    get() = primary
