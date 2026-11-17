package dev.grabline.app.ui.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The app frame's snackbar host. Screens show their messages (errors, "Removed. Undo") here, so the frame's
 * Scaffold places them above the + button and the tab bar. A screen drew its own host inside the content,
 * where the + button covered it.
 */
val LocalAppSnackbar = staticCompositionLocalOf<SnackbarHostState?> { null }

/** The frame's host, or a screen-local one when there is no frame (previews, tests). */
@Composable
fun rememberAppSnackbar(): Pair<SnackbarHostState, Boolean> {
    val shared = LocalAppSnackbar.current
    val local = remember { SnackbarHostState() }
    return if (shared != null) shared to true else local to false
}
