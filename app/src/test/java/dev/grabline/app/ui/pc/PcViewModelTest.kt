package dev.grabline.app.ui.pc

import dev.grabline.app.remote.connection.PcLinkState
import dev.grabline.app.remote.protocol.GlobalState
import dev.grabline.app.ui.devices.FakeRemote
import dev.grabline.app.ui.devices.PcStatus
import dev.grabline.app.ui.devices.SpeedCap
import dev.grabline.app.ui.devices.SpeedUnit
import dev.grabline.app.ui.devices.testPc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
class PcViewModelTest {
    private val remote = FakeRemote()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        remote.pcList.value = listOf(testPc())
        remote.links.value = mapOf("pc1" to PcLinkState.Online("Office PC", "2.4.0", emptySet(), "x"))
        remote.globals.value = mapOf("pc1" to GlobalState(paused = false, speed = 2_000_000, active = 1, todayBytes = 5_000))
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = PcViewModel(remote, "pc1")

    @Test fun speedCapConvertsUnitsToBytes() {
        assertEquals(500L * 1024, SpeedCap.toBytesPerSecond("500", SpeedUnit.KB))
        assertEquals(2L * 1024 * 1024, SpeedCap.toBytesPerSecond("2", SpeedUnit.MB))
        assertEquals(1_572_864L, SpeedCap.toBytesPerSecond("1,5", SpeedUnit.MB))
        assertNull(SpeedCap.toBytesPerSecond("0", SpeedUnit.MB))
        assertNull(SpeedCap.toBytesPerSecond("abc", SpeedUnit.KB))
        assertNull(SpeedCap.toBytesPerSecond("0.5", SpeedUnit.KB)) // below the protocol's 1,024 B/s minimum
        assertEquals("3" to SpeedUnit.MB, SpeedCap.fromBytesPerSecond(3L * 1024 * 1024))
        assertEquals("512" to SpeedUnit.KB, SpeedCap.fromBytesPerSecond(512L * 1024))
    }

    @Test fun savingACapSendsBytesPerSecond() {
        val v = vm()
        v.openSpeedCap()
        v.onCapOff(false)
        v.onCapText("500")
        v.onCapUnit(SpeedUnit.KB)
        v.saveSpeedCap()
        assertEquals("setSpeedLimit" to 512_000L, remote.globalCalls.single())
        assertEquals(PcDialog.None, v.state.value.dialog)
    }

    @Test fun badCapShowsAnErrorAndSendsNothing() {
        val v = vm()
        v.openSpeedCap()
        v.onCapOff(false)
        v.onCapText("-3")
        v.saveSpeedCap()
        assertTrue(v.state.value.capError)
        assertTrue(remote.globalCalls.isEmpty())
    }

    @Test fun capOffClearsTheLimit() {
        val v = vm()
        v.openSpeedCap() // no limit set, so the switch starts on
        v.saveSpeedCap()
        assertEquals("clearSpeedLimit" to null, remote.globalCalls.single())
    }

    @Test fun pauseAllThenResumeAll() {
        val v = vm()
        v.pauseOrResumeAll()
        remote.globals.value = mapOf("pc1" to GlobalState(paused = true))
        v.pauseOrResumeAll()
        assertEquals(listOf("pauseAll", "resumeAll"), remote.globalCalls.map { it.first })
    }

    @Test fun todayTotalIsHiddenWhenAbsent() {
        assertEquals(5_000L, vm().state.value.todayBytes)
        remote.globals.value = mapOf("pc1" to GlobalState(paused = false))
        assertNull(vm().state.value.todayBytes)
    }

    @Test fun queuesLoadWhenOnline() {
        val q = vm().state.value.queues
        assertEquals(listOf("Main", "Night"), q.map { it.name })
        assertTrue(q[1].paused)
    }

    @Test fun defaultQueueIsStoredOnThePairing() {
        val v = vm()
        v.chooseDefaultQueue(2)
        assertEquals(2, remote.pcList.value.single().defaultQueueId)
        v.chooseDefaultQueue(null)
        assertNull(remote.pcList.value.single().defaultQueueId)
    }

    @Test fun unpairNeedsTheConfirmDialog() {
        val v = vm()
        var done = false
        v.confirmUnpair { done = true } // no dialog yet: ignored
        assertTrue(remote.unpaired.isEmpty())
        assertFalse(done)
        v.askUnpair()
        assertEquals(PcDialog.ConfirmUnpair, v.state.value.dialog)
        assertTrue(remote.unpaired.isEmpty()) // asking alone removes nothing
        v.dismissDialog()
        v.confirmUnpair { done = true }
        assertTrue(remote.unpaired.isEmpty())
        v.askUnpair()
        v.confirmUnpair { done = true }
        assertEquals(listOf("pc1"), remote.unpaired)
        assertTrue(done)
    }

    @Test fun statusFollowsTheLink() {
        val v = vm()
        assertEquals(PcStatus.Online, v.state.value.status)
        remote.links.value = mapOf("pc1" to PcLinkState.PairingRemoved)
        assertEquals(PcStatus.PairingRemoved, v.state.value.status)
    }
}
