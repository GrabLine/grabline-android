package dev.grabline.app.ui.add

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import dev.grabline.app.data.AppSettings
import dev.grabline.app.data.PairedPc
import dev.grabline.app.remote.PcRemote
import dev.grabline.app.ui.downloads.FakePcRemote
import dev.grabline.app.ui.downloads.FakePhoneEngine
import dev.grabline.app.ui.downloads.PHONE_ID
import dev.grabline.app.ui.entry.LinkKind
import dev.grabline.app.ui.entry.LinkProbe
import dev.grabline.app.ui.settings.RulesCodec
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class PcsRemote(list: List<PairedPc>) : PcRemote by FakePcRemote() {
    override val pcs: Flow<List<PairedPc>> = MutableStateFlow(list)
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddPhoneTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settings: AppSettings
    private val phone = FakePhoneEngine()
    private val video = "https://www.youtube.com/watch?v=abc"
    private val file = "https://example.com/a.zip"
    private val song = "https://example.com/a.mp3"

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = File.createTempFile("add-phone", ".preferences_pb").also { it.delete() }
        settings = AppSettings(PreferenceDataStoreFactory.create(scope = scope) { f })
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        scope.cancel()
    }

    private fun pc(id: String) = PairedPc(
        pcId = id, name = "PC $id", fingerprint = "f", deviceId = "d", addresses = listOf("10.0.0.2"),
        port = 1, protocolVersion = 6, pairedAt = 0,
    )

    private fun vm(links: List<String>, pcs: List<PairedPc> = emptyList()) =
        AddViewModel(PcsRemote(pcs), settings, SavedStateHandle(), phone).also { it.init(links, null) }

    private fun probedVm(link: String, probe: LinkProbe) =
        AddViewModel(PcsRemote(emptyList()), settings, SavedStateHandle(), phone, probe).also { it.init(listOf(link), null) }

    private fun AddViewModel.await(cond: (AddUiState) -> Boolean = { true }): AddUiState =
        runBlocking { withTimeout(10_000) { state.first { it.ready && cond(it) } } }

    @Test fun phoneIsTheTargetWhenNoPcIsPaired() {
        val s = vm(listOf(file)).await()
        assertTrue(s.deviceIsPhone && s.phoneAvailable && s.canStart)
        assertFalse(s.showQuality)
    }

    @Test fun defaultDeviceSettingPicksPhoneOverPc() {
        runBlocking { settings.set(AppSettings.DEFAULT_DEVICE, "phone") }
        assertTrue(vm(listOf(file), listOf(pc("a"))).await().deviceIsPhone)
    }

    @Test fun rulesChooseTheDeviceByType() {
        runBlocking {
            settings.set(AppSettings.DEFAULT_DEVICE, "a")
            settings.set(AppSettings.RULES, RulesCodec.encode(mapOf("music" to "phone", "torrent" to "b")))
        }
        val pcs = listOf(pc("a"), pc("b"))
        // The saved rules can arrive a moment after the first state; wait for the settled choice.
        assertTrue(vm(listOf(song), pcs).await { it.deviceIsPhone }.deviceIsPhone)
        assertEquals("b", vm(listOf("magnet:?xt=urn:btih:abc"), pcs).await { it.selectedPcId == "b" }.selectedPcId)
        assertEquals("a", vm(listOf(file), pcs).await { it.selectedPcId != null }.selectedPcId)
    }

    @Test fun manyLinksMakeManyAdds() {
        val links = listOf(file, song, "https://example.com/b.zip")
        val vm = vm(links)
        vm.await()
        vm.startNow()
        val s = vm.await { it.sent != null }
        assertEquals(links, phone.added.map { it.url })
        assertEquals(SendOutcome.ADDED_ON_PHONE, s.sent?.outcome)
        assertEquals(3, s.sent?.count)
        assertEquals(PHONE_ID, runBlocking { settings.observe(AddViewModel.LAST_PC).first() })
    }

    @Test fun savedFolderIsPassedOnlyWhenItIsATree() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3AMovies"
        val vm = vm(listOf(file))
        vm.await()
        vm.selectPhoneFolder(tree)
        vm.await { it.phoneFolder == tree }
        vm.startNow()
        vm.await { it.sent != null }
        assertEquals(tree, phone.added.single().folderUri)
    }

    @Test fun videoLinksGoToThePhoneWithTheChosenQuality() {
        val vm = vm(listOf(video, file), listOf(pc("a")))
        vm.await()
        vm.selectPhone()
        val s = vm.await { it.deviceIsPhone }
        assertEquals(2, s.phoneAddCount)
        assertTrue(s.showQuality)
        vm.selectQuality("720p")
        vm.startNow()
        vm.await { it.sent != null }
        assertEquals(listOf(video, file), phone.added.map { it.url })
        assertEquals("720p", phone.added[0].quality)
        assertEquals(null, phone.added[1].quality)
    }

    @Test fun drmLinkIsRefusedUpFrontOnThePhone() {
        val vm = vm(listOf("https://www.netflix.com/watch/123"), listOf(pc("a")))
        vm.await()
        vm.selectPhone()
        val s = vm.await { it.deviceIsPhone }
        assertEquals("Netflix protects its media with DRM. GrabLine can't download it", s.blockedMessage)
        assertTrue(s.notDownloadable)
        assertFalse(s.canStart)
    }

    @Test fun extensionlessLinkProbedAsFileCanStartOnThePhone() {
        val link = "https://example.com/tool.bin"
        val vm = probedVm(link, LinkProbe { true })
        val s = vm.await { !it.checking }
        assertFalse(s.notDownloadable)
        assertEquals(LinkKind.FILE, s.kind)
        assertTrue(s.canStart)
        assertEquals(1, s.phoneAddCount)
        vm.startNow()
        vm.await { it.sent != null }
        assertEquals(listOf(link), phone.added.map { it.url })
    }

    @Test fun linkProbedAsPageStaysANonDownloadablePage() {
        val s = probedVm("https://example.com/blog/post", LinkProbe { false }).await { !it.checking }
        assertTrue(s.notDownloadable)
        assertFalse(s.canStart)
    }

    @Test fun pageIsNotClaimedWhileTheProbeIsPending() {
        val gate = CompletableDeferred<Boolean>()
        val vm = probedVm("https://example.com/get/12345", LinkProbe { gate.await() })
        val s = vm.await()
        assertTrue(s.checking)
        assertFalse(s.notDownloadable || s.canStart)
        gate.complete(true)
        assertTrue(vm.await { !it.checking }.canStart)
    }
}
