package dev.grabline.app.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/** QA-44: text and secondary text meet WCAG AA (4.5:1) in both schemes. */
class ContrastTest {
    private fun lin(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)

    private fun ratio(a: Color, b: Color): Double {
        val l1 = luminance(a)
        val l2 = luminance(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun check(name: String, fg: Color, bg: Color) {
        val r = ratio(fg, bg)
        println("CONTRAST $name = ${"%.2f".format(r)}")
        assertTrue("$name contrast $r < 4.5", r >= 4.5)
    }

    private fun checkLarge(name: String, fg: Color, bg: Color) {
        val r = ratio(fg, bg)
        println("CONTRAST $name = ${"%.2f".format(r)}")
        assertTrue("$name contrast $r < 3.0", r >= 3.0)
    }

    @Test
    fun darkPairs() {
        val s = GrabLineDarkScheme
        check("dark onBackground/background", s.onBackground, s.background)
        check("dark onSurface/surface", s.onSurface, s.surface)
        check("dark onSurface/background", s.onSurface, s.background)
        check("dark onSurfaceVariant/background", s.onSurfaceVariant, s.background)
        check("dark onSurfaceVariant/surface", s.onSurfaceVariant, s.surface)
        check("dark onPrimary/primary", s.onPrimary, s.primary)
        // Text buttons and links use primary as small text: full 4.5:1.
        check("dark primary/background", s.primary, s.background)
        check("dark primary/surface", s.primary, s.surface)
        check("dark onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer)
        checkLarge("dark primaryContainer/background (UI, 3:1)", s.primaryContainer, s.background)
    }

    @Test
    fun lightPairs() {
        val s = GrabLineLightScheme
        check("light onBackground/background", s.onBackground, s.background)
        check("light onSurface/surface", s.onSurface, s.surface)
        check("light onSurface/background", s.onSurface, s.background)
        check("light onSurfaceVariant/background", s.onSurfaceVariant, s.background)
        check("light onSurfaceVariant/surface", s.onSurfaceVariant, s.surface)
        check("light onPrimary/primary", s.onPrimary, s.primary)
        // Text buttons and links use primary as small text: full 4.5:1.
        check("light primary/background", s.primary, s.background)
        check("light primary/surface", s.primary, s.surface)
        check("light onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer)
        checkLarge("light primaryContainer/background (UI, 3:1)", s.primaryContainer, s.background)
    }
}
