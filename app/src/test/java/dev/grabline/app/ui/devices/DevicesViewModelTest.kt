package dev.grabline.app.ui.devices

import androidx.test.core.app.ApplicationProvider
import dev.grabline.app.remote.connection.PcLinkState
import dev.grabline.app.remote.protocol.GlobalState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DevicesViewModelTest {
    private val remote = FakeRemote()
    private val pairing = FakePairing {
        flowOf(PairingProgress.Connecting, PairingProgress.ConfirmOnPc, PairingProgress.Paired("Office PC"))
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = DevicesViewModel(remote, pairing, "Pixel")

    private fun text(res: Int) = ApplicationProvider.getApplicationContext<android.app.Application>().getString(res)

    @Test fun everyLinkStateMapsToTheSpecWords() {
        val expected = listOf(
            PcLinkState.Offline(null) to "Your PC is off or on another network",
            PcLinkState.PcTooOld to "Update GrabLine on your PC",
            PcLinkState.IdentityChanged to "Your PC's identity changed. If you reinstalled GrabLine, pair again",
            PcLinkState.PairingRemoved to "This PC no longer accepts this phone. Pair again to reconnect",
            PcLinkState.PhoneTooOld to "Update GrabLine on this phone",
            PcLinkState.Online("PC", "2.4", emptySet(), "1.2.3.4") to "Online",
            PcLinkState.Connecting to "Connecting",
        )
        for ((state, words) in expected) assertEquals(words, text(pcStatusOf(state).labelRes()))
        assertEquals(PcStatus.Connecting, pcStatusOf(null))
    }

    @Test fun rowsShowStatusVersionAndActiveJobs() {
        remote.pcList.value = listOf(testPc("a", "Office"), testPc("b", "Laptop"))
        remote.links.value = mapOf("a" to PcLinkState.Online("Office", "2.5.0", emptySet(), "x"), "b" to PcLinkState.Offline(5))
        remote.globals.value = mapOf("a" to GlobalState(paused = false, active = 3), "b" to GlobalState(paused = false, active = 2))
        val rows = vm().state.value.rows!!
        assertEquals(PcStatus.Online, rows[0].status)
        assertEquals("2.5.0", rows[0].version)
        assertEquals(3, rows[0].activeJobs)
        assertEquals(PcStatus.Offline, rows[1].status)
        assertEquals(0, rows[1].activeJobs) // stale count hidden when offline
        assertEquals("2.4.0", rows[1].version) // falls back to the stored version
    }

    @Test fun emptyListIsEmptyNotLoading() {
        assertEquals(emptyList<PcRowUi>(), vm().state.value.rows)
    }

    @Test fun refreshAsksForRetry() {
        vm().refresh()
        assertEquals(1, remote.refreshed)
    }

    @Test fun scanRunsPairingToSuccess() {
        val v = vm()
        v.onScanned(VALID_QR)
        assertEquals(PairingProgress.Paired("Office PC"), v.state.value.pairing)
        assertTrue(pairing.entries.single() is PairingEntry.Scanned)
        v.dismissPairing()
        assertNull(v.state.value.pairing)
    }

    @Test fun notAPairingCodeFailsWithoutCallingThePairingClient() {
        val v = vm()
        v.onScanned("https://example.com")
        val failed = v.state.value.pairing as PairingProgress.Failed
        assertFalse(failed.canRetry)
        assertTrue(failed.message.isNotBlank())
        assertTrue(pairing.entries.isEmpty())
    }

    @Test fun failureWithRetryRunsAgain() {
        var n = 0
        val p = FakePairing {
            flowOf(if (n++ == 0) PairingProgress.Failed("Code expired. Show a new code on your PC", true) else PairingProgress.Paired("X"))
        }
        val v = DevicesViewModel(remote, p, "Pixel")
        v.onScanned(VALID_QR)
        assertEquals("Code expired. Show a new code on your PC", (v.state.value.pairing as PairingProgress.Failed).message)
        v.retryPairing()
        assertEquals(PairingProgress.Paired("X"), v.state.value.pairing)
        assertEquals(2, p.entries.size)
    }

    @Test fun cameraDeniedOpensManualEntry() {
        val v = vm()
        v.onCameraDenied()
        assertTrue(v.state.value.manualOpen)
    }

    @Test fun manualEntryValidatesEachField() {
        val v = vm()
        v.onCameraDenied()
        v.onForm(ManualForm(address = "not an address", port = "80", code = "short"))
        v.submitManual()
        val f = v.state.value.form
        assertTrue(f.addressError)
        assertTrue(f.portError)
        assertTrue(f.codeError)
        assertTrue(pairing.entries.isEmpty())
    }

    @Test fun manualEntryNormalisesTheCodeAndPairs() {
        val v = vm()
        v.onCameraDenied()
        v.onForm(ManualForm(address = "192.168.1.20", port = "49152", code = "abcd-efgh-jkmn"))
        v.submitManual()
        val entry = pairing.entries.single() as PairingEntry.Manual
        assertEquals("192.168.1.20:49152\nABCDEFGHJKMN", entry.text)
        v.dismissPairing()
        assertFalse(v.state.value.manualOpen) // success closes the manual screen
    }

    @Test fun manualPortIsOptionalAndAddressMayCarryIt() {
        assertEquals("pc.local:47821\nABCDEFGHJKMN", ManualForm("pc.local", "", "ABCDEFGHJKMN").entryText())
        assertEquals("10.0.0.2:5000\nABCDEFGHJKMN", ManualForm("10.0.0.2:5000", "", "ABCDEFGHJKMN").entryText())
        assertNull(ManualForm("10.0.0.2", "1023", "ABCDEFGHJKMN").entryText())
    }

    private companion object {
        const val VALID_QR = "grabline-pair:?v=1&a=192.168.1.5&p=49152&f=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "&c=ABCDEFGHJKMN&i=AAAAAAAAAAAAAAAAAAAAAA&n=Office&x=9999999999"
    }
}
