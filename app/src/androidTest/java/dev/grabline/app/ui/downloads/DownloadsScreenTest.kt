package dev.grabline.app.ui.downloads

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.grabline.app.ui.theme.GrabLineTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DownloadsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val remote = FakePcRemote()

    private fun show() {
        compose.setContent {
            GrabLineTheme { DownloadsRoute(remote, onOpenDetails = { _, _ -> }, onAddClick = {}) }
        }
    }

    @Test fun emptyStateInvitesToShareALink() {
        show()
        compose.onNodeWithText("Share a link to GrabLine, or tap +").assertExists()
    }

    @Test fun rowDescriptionIncludesPercentAndTimeLeft() {
        // 45 of 100 MB at 460 kB/s leaves a little under 2 minutes.
        remote.jobsFlow.value = listOf(
            fakeJob("pc1", 1, "downloading", downloaded = 45_000_000, total = 100_000_000, speed = 460_000),
        )
        show()
        compose.onNodeWithContentDescription("45 percent, 2 minutes left", substring = true).assertExists()
        compose.onNodeWithContentDescription("Desk PC", substring = true).assertExists()
    }

    private fun startCancel() {
        remote.jobsFlow.value = listOf(fakeJob("pc1", 1, "downloading", downloaded = 1, total = 10, speed = 1))
        show()
        compose.onNodeWithContentDescription("file1.zip", substring = true).performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Cancel").performClick()
    }

    @Test fun undoStopsTheCancelRequest() {
        startCancel()
        compose.onNodeWithText("Undo").performClick()
        Thread.sleep(6_500)
        compose.waitForIdle()
        assertTrue(remote.controlCalls.isEmpty())
    }

    @Test fun cancelIsSentWhenUndoTimesOut() {
        startCancel()
        assertTrue(remote.controlCalls.isEmpty())
        compose.waitUntil(timeoutMillis = 9_000) { remote.controlCalls.isNotEmpty() }
        assertEquals(Triple("pc1", 1L, "cancel"), remote.controlCalls.single())
    }
}
