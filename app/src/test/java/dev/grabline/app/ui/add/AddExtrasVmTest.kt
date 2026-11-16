package dev.grabline.app.ui.add

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import dev.grabline.app.data.AppSettings
import dev.grabline.app.data.PairedPc
import dev.grabline.app.remote.PcRemote
import dev.grabline.app.ui.downloads.FakePcRemote
import dev.grabline.app.ui.downloads.FakePhoneEngine
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class TwoPcRemote(list: List<PairedPc>) : PcRemote by FakePcRemote() {
    override val pcs: Flow<List<PairedPc>> = MutableStateFlow(list)
}

/** Start later, checksum and clip in the Add sheet's state, and that they stay off a PC target. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddExtrasVmTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settings: AppSettings
    private val phone = FakePhoneEngine()
    private val file = "https://example.com/a.zip"
    private val video = "https://www.youtube.com/watch?v=abc"
    private val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = File.createTempFile("add-extras", ".preferences_pb").also { it.delete() }
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
        AddViewModel(TwoPcRemote(pcs), settings, SavedStateHandle(), phone).also { it.init(links, null) }

    private fun AddViewModel.await(cond: (AddUiState) -> Boolean = { true }): AddUiState =
        runBlocking { withTimeout(10_000) { state.first { it.ready && cond(it) } } }

    @Test fun startLaterAndChecksumAreOfferedForThePhoneOnly() {
        val vm = vm(listOf(file), listOf(pc("a"), pc("b")))
        val onPc = vm.await()
        assertEquals("a", onPc.selectedPcId)
        assertFalse(onPc.canStartLater)
        assertFalse(onPc.showChecksum)
        vm.selectPc("b")
        assertFalse(vm.await { it.selectedPcId == "b" }.canStartLater)
        vm.selectPhone()
        val onPhone = vm.await { it.deviceIsPhone }
        assertTrue(onPhone.canStartLater && onPhone.showChecksum)
    }

    @Test fun aPcTargetIgnoresAStartTimeSetEarlier() {
        val vm = vm(listOf(file), listOf(pc("a")))
        vm.selectPhone()
        vm.setStartLater(5_000L)
        assertEquals(5_000L, vm.await { it.deviceIsPhone }.startAt)
        vm.selectPc("a")
        assertNull(vm.await { !it.deviceIsPhone }.startAt)
    }

    @Test fun startAtAndChecksumReachThePhoneEngine() {
        val vm = vm(listOf(file))
        vm.await()
        vm.setStartLater(9_000L)
        vm.setChecksum("SHA256:${hash.uppercase()}")
        vm.await { it.startAt == 9_000L }
        vm.startNow()
        vm.await { it.sent != null }
        val request = phone.added.single()
        assertEquals(9_000L, request.startAt)
        assertEquals(hash, request.sha256)
    }

    @Test fun aJunkChecksumBlocksStartUntilFixedOrCleared() {
        val vm = vm(listOf(file))
        vm.await()
        vm.setChecksum("not a checksum")
        val s = vm.await { !it.checksumValid }
        assertFalse(s.canStart)
        vm.setChecksum("")
        assertTrue(vm.await { it.checksumValid }.canStart)
    }

    @Test fun clipGoesToThePhoneVideoJobAndABadClipBlocksStart() {
        val vm = vm(listOf(video))
        vm.await()
        vm.setClipStart("1:00")
        vm.setClipEnd("2:30")
        vm.await { it.clipEnd == "2:30" }
        vm.startNow()
        vm.await { it.sent != null }
        val request = phone.added.single()
        assertEquals(60L, request.clipStartSec)
        assertEquals(150L, request.clipEndSec)

        val bad = vm(listOf(video))
        bad.await()
        bad.setClipStart("5:00")
        bad.setClipEnd("1:00")
        val s = bad.await { !it.clipValid }
        assertTrue(s.showClip)
        assertFalse(s.canStart)
    }
}
