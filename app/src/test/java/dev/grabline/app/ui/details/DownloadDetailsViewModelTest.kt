package dev.grabline.app.ui.details

import dev.grabline.app.data.PairedPc
import dev.grabline.app.engine.PhoneJobStatus
import dev.grabline.app.remote.PcRemote
import dev.grabline.app.ui.downloads.FakePcRemote
import dev.grabline.app.ui.downloads.FakePhoneEngine
import dev.grabline.app.ui.downloads.PHONE_ID
import dev.grabline.app.ui.downloads.phoneJob
import kotlinx.coroutines.flow.flowOf
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

    private val pairedRemote = object : PcRemote by remote {
        override val pcs = flowOf(
            listOf(
                PairedPc(
                    pcId = "pcA", name = "Desk", fingerprint = "ab", deviceId = "KsucBZHeyJguN6H0BzJQiw",
                    addresses = listOf("10.0.0.1"), port = 47821, protocolVersion = 6, pairedAt = 1L,
                ),
            ),
        )
    }

    private suspend fun kotlinx.coroutines.test.TestScope.sendTargetsFor(job: dev.grabline.app.engine.PhoneJob): List<PcChoice> {
        val engine = FakePhoneEngine()
        engine.jobsFlow.value = listOf(job)
        val vm = DownloadDetailsViewModel(pairedRemote, PHONE_ID, job.id, flushScope = backgroundScope, phone = engine)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        return vm.state.value.sendTargets
    }

    @Test fun sendToPcIsOfferedForEveryPhoneDownloadThatCanMove() = runTest(dispatcher) {
        val movable = listOf(
            PhoneJobStatus.QUEUED, PhoneJobStatus.DOWNLOADING, PhoneJobStatus.PAUSED, PhoneJobStatus.WAITING_NETWORK,
            PhoneJobStatus.WAITING_WIFI, PhoneJobStatus.WAITING_CHARGING, PhoneJobStatus.FAILED,
        )
        for (status in movable) {
            assertEquals(status.name, listOf(PcChoice("pcA", "Desk")), sendTargetsFor(phoneJob(1, status)))
        }
    }

    @Test fun sendToPcIsNotOfferedForTransfersFinishedCancelledOrLinksAPcCannotTake() = runTest(dispatcher) {
        assertTrue(sendTargetsFor(phoneJob(1, PhoneJobStatus.DOWNLOADING).copy(isTransfer = true)).isEmpty())
        assertTrue(sendTargetsFor(phoneJob(1, PhoneJobStatus.COMPLETED)).isEmpty())
        assertTrue(sendTargetsFor(phoneJob(1, PhoneJobStatus.CANCELLED)).isEmpty())
        assertTrue(sendTargetsFor(phoneJob(1, PhoneJobStatus.QUEUED, url = "sftp://host.example/f")).isEmpty())
        assertTrue(sendTargetsFor(phoneJob(1, PhoneJobStatus.QUEUED, url = "torrent-file:a.torrent")).isEmpty())
    }
}
