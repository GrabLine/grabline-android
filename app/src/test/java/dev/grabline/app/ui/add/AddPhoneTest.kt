package dev.grabline.app.ui.add

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import dev.grabline.app.data.AppSettings
import dev.grabline.app.data.PairedPc
import dev.grabline.app.remote.PcRemote
import dev.grabline.app.ui.downloads.FakePcRemote
import dev.grabline.app.ui.downloads.FakePhoneEngine
import dev.grabline.app.ui.downloads.PHONE_ID
import dev.grabline.app.ui.settings.RulesCodec
import java.io.File
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
        assertTrue(vm(listOf(song), pcs).await().deviceIsPhone)
        assertEquals("b", vm(listOf("magnet:?xt=urn:btih:abc"), pcs).await().selectedPcId)
        assertEquals("a", vm(listOf(file), pcs).await().selectedPcId)
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

    @Test fun videoToPhoneIsSkippedAndOfferedToAPc() {
        val vm = vm(listOf(video, file), listOf(pc("a")))
        vm.await()
        vm.selectPhone()
        val s = vm.await { it.deviceIsPhone }
        assertEquals(1, s.phoneVideoCount)
        assertEquals(1, s.phoneAddCount)
        vm.startNow()
        vm.await { it.sent != null }
        assertEquals(listOf(file), phone.added.map { it.url })
        val onlyVideo = vm(listOf(video), listOf(pc("a")))
        onlyVideo.await()
        onlyVideo.selectPhone()
        assertFalse(onlyVideo.await { it.deviceIsPhone }.canStart)
        onlyVideo.sendToPcInstead()
        assertEquals("a", onlyVideo.await { !it.deviceIsPhone }.selectedPcId)
    }
}
