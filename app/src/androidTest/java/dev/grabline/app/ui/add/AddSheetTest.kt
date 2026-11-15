package dev.grabline.app.ui.add

import android.content.Context
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import dev.grabline.app.data.AppSettings
import dev.grabline.app.data.OutboxItem
import dev.grabline.app.data.PairedPc
import dev.grabline.app.data.PcJobWithPc
import dev.grabline.app.remote.PcRemote
import dev.grabline.app.remote.connection.PcLinkState
import dev.grabline.app.remote.outbox.EnqueueResult
import dev.grabline.app.remote.protocol.AnalysisReply
import dev.grabline.app.remote.protocol.GlobalState
import dev.grabline.app.remote.protocol.Job
import dev.grabline.app.remote.protocol.QueuesReply
import dev.grabline.app.ui.theme.GrabLineTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Rule
import org.junit.Test

private class UiFakeRemote : PcRemote {
    override val pcs: Flow<List<PairedPc>> = flowOf(
        listOf(PairedPc("pc1", "Desk PC", "f", "d", listOf("10.0.0.2"), 1, protocolVersion = 6, pairedAt = 0)),
    )
    override val linkStates: StateFlow<Map<String, PcLinkState>> = MutableStateFlow(emptyMap())
    override val globalStates = MutableStateFlow<Map<String, GlobalState>>(emptyMap())
    override val jobs: Flow<List<PcJobWithPc>> = flowOf(emptyList())
    override val outboxPending: Flow<Int> = flowOf(0)
    override val outboxFailed: Flow<List<OutboxItem>> = flowOf(emptyList())
    override fun setForeground(foreground: Boolean) {}
    override fun refresh(pcId: String?) {}
    override suspend fun send(pcId: String, url: String, title: String?, quality: String?, queueId: Int?, folderId: String?) =
        EnqueueResult.Queued("r")
    override suspend fun control(pcId: String, jobId: Long, action: String): Result<Job?> = Result.success(null)
    override suspend fun global(pcId: String, action: String, bytesPerSecond: Long?): Result<GlobalState> =
        Result.success(GlobalState(paused = false))
    override suspend fun queues(pcId: String): Result<QueuesReply> = Result.success(QueuesReply())
    override suspend fun analyze(pcId: String, url: String): Result<AnalysisReply> = Result.success(AnalysisReply(url))
    override suspend fun addPairing(pc: PairedPc) {}
    override suspend fun unpair(pcId: String) {}
    override suspend fun dismissFailed(itemId: Long) {}
}

class AddSheetTest {
    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings: AppSettings = run {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = File(ctx.cacheDir, "add-${System.nanoTime()}.preferences_pb")
        AppSettings(PreferenceDataStoreFactory.create(scope = scope) { file })
    }
    private val link = "https://www.youtube.com/watch?v=abc"

    @After fun tearDown() = scope.cancel()

    @Test fun showsLinkAndPcSegment() {
        compose.setContent {
            GrabLineTheme { AddSheetRoute(UiFakeRemote(), settings, listOf(link), null, onDismiss = {}) }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTextCount(link) > 0
        }
        compose.onNodeWithText(link).assertExists()
        compose.onNodeWithContentDescription("Desk PC, Offline").assertExists()
    }

    @Test fun rotationKeepsTheChosenQuality() {
        val tester = StateRestorationTester(compose)
        tester.setContent {
            GrabLineTheme { AddSheetRoute(UiFakeRemote(), settings, listOf(link), null, onDismiss = {}) }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("720p") > 0 }
        compose.onNodeWithText("720p").performClick()
        compose.onNodeWithText("720p").assertIsSelected()
        tester.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("720p") > 0 }
        compose.onNodeWithText("720p").assertIsSelected()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodesWithText(text).fetchSemanticsNodes().size
}
