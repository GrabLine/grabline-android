package dev.grabline.app.remote.phone

import dev.grabline.app.engine.PhoneJob
import dev.grabline.app.engine.PhoneJobStatus
import dev.grabline.app.engine.TorrentDetails
import dev.grabline.app.remote.PcRemote
import dev.grabline.app.remote.outbox.EnqueueResult
import dev.grabline.app.remote.protocol.Inbound
import dev.grabline.app.remote.protocol.OkReply
import dev.grabline.app.remote.protocol.PhoneCommandEvent
import dev.grabline.app.remote.protocol.PhoneCommandResultRequest
import dev.grabline.app.remote.protocol.PhoneJobsRequest
import dev.grabline.app.remote.protocol.ProtocolCodec
import dev.grabline.app.remote.protocol.Request
import dev.grabline.app.ui.downloads.FakePcRemote
import dev.grabline.app.ui.downloads.FakePhoneEngine
import dev.grabline.app.ui.downloads.phoneJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records what the phone sends on a PC connection and answers `ok` like a PC would. */
private class RecordingLink : PhoneLink {
    val sent = mutableListOf<Request>()
    override suspend fun request(build: (String) -> Request): Inbound.Reply {
        val request = build("r${sent.size + 1}")
        ProtocolCodec.encode(request) // every message must obey the protocol rules, as the real session does
        sent += request
        return Inbound.Reply(request.id, "ok", 6, OkReply(), JsonObject(emptyMap()))
    }

    val lists: List<PhoneJobsRequest> get() = sent.filterIsInstance<PhoneJobsRequest>()
    val results: List<PhoneCommandResultRequest> get() = sent.filterIsInstance<PhoneCommandResultRequest>()
}

/** Remembers what the phone asked it to send to a PC. */
private class MoveRemote(var result: EnqueueResult = EnqueueResult.Queued("req1")) : PcRemote by FakePcRemote() {
    class Call(val kind: String, val pcId: String, val phoneJobId: Long?, val url: String, val title: String?)

    val calls = mutableListOf<Call>()

    override suspend fun send(pcId: String, url: String, title: String?, quality: String?, queueId: Int?, folderId: String?): EnqueueResult {
        calls += Call("send", pcId, null, url, title)
        return result
    }

