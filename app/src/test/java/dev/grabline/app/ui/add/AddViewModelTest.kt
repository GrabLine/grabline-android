package dev.grabline.app.ui.add

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import dev.grabline.app.data.AppSettings
import dev.grabline.app.data.OutboxItem
import dev.grabline.app.data.PairedPc
import dev.grabline.app.data.PcJobWithPc
import dev.grabline.app.remote.PcRemote
import dev.grabline.app.remote.RemoteFailure
import dev.grabline.app.remote.connection.PcLinkState
import dev.grabline.app.remote.outbox.EnqueueResult
import dev.grabline.app.remote.protocol.AnalysisReply
import dev.grabline.app.remote.protocol.ErrorCode
import dev.grabline.app.remote.protocol.ErrorMessages
import dev.grabline.app.remote.protocol.FolderInfo
import dev.grabline.app.remote.protocol.GlobalState
import dev.grabline.app.remote.protocol.Job
import dev.grabline.app.remote.protocol.QualityOption
import dev.grabline.app.remote.protocol.QueueInfo
import dev.grabline.app.remote.protocol.QueuesReply
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeRemote : PcRemote {
    val pcList = MutableStateFlow<List<PairedPc>>(emptyList())
    val links = MutableStateFlow<Map<String, PcLinkState>>(emptyMap())
    override val pcs: Flow<List<PairedPc>> = pcList
    override val linkStates: StateFlow<Map<String, PcLinkState>> = links
    override val globalStates = MutableStateFlow<Map<String, GlobalState>>(emptyMap())
    override val jobs: Flow<List<PcJobWithPc>> = flowOf(emptyList())
    override val outboxPending: Flow<Int> = flowOf(0)
    override val outboxFailed: Flow<List<OutboxItem>> = flowOf(emptyList())

    data class Sent(val pcId: String, val url: String, val title: String?, val quality: String?, val queueId: Int?, val folderId: String?)
    val sent = mutableListOf<Sent>()
    var sendResult: EnqueueResult = EnqueueResult.Queued("r")
    var analyzeResult: Result<AnalysisReply> = Result.success(AnalysisReply(url = "u"))
    var queuesResult: Result<QueuesReply> = Result.success(QueuesReply())

    override fun setForeground(foreground: Boolean) {}
    override fun refresh(pcId: String?) {}
    override suspend fun send(pcId: String, url: String, title: String?, quality: String?, queueId: Int?, folderId: String?): EnqueueResult {
        sent += Sent(pcId, url, title, quality, queueId, folderId)
        return sendResult
    }
    override suspend fun control(pcId: String, jobId: Long, action: String): Result<Job?> = Result.success(null)
    override suspend fun global(pcId: String, action: String, bytesPerSecond: Long?): Result<GlobalState> =
        Result.success(GlobalState(paused = false))
    override suspend fun queues(pcId: String) = queuesResult
    override suspend fun analyze(pcId: String, url: String) = analyzeResult
    override suspend fun addPairing(pc: PairedPc) {}
    override suspend fun unpair(pcId: String) {}
    override suspend fun dismissFailed(itemId: Long) {}
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settings: AppSettings
    private lateinit var remote: FakeRemote

    private val video = "https://www.youtube.com/watch?v=abc"
    private val online = PcLinkState.Online("PC", "1", emptySet(), "10.0.0.2")

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val file = File.createTempFile("add-test", ".preferences_pb").also { it.delete() }
        settings = AppSettings(PreferenceDataStoreFactory.create(scope = scope) { file })
        remote = FakeRemote()
        remote.pcList.value = listOf(pc("pc1", "Desk"), pc("pc2", "Laptop"))
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        scope.cancel()
    }

    private fun pc(id: String, name: String) = PairedPc(
        pcId = id, name = name, fingerprint = "f", deviceId = "d", addresses = listOf("10.0.0.2"),
        port = 1, protocolVersion = 6, pairedAt = 0,
    )

    private fun vm(links: List<String> = listOf(video), handle: SavedStateHandle = SavedStateHandle()): AddViewModel =
        AddViewModel(remote, settings, handle).also { it.init(links, null) }

    private fun AddViewModel.await(cond: (AddUiState) -> Boolean): AddUiState =
        runBlocking { withTimeout(10_000) { state.first { it.ready && cond(it) } } }

    @Test fun defaultPcIsFirstWhenNothingElseIsKnown() {
        assertEquals("pc1", vm().await { true }.selectedPcId)
    }

    @Test fun defaultDeviceSettingWins() {
        runBlocking { settings.set(AppSettings.DEFAULT_DEVICE, "pc2") }
        assertEquals("pc2", vm().await { true }.selectedPcId)
    }

    @Test fun lastUsedPcIsUsedWhenDefaultIsAsk() {
        runBlocking { settings.set(AddViewModel.LAST_PC, "pc2") }
        assertEquals("pc2", vm().await { true }.selectedPcId)
    }

    @Test fun userChoiceBeatsDefaults() {
        runBlocking { settings.set(AppSettings.DEFAULT_DEVICE, "pc2") }
        val vm = vm()
        vm.await { true }
        vm.selectPc("pc1")
        assertEquals("pc1", vm.await { it.selectedPcId == "pc1" }.selectedPcId)
    }

    @Test fun askMeansBestPreselected() {
        assertEquals("best", vm().await { true }.quality)
    }

    @Test fun defaultQualityFollowsSettings() {
        runBlocking { settings.set(AppSettings.VIDEO_QUALITY, "720p") }
        assertEquals("720p", vm().await { true }.quality)
    }

    @Test fun audioDefaultUsesAudioFormat() {
        runBlocking {
            settings.set(AppSettings.VIDEO_QUALITY, "audio")
            settings.set(AppSettings.AUDIO_FORMAT, "m4a")
        }
        assertEquals("m4a", vm().await { true }.quality)
    }

    @Test fun onlyListedQualitiesAreShownAfterAnalysis() {
        remote.links.value = mapOf("pc1" to online)
        remote.analyzeResult = Result.success(
            AnalysisReply(
                url = video, title = "Clip",
                qualities = listOf(QualityOption("1080p", "video", 1080, "mp4", 5_000_000), QualityOption("mp3", "audio", null, "mp3", 1_000_000)),
            ),
        )
        val s = vm().await { it.analysis is AnalysisUi.Ready }
        assertEquals(listOf("best", "1080p", "mp3"), s.qualityOptions)
        assertEquals(5_000_000L, s.sizes["1080p"])
        assertEquals("Clip", s.title)
    }

    @Test fun offlineSendSaysWillSendLater() {
        val vm = vm()
        vm.await { it.canStart }
        vm.startNow()
        val s = vm.await { it.sent != null }
        assertEquals(SendOutcome.WILL_SEND_LATER, s.sent!!.outcome)
        assertEquals(1, remote.sent.size)
        assertEquals("best", remote.sent[0].quality)
        assertEquals("pc1", remote.sent[0].pcId)
    }

    @Test fun onlineSendSaysSentAndRemembersPc() {
        remote.links.value = mapOf("pc1" to online)
        val vm = vm()
        vm.await { it.analysis !is AnalysisUi.Loading && it.pcOnline }
        vm.selectQuality("best")
        vm.startNow()
        val s = vm.await { it.sent != null }
        assertEquals(SendOutcome.SENT, s.sent!!.outcome)
        assertEquals("Desk", s.sent!!.pcName)
        assertEquals("pc1", runBlocking { settings.observe(AddViewModel.LAST_PC).first() })
    }

    @Test fun nonVideoLinksSendNoQuality() {
        val vm = vm(listOf("https://example.com/a.zip"))
        vm.await { it.canStart }
        vm.startNow()
        vm.await { it.sent != null }
        assertNull(remote.sent[0].quality)
    }

    @Test fun duplicateShowsAlreadySentAndStaysOpen() {
        remote.sendResult = EnqueueResult.DuplicateShare
        val vm = vm()
        vm.await { it.canStart }
        vm.startNow()
        val s = vm.await { it.notice == Notice.ALREADY_SENT }
        assertNull(s.sent)
        assertFalse(s.sending)
    }

    @Test fun drmFailureKeepsBestAndShowsDrmText() {
        val drm = "Netflix protects its media with DRM. GrabLine can't download it"
        remote.links.value = mapOf("pc1" to online)
        remote.analyzeResult = Result.failure(RemoteFailure(ErrorCode.FAILED, drm))
        val s = vm().await { it.analysis is AnalysisUi.Failed }
        val failed = s.analysis as AnalysisUi.Failed
        assertEquals(FailureReason.DRM, failed.reason)
        assertEquals(drm, failed.message)
        assertEquals(listOf("best"), s.qualityOptions)
        assertTrue(s.canStart)
    }

    @Test fun noMediaMakesItNotDownloadable() {
        remote.links.value = mapOf("pc1" to online)
        remote.analyzeResult = Result.failure(RemoteFailure(ErrorCode.FAILED, ErrorMessages.failedByReason.getValue("noMedia")))
        val s = vm().await { it.notDownloadable }
        assertFalse(s.canStart)
    }

    @Test fun pageLinkIsNotDownloadable() {
        val s = vm(listOf("https://example.com/about")).await { true }
        assertTrue(s.notDownloadable)
        assertFalse(s.canStart)
    }

    @Test fun otherFailureIsPlainWords() {
        remote.links.value = mapOf("pc1" to online)
        remote.analyzeResult = Result.failure(RemoteFailure(ErrorCode.INTERNAL, ErrorMessages.forCode(ErrorCode.INTERNAL)))
        val failed = vm().await { it.analysis is AnalysisUi.Failed }.analysis as AnalysisUi.Failed
        assertEquals(FailureReason.OTHER, failed.reason)
        assertEquals("Your PC hit a problem. Try again", failed.message)
    }

    @Test fun queuesAndFoldersLoadWhenOnlineWithDefaultFolderFirst() {
        remote.links.value = mapOf("pc1" to online)
        remote.queuesResult = Result.success(
            QueuesReply(listOf(QueueInfo(3, "Night")), listOf(FolderInfo("fa", "Music"), FolderInfo("fb", "Downloads", isDefault = true))),
        )
        val vm = vm(listOf("https://example.com/a.zip"))
        val s = vm.await { it.folders.isNotEmpty() }
        assertEquals("fb", s.folders.first().id)
        vm.selectQueue(3)
        vm.selectFolder("fa")
        vm.startNow()
        vm.await { it.sent != null }
        assertEquals(3, remote.sent[0].queueId)
        assertEquals("fa", remote.sent[0].folderId)
    }

    @Test fun stateIsRestoredFromSavedStateHandle() {
        val first = SavedStateHandle()
        val vm1 = vm(handle = first)
        vm1.await { true }
        vm1.selectPc("pc2")
        vm1.selectQuality("720p")
        vm1.toggleLinks()
        vm1.await { it.quality == "720p" }

        val restored = SavedStateHandle(first.keys().associateWith { first.get<Any?>(it) })
        val s = AddViewModel(remote, settings, restored).also { it.init(listOf("https://other.example/x"), null) }.await { true }
        assertEquals("pc2", s.selectedPcId)
        assertEquals("720p", s.quality)
        assertEquals(listOf(video), s.links)
        assertTrue(s.linksExpanded)
    }
}
