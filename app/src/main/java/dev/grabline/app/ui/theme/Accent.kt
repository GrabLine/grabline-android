package dev.grabline.app.ui.theme

import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import dev.grabline.app.R

/**
 * The accent colours, the same presets as the desktop app (Settings, Appearance; `ACCENT_PRESETS` in
 * app/ui/design.py). Each has its fill (filled buttons, the + button, progress), its text tint for light and
 * dark backgrounds and its selected-row wash. The desktop's green, orange and teal take white text at only
 * about 3.6:1, so here their fills are one shade deeper (same hue) to reach 4.5:1.
 */
enum class Accent(
    val key: String,
    @StringRes val label: Int,
    val fill: Color,
    val lightText: Color,
    val darkText: Color,
    val lightSelected: Color,
    val darkSelected: Color,
) {
    Blue("grabline", R.string.colours_grabline, Color(0xFF2563EB), Color(0xFF1D4ED8), Color(0xFF86AAFF), Color(0xFFDFE8FC), Color(0xFF172846)),
    Violet("violet", R.string.colours_violet, Color(0xFF7C3AED), Color(0xFF6D28D9), Color(0xFFC4B5FD), Color(0xFFEDE6FD), Color(0xFF2A1D4C)),
    Green("green", R.string.colours_green, Color(0xFF047857), Color(0xFF047857), Color(0xFF6EE7B7), Color(0xFFD5F5E8), Color(0xFF0E3328)),
    Orange("orange", R.string.colours_orange, Color(0xFFC2410C), Color(0xFF9A3412), Color(0xFFFDBA74), Color(0xFFFCE7D9), Color(0xFF3A2215)),
    Rose("rose", R.string.colours_rose, Color(0xFFE11D48), Color(0xFFBE123C), Color(0xFFFDA4AF), Color(0xFFFCE1E6), Color(0xFF3D1720)),
    Teal("teal", R.string.colours_teal, Color(0xFF0F766E), Color(0xFF0F766E), Color(0xFF5EEAD4), Color(0xFFD3F3EF), Color(0xFF0E302D)),
    ;

    companion object {
        /** The stored `colours` setting; "wallpaper" and unknown values fall back to the brand blue here. */
        fun of(key: String?): Accent = entries.firstOrNull { it.key == key } ?: Blue
    }
}

/** A brand scheme re-tinted with [accent]; every other role stays the brand neutral. */
fun brandScheme(dark: Boolean, accent: Accent): ColorScheme {
    val base = if (dark) GrabLineDarkScheme else GrabLineLightScheme
    if (accent == Accent.Blue) return base
    val text = if (dark) accent.darkText else accent.lightText
    val selected = if (dark) accent.darkSelected else accent.lightSelected
    return base.copy(
        primary = text,
        primaryContainer = accent.fill,
        inversePrimary = if (dark) accent.fill else accent.darkText,
        secondary = text,
        secondaryContainer = selected,
        tertiary = text,
        tertiaryContainer = selected,
        surfaceTint = accent.fill,
    )
}
