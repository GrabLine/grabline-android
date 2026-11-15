package dev.grabline.app.a11y

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.grabline.app.ui.theme.GrabLineTheme
import dev.grabline.app.ui.theme.ThemeMode
import kotlin.math.pow
import org.junit.Assert.assertTrue

/** Shared setup and checks for the accessibility tests (QA-41, QA-42, QA-44, RTL). */

/** Shows [content] on a 360 x 640 dp layout with the given font scale, theme and direction. */
fun ComposeContentTestRule.showScreen(
    fontScale: Float = 1f,
    mode: ThemeMode = ThemeMode.Light,
    direction: LayoutDirection = LayoutDirection.Ltr,
    onScheme: (ColorScheme) -> Unit = {},
    content: @Composable () -> Unit,
) {
    setContent {
        val base = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(base.density, fontScale),
            LocalLayoutDirection provides direction,
        ) {
            GrabLineTheme(themeMode = mode) {
                val scheme = MaterialTheme.colorScheme
                SideEffect { onScheme(scheme) }
                Box(Modifier.size(360.dp, 640.dp)) { content() }
            }
        }
    }
}

/** QA-41: every node that can be clicked says what it does; targets are at least 48 dp; headings exist. */
fun ComposeContentTestRule.assertAccessible(screen: String) {
    waitForIdle()
    val nodes = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()
    val unlabelled = nodes.filter { n ->
        val c = n.config
        val description = c.getOrNull(SemanticsProperties.ContentDescription)?.any { it.isNotBlank() } == true
        val text = c.getOrNull(SemanticsProperties.Text)?.any { it.text.isNotBlank() } == true
        // A text field is named by its label text, which sits in a child that is not merged.
        val field = c.getOrNull(SemanticsProperties.EditableText) != null
        !(description || text || field)
    }
    assertTrue("$screen: clickable nodes without a label: ${unlabelled.map { it.config }}", unlabelled.isEmpty())

    val small = nodes.filter { n ->
        val b = n.touchBoundsInRoot
        // Scrolled partly out of its viewport (a chip at the row's end, a button below the fold):
        // the visible part is smaller than the control, which says nothing about its real size.
        if (b.width < n.size.width - 1 || b.height < n.size.height - 1) return@filter false
        density.run { b.width.toDp().value < 47.5f || b.height.toDp().value < 47.5f }
    }
    assertTrue("$screen: tap targets under 48 dp: ${small.map { it.touchBoundsInRoot }}", small.isEmpty())

    val headings = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes()
    assertTrue("$screen: no heading marked", headings.isNotEmpty())
}

/** QA-42: the text of every Text node that overflowed its box (empty when nothing is clipped). */
fun ComposeContentTestRule.clippedTexts(): List<String> {
    waitForIdle()
    val nodes = onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
        useUnmergedTree = true,
    ).fetchSemanticsNodes()
    val clipped = mutableListOf<String>()
    runOnIdle {
        nodes.forEach { n ->
            val results = mutableListOf<TextLayoutResult>()
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            val r = results.firstOrNull() ?: return@forEach
            // The semantics result is laid out without the real constraints, so lay the text out
            // again at the width the node actually got and compare with the box it was given.
            val li = r.layoutInput
            val m = TextMeasurer(li.fontFamilyResolver, li.density, li.layoutDirection).measure(
                text = li.text,
                style = li.style,
                overflow = li.overflow,
                softWrap = li.softWrap,
                maxLines = li.maxLines,
                constraints = Constraints(maxWidth = n.size.width + 1),
            )
            val widest = (0 until m.lineCount).maxOfOrNull { m.getLineRight(it) - m.getLineLeft(it) } ?: 0f
            if (m.multiParagraph.didExceedMaxLines || m.multiParagraph.height > n.size.height + 1 ||
                widest > n.size.width + 1
            ) {
                clipped += li.text.text
            }
        }
    }
    return clipped
}

fun ComposeContentTestRule.assertNoClippedText(screen: String) {
    val clipped = clippedTexts()
    assertTrue("$screen: clipped text at 200% font: $clipped", clipped.isEmpty())
}

private fun lin(c: Float): Double {
    val v = c.toDouble()
    return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
}

private fun luminance(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)

/** WCAG contrast ratio, the same maths as the JVM ContrastTest. */
fun contrastRatio(a: Color, b: Color): Double {
    val l1 = luminance(a)
    val l2 = luminance(b)
    return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
}