    override suspend fun sendFromPhone(pcId: String, phoneJobId: Long, url: String, title: String?, quality: String?): EnqueueResult {
        calls += Call("sendFromPhone", pcId, phoneJobId, url, title)
        return result
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneShareTest {
    private val engine = FakePhoneEngine()
    private val enabled = MutableStateFlow(true)
    private val remote = MoveRemote()
    private val link = RecordingLink()
    private val commands = PhoneShare.newCommandChannel()
    private val share = PhoneShare(engine, enabled, { remote })

    private fun TestScope.start() {
        backgroundScope.launch { share.run(link, commands, "pcA") }
        runCurrent()
    }

    private var nextCommand = 1L
    private fun command(action: String, jobId: Long) = PhoneCommandEvent(seq = nextCommand, commandId = nextCommand++, action = action, jobId = jobId)

    private fun TestScope.ask(action: String, jobId: Long): PhoneCommandResultRequest {
        val before = link.results.size
        commands.trySend(command(action, jobId))
        runCurrent()
        assertEquals("one answer to the command", before + 1, link.results.size)
        return link.results.last()
    }

    // ------------------------------------------------------------------ mapping

    @Test fun statesAndKindsUseTheSpecStrings() {
        fun status(s: PhoneJobStatus) = PhoneJobMapper.toInfo(phoneJob(1, s)).status
        assertEquals("queued", status(PhoneJobStatus.QUEUED))
        assertEquals("downloading", status(PhoneJobStatus.DOWNLOADING))
        assertEquals("paused", status(PhoneJobStatus.PAUSED))
        assertEquals("waiting", status(PhoneJobStatus.WAITING_NETWORK))
        assertEquals("waiting", status(PhoneJobStatus.WAITING_WIFI))
        assertEquals("waiting", status(PhoneJobStatus.WAITING_CHARGING))
        assertEquals("completed", status(PhoneJobStatus.COMPLETED))
        assertEquals("failed", status(PhoneJobStatus.FAILED))
        assertEquals("cancelled", status(PhoneJobStatus.CANCELLED))

        val base = phoneJob(1)
        assertEquals("direct", PhoneJobMapper.kindOf(base))
        assertEquals("video", PhoneJobMapper.kindOf(base.copy(isVideo = true)))
        assertEquals("cloud", PhoneJobMapper.kindOf(base.copy(isCloud = true)))
        assertEquals("transfer", PhoneJobMapper.kindOf(base.copy(isTransfer = true)))
        val torrent = TorrentDetails(peers = 0, uploaded = 0, ratio = 0f, sequential = false, seeding = false, files = emptyList())
        assertEquals("torrent", PhoneJobMapper.kindOf(base.copy(torrent = torrent)))
    }

    @Test fun userAndPasswordAreStrippedFromLinks() {
        assertEquals("https://host.example/a/b.zip?x=1", PhoneJobMapper.stripUserInfo("https://user:pa55@host.example/a/b.zip?x=1"))
        assertEquals("ftp://host.example/f", PhoneJobMapper.stripUserInfo("ftp://me@host.example/f"))
        assertEquals("https://host.example/@handle/post", PhoneJobMapper.stripUserInfo("https://host.example/@handle/post"))
        assertEquals("magnet:?xt=urn:btih:abc&dn=a@b", PhoneJobMapper.stripUserInfo("magnet:?xt=urn:btih:abc&dn=a@b"))
        val info = PhoneJobMapper.toInfo(phoneJob(1, url = "https://u:secret@host.example/x.bin"))
        assertEquals("https://host.example/x.bin", info.url)
    }

    @Test fun namesAreNeverPaths() {
        assertEquals("movie.mkv", PhoneJobMapper.safeName("Download/GrabLine/movie.mkv"))
        assertEquals("movie.mkv", PhoneJobMapper.safeName("C:\\Users\\me\\movie.mkv"))
        assertEquals("folder", PhoneJobMapper.safeName("a/folder/"))
        assertEquals("Download", PhoneJobMapper.safeName("  "))
        assertEquals(256, PhoneJobMapper.safeName("n".repeat(400)).length)
    }

    @Test fun fieldsFollowTheSpec() {
        val downloading = phoneJob(7, PhoneJobStatus.DOWNLOADING).copy(
            downloaded = 40, total = 100, speed = 5, etaSeconds = 12, createdAt = 1_794_700_100_999L, error = "stale",
        )
        val a = PhoneJobMapper.toInfo(downloading)
        assertEquals(1_794_700_100L, a.createdAt) // milliseconds in the database, seconds on the wire
        assertEquals(5L, a.speed)
        assertEquals(12L, a.eta)
        assertNull("error only when failed", a.error)

        val paused = PhoneJobMapper.toInfo(downloading.copy(status = PhoneJobStatus.PAUSED))
        assertEquals("speed is 0 unless downloading", 0L, paused.speed)
        assertNull(paused.eta)

        val failed = PhoneJobMapper.toInfo(downloading.copy(status = PhoneJobStatus.FAILED, error = "Your PC is off or on another network"))
        assertEquals("Your PC is off or on another network", failed.error)

        val unknownSize = PhoneJobMapper.toInfo(downloading.copy(total = null))
        assertNull(unknownSize.total)

        val transfer = PhoneJobMapper.toInfo(downloading.copy(isTransfer = true, url = "https://pc.example/f"))
        assertEquals("", transfer.url)
        assertEquals("transfer", transfer.kind)

        assertEquals(2048, PhoneJobMapper.toInfo(downloading.copy(url = "https://e.com/" + "a".repeat(5000))).url.length)
        assertEquals(512, PhoneJobMapper.toInfo(downloading.copy(status = PhoneJobStatus.FAILED, error = "e".repeat(900))).error!!.length)
    }

    @Test fun onlyTheNewestHundredAreSentInTheirOrder() = runTest {
        engine.jobsFlow.value = (150L downTo 1L).map { phoneJob(it) } // newest first, as the engine delivers
        start()
        val sent = link.lists.single()
        assertTrue(sent.shared)
        assertEquals((150L downTo 51L).toList(), sent.jobs.map { it.id })
    }

    // ------------------------------------------------------------------ reporting

    @Test fun theListGoesOutRightAfterTheConnectionIsUp() = runTest {
        engine.jobsFlow.value = listOf(phoneJob(2), phoneJob(1))
        start()
        assertEquals(1, link.sent.size)
        assertEquals(listOf(2L, 1L), link.lists.single().jobs.map { it.id })
    }

    @Test fun anEmptyPhoneStillReportsAnEmptyList() = runTest {
        start()
        val first = link.lists.single()
        assertTrue(first.shared)
        assertTrue(first.jobs.isEmpty())
    }

    @Test fun changesAreCoalescedToOneListPerSecondWithTheNewestWinning() = runTest {
        engine.jobsFlow.value = listOf(phoneJob(1))
        start()
        assertEquals(1, link.lists.size)
        for (i in 1..9) {
            engine.jobsFlow.value = listOf(phoneJob(1).copy(downloaded = i * 10L))
            advanceTimeBy(90)
            runCurrent()
        }
        assertEquals("nothing more inside the first second", 1, link.lists.size)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, link.lists.size)
        assertEquals("the newest list wins", 90L, link.lists.last().jobs.single().downloaded)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("no change, no message", 2, link.lists.size)
    }

    @Test fun aChangeAfterAQuietSecondIsSentAtOnce() = runTest {
        engine.jobsFlow.value = listOf(phoneJob(1))
        start()
        advanceTimeBy(5_000)
        engine.jobsFlow.value = listOf(phoneJob(1), phoneJob(2))
        runCurrent()
        assertEquals(2, link.lists.size)
    }

    @Test fun whenNotSharedItSendsOneEmptyListAndRefusesEveryCommand() = runTest {
        enabled.value = false
        engine.jobsFlow.value = listOf(phoneJob(1, PhoneJobStatus.DOWNLOADING), phoneJob(2, PhoneJobStatus.PAUSED))
        start()
        val only = link.lists.single()
        assertEquals(false, only.shared)
        assertTrue(only.jobs.isEmpty())

        engine.jobsFlow.value = listOf(phoneJob(3))
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("one per connection while it stays off", 1, link.lists.size)

        for (action in listOf("pause", "resume", "cancel", "retry", "remove", "moveToPc")) {
            val result = ask(action, 1)
            assertEquals(action, "refused", result.result)
            assertEquals("This phone doesn't let PCs control its downloads", result.message)
        }
        assertTrue("nothing was touched", engine.calls.isEmpty())
        assertTrue(remote.calls.isEmpty())
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("a refused command does not trigger a new list", 1, link.lists.size)
    }

    @Test fun turningTheSettingOffAndOnAgainTellsThePc() = runTest {
        engine.jobsFlow.value = listOf(phoneJob(1))
        start()
        enabled.value = false
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(false, link.lists.last().shared)
        enabled.value = true
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(true, link.lists.last().shared)
        assertEquals(listOf(1L), link.lists.last().jobs.map { it.id })
    }

    // ------------------------------------------------------------------ commands

    @Test fun pauseResumeCancelRetryRemoveDoWhatTheDownloadsScreenDoes() = runTest {
        engine.jobsFlow.value = listOf(
            phoneJob(1, PhoneJobStatus.DOWNLOADING), phoneJob(2, PhoneJobStatus.PAUSED), phoneJob(3, PhoneJobStatus.QUEUED),
            phoneJob(4, PhoneJobStatus.FAILED), phoneJob(5, PhoneJobStatus.COMPLETED), phoneJob(6, PhoneJobStatus.WAITING_WIFI),
            phoneJob(7, PhoneJobStatus.CANCELLED),
        )
        start()
        assertEquals("ok", ask("pause", 1).result)
        assertEquals("ok", ask("resume", 2).result)
        assertEquals("ok", ask("cancel", 3).result)
        assertEquals("ok", ask("retry", 4).result)
        assertEquals("ok", ask("remove", 5).result)
        assertEquals("pause while waiting for Wi-Fi", "ok", ask("pause", 6).result)
        assertEquals("retry a cancelled one", "ok", ask("retry", 7).result)
        assertEquals(
            listOf("pause" to 1L, "resume" to 2L, "cancel" to 3L, "retry" to 4L, "remove" to 5L, "pause" to 6L, "retry" to 7L),
            engine.calls,
        )
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L), link.results.map { it.commandId })
        assertTrue(link.results.all { it.message == null })
    }

    @Test fun anActionThatDoesNotFitTheStateIsRefusedWithoutTouchingAnything() = runTest {
        engine.jobsFlow.value = listOf(
            phoneJob(1, PhoneJobStatus.DOWNLOADING), phoneJob(2, PhoneJobStatus.PAUSED), phoneJob(3, PhoneJobStatus.COMPLETED),
            phoneJob(4, PhoneJobStatus.CANCELLED), phoneJob(5, PhoneJobStatus.FAILED),
        )
        start()
        for ((action, id) in listOf("resume" to 1L, "pause" to 2L, "cancel" to 3L, "retry" to 1L, "cancel" to 4L, "pause" to 5L, "resume" to 3L)) {
            val result = ask(action, id)
            assertEquals("$action $id", "refused", result.result)
            assertEquals("That can't be done to this download right now", result.message)
        }
        assertTrue(engine.calls.isEmpty())
    }

    @Test fun anUnknownJobIsNotFound() = runTest {
        engine.jobsFlow.value = listOf(phoneJob(1))
        start()
        for (action in listOf("pause", "resume", "cancel", "retry", "remove", "moveToPc")) {
            val result = ask(action, 99)
            assertEquals(action, "notFound", result.result)
        }
        assertTrue(engine.calls.isEmpty())
        assertTrue(remote.calls.isEmpty())
    }

    @Test fun anUnknownActionIsRefused() = runTest {
        engine.jobsFlow.value = listOf(phoneJob(1))
        start()
        val result = ask("explode", 1)
        assertEquals("refused", result.result)
        assertTrue(engine.calls.isEmpty())
    }

    @Test fun aCommandThatChangedSomethingIsFollowedByAFreshList() = runTest {
        engine.jobsFlow.value = listOf(phoneJob(1, PhoneJobStatus.DOWNLOADING))
        start()
        assertEquals(1, link.lists.size)
        assertEquals("ok", ask("pause", 1).result) // the fake engine leaves the list as it was
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals("a fresh list even though nothing else changed", 2, link.lists.size)
        assertTrue("the answer comes before the list", link.sent.indexOfFirst { it is PhoneCommandResultRequest } < link.sent.indexOfLast { it is PhoneJobsRequest })
    }

    @Test fun anEngineFailureIsAnsweredFailedNotCrashed() = runTest {
        val failing = object : dev.grabline.app.engine.PhoneEngine by engine {
            override suspend fun pause(jobId: Long) = throw IllegalStateException("boom")
        }
        val own = PhoneShare(failing, enabled, { remote })
        engine.jobsFlow.value = listOf(phoneJob(1, PhoneJobStatus.DOWNLOADING))
        backgroundScope.launch { own.run(link, commands, "pcA") }
        runCurrent()
        val result = ask("pause", 1)
        assertEquals("failed", result.result)
        assertEquals("The phone couldn't do that. Try again", result.message)
        // and it keeps serving commands
        assertEquals("ok", ask("remove", 1).result)
    }

    // ------------------------------------------------------------------ moveToPc

    @Test fun moveToPcSendsTheLinkToTheCommandingPcAndLeavesTheCancelToTheDelivery() = runTest {
        engine.jobsFlow.value = listOf(phoneJob(1, PhoneJobStatus.DOWNLOADING, url = "https://u:pw@e.com/a.zip"))
        start()
        val result = ask("moveToPc", 1)
        assertEquals("ok", result.result)
        val call = remote.calls.single()
        assertEquals("sendFromPhone", call.kind) // remembers the phone job so it is cancelled once the PC says `queued`
        assertEquals("pcA", call.pcId)
        assertEquals(1L, call.phoneJobId)
        assertEquals("https://u:pw@e.com/a.zip", call.url) // the PC gets the real link, only the report strips the login
        assertEquals("phone1.zip", call.title)
        assertTrue("the phone job is not cancelled by the command itself", engine.calls.none { it.first == "cancel" || it.first == "remove" })
    }

    @Test fun moveToPcWorksForEveryStateThatCanMove() = runTest {
        val states = listOf(
            PhoneJobStatus.QUEUED, PhoneJobStatus.DOWNLOADING, PhoneJobStatus.PAUSED, PhoneJobStatus.WAITING_NETWORK,
            PhoneJobStatus.WAITING_WIFI, PhoneJobStatus.WAITING_CHARGING, PhoneJobStatus.FAILED,
        )
        engine.jobsFlow.value = states.mapIndexed { i, s -> phoneJob(i + 1L, s) }
        start()
        for (i in states.indices) assertEquals(states[i].name, "ok", ask("moveToPc", i + 1L).result)
        assertEquals(states.size, remote.calls.count { it.kind == "sendFromPhone" })
    }

    @Test fun aFinishedFileIsKeptSoItIsSentWithoutTheCancelLink() = runTest {
        engine.jobsFlow.value = listOf(phoneJob(1, PhoneJobStatus.COMPLETED))
        start()
        assertEquals("ok", ask("moveToPc", 1).result)
        assertEquals("send", remote.calls.single().kind)
        assertTrue(engine.calls.isEmpty())
    }

    @Test fun aTransferIsRefusedForMoveToPc() = runTest {
        engine.jobsFlow.value = listOf(phoneJob(1, PhoneJobStatus.DOWNLOADING).copy(isTransfer = true))
        start()
        val result = ask("moveToPc", 1)
        assertEquals("refused", result.result)
        assertTrue(remote.calls.isEmpty())
        assertTrue(engine.calls.isEmpty())
    }

    @Test fun linksThePcCannotTakeAreRefusedForMoveToPc() = runTest {
        engine.jobsFlow.value = listOf(
            phoneJob(1, PhoneJobStatus.QUEUED, url = "sftp://host.example/file"),
            phoneJob(2, PhoneJobStatus.QUEUED, url = "torrent-file:my.torrent"),
            phoneJob(3, PhoneJobStatus.CANCELLED),
        )
        start()
        for (id in 1L..3L) assertEquals("job $id", "refused", ask("moveToPc", id).result)
        assertTrue(remote.calls.isEmpty())
    }

    @Test fun aDuplicateShareIsStillOkAndARemovedPcIsFailed() = runTest {
        engine.jobsFlow.value = listOf(phoneJob(1, PhoneJobStatus.PAUSED))
        start()
        remote.result = EnqueueResult.DuplicateShare
        val dup = ask("moveToPc", 1)
        assertEquals("ok", dup.result)
        assertEquals("Already on its way to this PC", dup.message)
        remote.result = EnqueueResult.PcRemoved
        val removed = ask("moveToPc", 1)
        assertEquals("failed", removed.result)
    }

    @Test fun anInvalidCommandIdIsNotAnswered() = runTest {
        engine.jobsFlow.value = listOf(phoneJob(1, PhoneJobStatus.DOWNLOADING))
        start()
        commands.trySend(PhoneCommandEvent(commandId = 0, action = "pause", jobId = 1))
        runCurrent()
        assertTrue(link.results.isEmpty())
    }

    // ------------------------------------------------------------------ hardening

    @Test fun aCommandWithAnUnusableIdOrJobIsNeitherExecutedNorAnswered() = runTest {
        engine.jobsFlow.value = listOf(phoneJob(1, PhoneJobStatus.DOWNLOADING))
        start()
        for (bad in listOf(
            PhoneCommandEvent(commandId = 0, action = "pause", jobId = 1),
            PhoneCommandEvent(commandId = -4, action = "pause", jobId = 1),
            PhoneCommandEvent(commandId = 2_147_483_648, action = "pause", jobId = 1),
            PhoneCommandEvent(commandId = 3, action = "pause", jobId = 0),
            PhoneCommandEvent(commandId = 3, action = "pause", jobId = -1),
        )) commands.trySend(bad)
        runCurrent()
        assertTrue(engine.calls.isEmpty())
        assertTrue(link.results.isEmpty())
        assertEquals("ok", ask("pause", 1).result) // a good one right after still works
    }

    @Test fun aFloodOfCommandsKeepsOnlyTheNewestFew() = runTest {
        engine.jobsFlow.value = listOf(phoneJob(1, PhoneJobStatus.DOWNLOADING))
        for (i in 1L..500L) commands.trySend(PhoneCommandEvent(commandId = i, action = "pause", jobId = 1))
        start()
        assertEquals(PhoneShare.MAX_PENDING_COMMANDS, link.results.size)
        assertEquals(500L, link.results.last().commandId)
        assertEquals(500L - PhoneShare.MAX_PENDING_COMMANDS + 1, link.results.first().commandId)
    }

    @Test fun controlCharactersAndBidiMarksAreRemovedFromNamesAndErrors() {
        val nul = 0.toChar()
        val esc = 0x1B.toChar()
        val c1 = 0x85.toChar()
        val rlo = 0x202E.toChar()
        val lri = 0x2066.toChar()
        val pdi = 0x2069.toChar()
        assertEquals("a b.mkv", PhoneJobMapper.safeName("a${nul}b.mkv"))
        assertEquals("a b c", PhoneJobMapper.safeName("a${esc}b${c1}c"))
        assertEquals("fdp.exe".replace("fdp", "ab"), PhoneJobMapper.safeName("ab${rlo}.exe").replace(" ", ""))
        assertTrue(PhoneJobMapper.safeName("x${rlo}y${lri}z${pdi}").none { it == rlo || it == lri || it == pdi })
        assertEquals("a b", PhoneJobMapper.safeName("a b")) // normal spaces stay
        assertEquals("Download", PhoneJobMapper.safeName("${nul}${esc}"))
        val job = phoneJob(1, PhoneJobStatus.FAILED).copy(name = "n${nul}x", error = "bad${nul}${rlo}thing")
        val info = PhoneJobMapper.toInfo(job)
        assertTrue(info.name.none { it.code < 0x20 })
        assertTrue(info.error!!.none { it.code < 0x20 || it == rlo })
        assertEquals(null, ProtocolCodec.validate(PhoneJobsRequest("r1", true, listOf(info))))
    }

    @Test fun theListWithAHostileJobIsStillAcceptedAndSent() = runTest {
        val nul = 0.toChar()
        engine.jobsFlow.value = listOf(phoneJob(2).copy(name = "a${nul}b"), phoneJob(1, PhoneJobStatus.FAILED).copy(error = "x${nul}y"))
        start()
        assertEquals(listOf(2L, 1L), link.lists.single().jobs.map { it.id })
    }

    @Test fun thePhoneWillNotSendANulInTheList() {
        val nul = 0.toChar()
        fun info(name: String = "a", url: String = "https://e.com/a", error: String? = null) =
            dev.grabline.app.remote.protocol.PhoneJobInfo(1, name, url, "failed", "direct", error = error)
        assertTrue(ProtocolCodec.validate(PhoneJobsRequest("r1", true, listOf(info(name = "a${nul}")))) != null)
        assertTrue(ProtocolCodec.validate(PhoneJobsRequest("r1", true, listOf(info(url = "https://e.com/${nul}")))) != null)
        assertTrue(ProtocolCodec.validate(PhoneJobsRequest("r1", true, listOf(info(error = "e${nul}")))) != null)
    }

    @Test fun localReferencesAndLinksThePcCannotTakeAreReportedWithAnEmptyUrl() {
        fun url(u: String) = PhoneJobMapper.toInfo(phoneJob(1, url = u)).url
        assertEquals("", url("torrent-file:my.torrent"))
        assertEquals("", url("content://com.android.providers/document/1"))
        assertEquals("", url("file:///storage/emulated/0/a.torrent"))
        assertEquals("", url("sftp://user:pw@host.example/file"))
        assertEquals("", url("ftp://host.example/file"))
        assertEquals("", url("s3://bucket/key"))
        assertEquals("", url("not a link"))
        assertEquals("", url("magnet:?dn=no-hash"))
        assertEquals("https://e.com/a", url("https://u:p@e.com/a"))
        assertEquals("http://e.com/a", url("http://e.com/a"))
        assertEquals("magnet:?xt=urn:btih:abc&dn=x", url("magnet:?xt=urn:btih:abc&dn=x"))
    }
}
