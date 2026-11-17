package dev.grabline.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The GrabLine brand tokens (docs/BRAND.md), shared with the desktop app and the website. The brand blue is
 * the logo's own colour; dark neutrals come from the website, light neutrals from the desktop app.
 */
object GrabLineColors {
    /** The logo blue: fills (primary buttons, the + button, progress). White on it is 5.2:1. */
    val Blue = Color(0xFF2563EB)
    val BlueHover = Color(0xFF1D4ED8)

    // Dark
    val DarkBackground = Color(0xFF07090E)
    val DarkSurface = Color(0xFF121821)
    val DarkSurfaceLow = Color(0xFF0C1018)
    val DarkSurfaceHigh = Color(0xFF18202C)
    val DarkOutline = Color(0xFF1E2736)
    val DarkOnBackground = Color(0xFFE8EEF8)
    val DarkOnSurfaceVariant = Color(0xFFA4B0C4)
    /** Selected rows, the active tab's pill, selected chips. */
    val DarkSelected = Color(0xFF172846)
    val DarkPrimary = Blue
    val DarkOnPrimary = Color(0xFFFFFFFF)
    /** The accent as small text: the brand blue itself is only 3.8:1 on the dark background. */
    val DarkAccentText = Color(0xFF86AAFF)

    // Light (desktop LIGHT palette: bg, surface, border, text, text2)
    val LightBackground = Color(0xFFF3F5F9)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceLow = Color(0xFFF7F9FC)
    val LightSurfaceHigh = Color(0xFFEAEEF4)
    val LightOutline = Color(0xFFDBE0EA)
    val LightOnBackground = Color(0xFF161B24)
    val LightOnSurfaceVariant = Color(0xFF5A6473)
    val LightSelected = Color(0xFFDFE8FC)
    val LightPrimary = Blue
    val LightOnPrimary = Color(0xFFFFFFFF)
    /** The accent as small text on the light background (6.7:1). */
    val LightAccentText = BlueHover
}
