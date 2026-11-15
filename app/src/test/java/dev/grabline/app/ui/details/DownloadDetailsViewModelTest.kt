package dev.grabline.app.ui.details

import dev.grabline.app.ui.downloads.FakePcRemote
import dev.grabline.app.ui.downloads.RowAction
import dev.grabline.app.ui.downloads.RowStatus
import dev.grabline.app.ui.downloads.fakeJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
class DownloadDetailsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val remote = FakePcRemote()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun cancelShowsCancelledThenSendsAfterUndoWindow() = runTest(dispatcher) {
        remote.jobsFlow.value = listOf(fakeJob("pc1", 7, "downloading", downloaded = 1, total = 10, speed = 1))
        val vm = DownloadDetailsViewModel(remote, "pc1", 7, flushScope = backgroundScope)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertEquals(RowStatus.Downloading, vm.state.value.row?.status)
        vm.act(RowAction.Cancel); runCurrent()
        assertEquals(RowStatus.Cancelled, vm.state.value.row?.status)
        assertTrue(remote.controlCalls.isEmpty())
        advanceTimeBy(5_100); runCurrent()
        assertEquals(1, remote.controlCalls.size)
    }

    @Test fun removeKeepsRowVisibleUntilSentThenLeaves() = runTest(dispatcher) {
        remote.jobsFlow.value = listOf(fakeJob("pc1", 7, "completed", total = 10))
        val vm = DownloadDetailsViewModel(remote, "pc1", 7, flushScope = backgroundScope)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        vm.act(RowAction.Remove); runCurrent()
        assertTrue(vm.state.value.removing && vm.state.value.row != null)
        advanceTimeBy(5_100); runCurrent()
        remote.jobsFlow.value = emptyList(); runCurrent() // the PC's event removes it
        assertTrue(vm.state.value.leave)
    }
}
