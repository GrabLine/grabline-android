package dev.grabline.app.ui.downloads

import dev.grabline.app.engine.PhoneJobStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneRowsTest {
    private val dispatcher = StandardTestDispatcher()
    private val remote = FakePcRemote()
    private val phone = FakePhoneEngine()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(): DownloadsViewModel {
        val vm = DownloadsViewModel(remote, flushScope = backgroundScope, phone = phone)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        return vm
    }

    private val strings = object : StatusStrings {
        override fun queued() = "Queued"
        override fun downloading() = "Downloading"
        override fun paused() = "Paused"
        override fun done() = "Done"
        override fun failed(error: String?) = if (error == null) "Failed" else "Failed: $error"
        override fun cancelled() = "Cancelled"
        override fun unknown() = "Unknown"
    }

    @Test fun statusTextForEveryPhoneStatus() {
        fun text(s: PhoneJobStatus, error: String? = null) = statusText(phoneRow(phoneJob(1, s, error = error)), strings)
        assertEquals("Waiting for network", text(PhoneJobStatus.WAITING_NETWORK))
        assertEquals("Waiting for Wi-Fi", text(PhoneJobStatus.WAITING_WIFI))
        assertEquals("Waiting to charge", text(PhoneJobStatus.WAITING_CHARGING))
        assertEquals("Failed: Disk full", text(PhoneJobStatus.FAILED, "Disk full"))
        assertEquals("Done", text(PhoneJobStatus.COMPLETED))
    }

    @Test fun phoneRowsJoinTheListWithNoticeAndStableKey() = runTest(dispatcher) {
        phone.jobsFlow.value = listOf(phoneJob(7, notice = "This server can't resume; it will restart if interrupted"))
        remote.jobsFlow.value = listOf(fakeJob("pc1", 7, "downloading"))
        val vm = vm()
        val row = vm.state.value.rows.first { it.device is Device.Phone }
        assertEquals("phone:7", row.key)
        assertEquals("This server can't resume; it will restart if interrupted", row.notice)
        assertEquals(2, vm.state.value.totalRows)
        assertEquals(phoneRowKey(7), detailsRowKey(PHONE_ID, 7))
        assertEquals("pc:pc1:7", detailsRowKey("pc1", 7))
        vm.toggleDevice(DeviceFilter.Phone); runCurrent()
        assertEquals(listOf("phone:7"), vm.state.value.rows.map { it.key })
    }

    @Test fun waitingForWifiOffersDownloadNowAnyway() = runTest(dispatcher) {
        phone.jobsFlow.value = listOf(phoneJob(3, PhoneJobStatus.WAITING_WIFI))
        val vm = vm()
        val row = vm.state.value.rows.single()
        assertEquals(RowAction.AllowMobileData, row.actions.first())
        assertTrue(row.waiting)
        vm.act(row, RowAction.AllowMobileData); runCurrent()
        assertEquals(listOf("allowMobileData" to 3L), phone.calls)
        // Still an active download for the Active filter.
        vm.toggleState(StateFilter.Active); runCurrent()
        assertEquals(1, vm.state.value.rows.size)
    }

    @Test fun cancelDelaysUntilUndoWindowCloses() = runTest(dispatcher) {
        phone.jobsFlow.value = listOf(phoneJob(5))
        val vm = vm()
        vm.act(vm.state.value.rows.single(), RowAction.Cancel); runCurrent()
        assertTrue(phone.calls.isEmpty())
        assertEquals(RowStatus.Cancelled, vm.state.value.rows.single().status)
        advanceTimeBy(4_900); runCurrent()
        assertTrue(phone.calls.isEmpty())
        advanceTimeBy(200); runCurrent()
        assertEquals(listOf("cancel" to 5L), phone.calls)
    }

    @Test fun undoRemoveNeverReachesTheEngine() = runTest(dispatcher) {
        phone.jobsFlow.value = listOf(phoneJob(5))
        val vm = vm()
        vm.act(vm.state.value.rows.single(), RowAction.Remove); runCurrent()
        assertTrue(vm.state.value.rows.isEmpty())
        val offer = vm.messages.first() as DownloadMessage.UndoOffer
        vm.undo(offer.id); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertTrue(phone.calls.isEmpty())
        assertEquals(1, vm.state.value.rows.size)
    }

    @Test fun pauseAndRetryGoStraightToTheEngine() = runTest(dispatcher) {
        phone.jobsFlow.value = listOf(phoneJob(1), phoneJob(2, PhoneJobStatus.FAILED, error = "x"))
        val vm = vm()
        vm.act(vm.state.value.rows.first { it.jobId == 1L }, RowAction.Pause); runCurrent()
        vm.act(vm.state.value.rows.first { it.jobId == 2L }, RowAction.Retry); runCurrent()
        assertEquals(listOf("pause" to 1L, "retry" to 2L), phone.calls)
        assertNull(vm.state.value.rows.first { it.jobId == 1L }.fileUri)
    }
}
