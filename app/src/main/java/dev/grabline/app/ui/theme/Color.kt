package dev.grabline.app.ui.theme

import androidx.compose.ui.graphics.Color

/** Dark tokens come from the website; light tokens from the desktop `app/ui/design.py`. */
object GrabLineColors {
    // Dark
    val DarkBackground = Color(0xFF07090E)
    val DarkSurface = Color(0xFF121821)
    val DarkOutline = Color(0xFF1E2736)
    val DarkOnBackground = Color(0xFFE8EEF8)
    val DarkOnSurfaceVariant = Color(0xFFA4B0C4)
    val DarkPrimary = Color(0xFF2563EB)
    val DarkOnPrimary = Color(0xFFFFFFFF)
    /** The accent as small text: the brand blue itself is only 3.8:1 on the dark background. */
    val DarkAccentText = Color(0xFF86AAFF)
    val LightAccentText = Color(0xFF0A55C9)

    // Light (desktop LIGHT palette: bg, surface, border, text, text2, accent)
    val LightBackground = Color(0xFFF3F5F9)
    val LightSurface = Color(0xFFFFFFFF)
    val LightOutline = Color(0xFFDBE0EA)
    val LightOnBackground = Color(0xFF161B24)
    val LightOnSurfaceVariant = Color(0xFF5A6473)
    val LightPrimary = Color(0xFF016CF5) // desktop #0170fd darkened slightly: white on it must reach 4.5:1
    val LightOnPrimary = Color(0xFFFFFFFF)
}
