package dev.grabline.app.ui.downloads

import dev.grabline.app.remote.RemoteFailure
import dev.grabline.app.remote.connection.PcLinkState
import dev.grabline.app.remote.protocol.ErrorCode
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val remote = FakePcRemote()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(): DownloadsViewModel {
        val vm = DownloadsViewModel(remote, flushScope = backgroundScope)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        return vm
    }

    private fun seed() {
        remote.jobsFlow.value = listOf(
            fakeJob("pc1", 1, "downloading", downloaded = 10, total = 100, speed = 5),
            fakeJob("pc1", 2, "completed", total = 50),
            fakeJob("pc2", 3, "failed", pcName = "Laptop", error = "Disk full"),
            fakeJob("pc2", 4, "paused", pcName = "Laptop"),
        )
    }

    @Test fun deviceAndStateChipsCombine() = runTest(dispatcher) {
        seed()
        val vm = vm()
        assertEquals(4, vm.state.value.rows.size)
        vm.toggleDevice(DeviceFilter.Pc("pc2")); runCurrent()
        assertEquals(setOf(3L, 4L), vm.state.value.rows.map { it.jobId }.toSet())
        vm.toggleState(StateFilter.Failed); runCurrent()
        assertEquals(listOf(3L), vm.state.value.rows.map { it.jobId })
        vm.toggleState(StateFilter.Active); runCurrent() // failed OR active within pc2
        assertEquals(setOf(3L, 4L), vm.state.value.rows.map { it.jobId }.toSet())
        vm.clearFilters(); runCurrent()
        assertEquals(4, vm.state.value.rows.size)
        assertTrue(vm.state.value.noFilters)
        assertEquals(listOf("Desk PC", "Laptop"), vm.state.value.pcChips.map { it.name })
    }

    @Test fun phoneChipHidesPcRows() = runTest(dispatcher) {
        seed()
        val vm = vm()
        vm.toggleDevice(DeviceFilter.Phone); runCurrent()
        assertTrue(vm.state.value.rows.isEmpty())
        assertEquals(4, vm.state.value.totalRows)
    }

    @Test fun cancelWaitsForUndoWindowBeforeSending() = runTest(dispatcher) {
        seed()
        val vm = vm()
        vm.act(vm.state.value.rows.first { it.jobId == 1L }, RowAction.Cancel); runCurrent()
        assertTrue(remote.controlCalls.isEmpty())
        assertEquals(RowStatus.Cancelled, vm.state.value.rows.first { it.jobId == 1L }.status)
        advanceTimeBy(4_900); runCurrent()
        assertTrue(remote.controlCalls.isEmpty())
        advanceTimeBy(200); runCurrent()
        assertEquals(listOf(Triple("pc1", 1L, "cancel")), remote.controlCalls)
    }

    @Test fun undoNeverSendsAndRestoresRow() = runTest(dispatcher) {
        seed()
        val vm = vm()
        vm.act(vm.state.value.rows.first { it.jobId == 1L }, RowAction.Remove); runCurrent()
        assertTrue(vm.state.value.rows.none { it.jobId == 1L })
        val offer = vm.messages.first() as DownloadMessage.UndoOffer
        vm.undo(offer.id); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertTrue(remote.controlCalls.isEmpty())
        assertEquals(RowStatus.Downloading, vm.state.value.rows.first { it.jobId == 1L }.status)
    }

    @Test fun pauseActsImmediately() = runTest(dispatcher) {
        seed()
        val vm = vm()
        vm.act(vm.state.value.rows.first { it.jobId == 1L }, RowAction.Pause); runCurrent()
        assertEquals(listOf(Triple("pc1", 1L, "pause")), remote.controlCalls)
    }

    @Test fun failedControlShowsPlainMessage() = runTest(dispatcher) {
        seed()
        remote.controlResult = Result.failure(RemoteFailure(ErrorCode.INTERNAL, "Your PC is off or on another network"))
        val vm = vm()
        vm.act(vm.state.value.rows.first { it.jobId == 1L }, RowAction.Pause); runCurrent()
        assertEquals(DownloadMessage.Error("Your PC is off or on another network"), vm.messages.first())
    }

    @Test fun offlinePcGreysRowsWithLastSeen() = runTest(dispatcher) {
        seed()
        remote.links.value = mapOf("pc1" to PcLinkState.Offline(5_000L))
        val vm = vm()
        val row = vm.state.value.rows.first { it.jobId == 1L }
        assertTrue(row.offline)
        assertEquals(5_000L, row.lastSeenAt)
        assertTrue(row.actions.isEmpty())
        val other = vm.state.value.rows.first { it.jobId == 3L }
        assertTrue(!other.offline && other.lastSeenAt == null)
    }

    @Test fun statusTextUsesPlainWords() {
        val s = object : StatusStrings {
            override fun queued() = "Queued"
            override fun downloading() = "Downloading"
            override fun paused() = "Paused"
            override fun done() = "Done"
            override fun failed(error: String?) = if (error == null) "Failed" else "Failed: $error"
            override fun cancelled() = "Cancelled"
            override fun unknown() = "Unknown"
        }
        fun text(status: String, error: String? = null) = statusText(toRow(fakeJob("p", 1, status, error = error), null), s)
        assertEquals("Queued", text("queued"))
        assertEquals("Downloading", text("downloading"))
        assertEquals("Paused", text("paused"))
        assertEquals("Done", text("completed"))
        assertEquals("Failed: Disk full", text("failed", "Disk full"))
        assertEquals("Failed", text("failed", " "))
        assertEquals("Cancelled", text("cancelled"))
        assertEquals("Unknown", text("whatever"))
    }

    @Test fun progressIsIndeterminateWithoutTotal() {
        assertEquals(null, toRow(fakeJob("p", 1, "downloading", downloaded = 5), null).progress)
        assertEquals(0.5f, toRow(fakeJob("p", 1, "downloading", downloaded = 5, total = 10), null).progress)
    }

    @Test fun multiSelectAppliesToEligibleRowsOnly() = runTest(dispatcher) {
        seed()
        val vm = vm()
        vm.toggleSelected(rowKey("pc1", 1)); vm.toggleSelected(rowKey("pc2", 4)); runCurrent()
        assertTrue(vm.state.value.selecting)
        vm.actOnSelection(RowAction.Pause); runCurrent()
        assertEquals(listOf(Triple("pc1", 1L, "pause")), remote.controlCalls) // paused row cannot pause
        assertTrue(!vm.state.value.selecting)

        vm.toggleSelected(rowKey("pc1", 2)); vm.toggleSelected(rowKey("pc2", 3)); runCurrent()
        vm.actOnSelection(RowAction.Remove); runCurrent()
        assertTrue(vm.state.value.rows.none { it.jobId == 2L || it.jobId == 3L })
        advanceTimeBy(5_100); runCurrent()
        assertEquals(setOf(Triple("pc1", 2L, "remove"), Triple("pc2", 3L, "remove")), remote.controlCalls.drop(1).toSet())
    }

    @Test fun outboxAndRefresh() = runTest(dispatcher) {
        val vm = vm()
        remote.pending.value = 2; runCurrent()
        assertEquals(2, vm.state.value.outboxPending)
        vm.refresh(); runCurrent()
        assertEquals(1, remote.refreshes)
        assertTrue(vm.state.value.refreshing)
        advanceTimeBy(1_300); runCurrent()
        assertTrue(!vm.state.value.refreshing)
    }
}
