package dev.grabline.app.e2e

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.grabline.app.GrabLineApp
import dev.grabline.app.data.PcJobWithPc
import dev.grabline.app.remote.connection.PcLinkState
import dev.grabline.app.remote.outbox.EnqueueResult
import dev.grabline.app.ui.devices.PairingEntry
import dev.grabline.app.ui.devices.PairingProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Phone against a real desktop listener (P1-A16). Runs only when the driver
 * (scripts/e2e/run_e2e.py) passes the instrumentation arguments `e2eCode`,
 * `e2ePort` and `e2eMedia`; a normal `connectedDebugAndroidTest` skips it.
 * The desktop peer is scripts/e2e/phone_peer.py in the desktop repo.
 */
class PcEndToEndTest {
    private val args = InstrumentationRegistry.getArguments()
    private val code: String? = args.getString("e2eCode")
    private val port: String? = args.getString("e2ePort")
    private val media: String? = args.getString("e2eMedia")
    // 10.0.2.2 on the emulator; 127.0.0.1 on a real phone through `adb reverse`.
    private val host: String = args.getString("e2eHost") ?: "10.0.2.2"

    private val container get() = ApplicationProvider.getApplicationContext<GrabLineApp>().container

    @Test
    fun pairShareControlAndRevoke() = runBlocking {
        assumeTrue("needs the e2e driver", code != null && port != null && media != null)
        val remote = container.remote
        remote.setForeground(true)

        val before = remote.pcs.first().map { it.pcId }.toSet()
        // QA-01: manual pairing (the emulator has no camera), approved on the PC.
        val steps = container.pairing.pair(PairingEntry.Manual("$host:$port\n$code"), "E2E phone").toList()
        val last = steps.last()
        assertTrue("pairing ended with $last", last is PairingProgress.Paired)
        assertTrue("saw Confirm on your PC", steps.any { it is PairingProgress.ConfirmOnPc })
        // The PC just paired, not whichever is listed first (an earlier run may have left another pairing).
        val pc = remote.pcs.first { list -> list.any { it.pcId !in before } }.first { it.pcId !in before }

        withTimeout(20_000) { remote.linkStates.first { it[pc.pcId] is PcLinkState.Online } }

        // QA-13: a shared link becomes a PC job and its progress shows within 2 s.
        val fast = "$media/fast.bin"
        val start = System.currentTimeMillis()
        assertTrue(remote.send(pc.pcId, fast) is EnqueueResult.Queued)
        val added = withTimeout(10_000) { awaitJob(fast) { true } }
        val seenMs = System.currentTimeMillis() - start
        assertTrue("job seen after $seenMs ms", seenMs <= 5_000)
        withTimeout(60_000) { awaitJob(fast) { it.job.status == "completed" } }
        assertEquals(fast, added.job.url)

        // QA-15: the same link twice within a second gives one job.
        val slow = "$media/slow.bin"
        remote.send(pc.pcId, slow)
        assertTrue(remote.send(pc.pcId, slow) is EnqueueResult.DuplicateShare)
        val running = withTimeout(20_000) { awaitJob(slow) { it.job.status == "downloading" } }
        assertEquals(1, remote.jobs.first().count { it.job.url == slow })

        // QA-16: pause, resume, cancel, retry and remove reflect within 1 s (2 s allowed here).
        val id = running.job.jobId
        for ((action, wanted) in listOf(
            "pause" to setOf("paused"),
            "resume" to setOf("queued", "downloading"),
            "cancel" to setOf("cancelled"),
            "retry" to setOf("queued", "downloading"),
        )) {
            assertTrue("$action failed", remote.control(pc.pcId, id, action).isSuccess)
            withTimeout(3_000) { awaitJob(slow) { it.job.status in wanted } }
        }
        assertTrue(remote.control(pc.pcId, id, "remove").isSuccess)
        withTimeout(3_000) { remote.jobs.first { list -> list.none { it.job.jobId == id && it.job.pcId == pc.pcId } } }

        // Pause all / speed cap.
        assertTrue(remote.global(pc.pcId, "setSpeedLimit", 512L * 1024).getOrNull()?.speedLimit == 512L * 1024)
        assertTrue(remote.global(pc.pcId, "clearSpeedLimit").getOrNull()?.speedLimit == null)

        // QA-06: the PC removes this phone while connected.
        remote.send(pc.pcId, "$media/revoke-me")
        withTimeout(5_000) { remote.linkStates.first { it[pc.pcId] is PcLinkState.PairingRemoved } }
        Unit
    }

    private suspend fun awaitJob(url: String, predicate: (PcJobWithPc) -> Boolean): PcJobWithPc =
        container.remote.jobs.first { list -> list.any { it.job.url == url && predicate(it) } }
            .first { it.job.url == url && predicate(it) }
}
