package dev.grabline.app.remote.connection

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.grabline.app.data.AppDatabase
import dev.grabline.app.data.PairedPc
import dev.grabline.app.remote.protocol.AuthRequest
import dev.grabline.app.remote.protocol.ErrorCode
import dev.grabline.app.remote.protocol.PingRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionManagerTest {
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var manager: ConnectionManager
    private val fake = FakePc()
    private val removed = CopyOnWriteArrayList<String>()
    private val auth = AuthProvider { p, _, id ->
        AuthRequest(id, deviceId = p.deviceId, signature = "MEUCIQDEkNVli0ImyofhN_Wu6-4n3CBTJwbf9PLA_GpHelIhsAIgGE-UGdJQPUyd2T_zsX1zDXFD6yn-mSsGqKBL67H4TCM")
    }

    private fun pc(lastGood: String? = null, addresses: List<String> = listOf("10.0.0.1", "10.0.0.2")) = PairedPc(
        pcId = "pcA", name = "Desk", fingerprint = "ab", deviceId = "KsucBZHeyJguN6H0BzJQiw",
        addresses = addresses, port = 47821, lastGoodAddress = lastGood, protocolVersion = 6, pairedAt = 1L,
    )

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        manager = ConnectionManager(scope, db.pcs(), db.jobs(), fake, auth, onPairingRemoved = { removed += it.pcId })
    }

    @After fun tearDown() {
        manager.setWanted(false)
        scope.cancel()
        db.close()
    }

    private suspend fun waitState(what: String, timeoutMs: Long = 8_000, check: (PcLinkState?) -> Boolean) =
        eventually(timeoutMs, what) { manager.states.value["pcA"]?.takeIf { check(it) } }

    private suspend fun online() = waitState("Online") { it is PcLinkState.Online } as PcLinkState.Online

    private suspend fun jobIds() = db.jobs().observeForPc("pcA").first().map { it.jobId }.sorted()

    @Test fun lastGoodUnreachableFallsBackToOtherAddress() = runBlocking<Unit> { // QA-12
        db.pcs().upsert(pc(lastGood = "10.0.0.1"))
        fake.modes["10.0.0.1"] = FakePc.Mode.Unreachable(1_500)
        fake.authOkAddresses = listOf("10.0.0.1", "10.0.0.2")
        val start = System.currentTimeMillis()
        manager.setWanted(true)
        val state = online()
        assertTrue("took ${System.currentTimeMillis() - start} ms", System.currentTimeMillis() - start < 10_000)
        assertEquals("10.0.0.2", state.address)
        eventually(what = "lastGoodAddress stored") { db.pcs().get("pcA")?.lastGoodAddress?.takeIf { it == "10.0.0.2" } }
        assertEquals(listOf("10.0.0.1", "10.0.0.2"), fake.attempts.toList())
    }

    @Test fun authOkAddressesPortAndNameAreStored() = runBlocking<Unit> {
        db.pcs().upsert(pc(lastGood = "10.0.0.1"))
        fake.authOkAddresses = listOf("10.0.0.1", "10.9.9.9")
        fake.authOkPort = 5555
        fake.authOkName = "New Name"
        manager.setWanted(true)
        val state = online()
        assertEquals("New Name", state.pcName)
        assertEquals("1.4.0", state.appVersion)
        val stored = eventually(what = "pc updated") { db.pcs().get("pcA")?.takeIf { it.port == 5555 } }
        assertEquals("New Name", stored.name)
        assertEquals(listOf("10.0.0.1", "10.9.9.9"), stored.addresses)
        assertEquals("1.4.0", stored.pcAppVersion)
    }

    @Test fun subscribeThenJobsPagingFillsCache() = runBlocking<Unit> {
        db.pcs().upsert(pc(lastGood = "10.0.0.1"))
        fake.jobPages = listOf(listOf(jobJson(3), jobJson(2)), listOf(jobJson(1, "completed")))
        manager.setWanted(true)
        eventually(what = "3 rows") { jobIds().takeIf { it == listOf(1L, 2L, 3L) } }
        val conn = fake.lastLive!!
        assertEquals(listOf("auth", "subscribe", "jobs", "jobs"), conn.requests().map { it.second })
        assertEquals("completed", db.jobs().observeForPc("pcA").first().first { it.jobId == 1L }.status)
        assertEquals(1, manager.globalStates.value["pcA"]?.active)
    }

    @Test fun eventsUpdateRows() = runBlocking<Unit> {
        db.pcs().upsert(pc(lastGood = "10.0.0.1"))
        fake.jobPages = listOf(listOf(jobJson(3), jobJson(2)))
        manager.setWanted(true)
        eventually(what = "2 rows") { jobIds().takeIf { it == listOf(2L, 3L) } }
        val conn = fake.lastLive!!
        conn.push(jobEvent(1, "job.progress", 3, ""","downloaded":500,"speed":7"""))
        eventually(what = "progress applied") { db.jobs().observeForPc("pcA").first().firstOrNull { it.jobId == 3L && it.downloaded == 500L } }
        conn.push(jobObjEvent(2, "job.added", jobJson(4, "queued")))
        eventually(what = "job added") { jobIds().takeIf { it == listOf(2L, 3L, 4L) } }
        conn.push(jobEvent(3, "job.removed", 2))
        eventually(what = "job removed") { jobIds().takeIf { it == listOf(3L, 4L) } }
        // progress for the live row kept the rest of the row
        assertEquals("f3", db.jobs().observeForPc("pcA").first().first { it.jobId == 3L }.name)
    }

    @Test fun seqGapReloadsJobs() = runBlocking<Unit> {
        db.pcs().upsert(pc(lastGood = "10.0.0.1"))
        fake.jobPages = listOf(listOf(jobJson(1)))
        manager.setWanted(true)
        eventually(what = "initial row") { jobIds().takeIf { it == listOf(1L) } }
        val conn = fake.lastLive!!
        conn.push(jobEvent(1, "job.progress", 1))
        fake.jobPages = listOf(listOf(jobJson(1), jobJson(9)))
        conn.push(jobEvent(7, "job.progress", 1)) // 2..6 missed
        eventually(what = "reloaded rows") { jobIds().takeIf { it == listOf(1L, 9L) } }
        assertEquals(2, conn.requests().count { it.second == "jobs" })
    }

    @Test fun close4403StopsEverythingAndDeletesRows() = runBlocking<Unit> { // QA-06
        db.pcs().upsert(pc(lastGood = "10.0.0.1"))
        fake.jobPages = listOf(listOf(jobJson(1)))
        manager.setWanted(true)
        eventually(what = "row") { jobIds().takeIf { it.isNotEmpty() } }
        fake.lastLive!!.closeFromPc(4403)
        waitState("PairingRemoved") { it == PcLinkState.PairingRemoved }
        eventually(what = "callback") { removed.toList().takeIf { it == listOf("pcA") } }
        eventually(what = "rows deleted") { jobIds().takeIf { it.isEmpty() } }
        val attempts = fake.attempts.size
        delay(2_000)
        assertEquals(attempts, fake.attempts.size)
        assertEquals(PcLinkState.PairingRemoved, manager.states.value["pcA"])
        assertEquals(listOf("pcA"), removed.toList())
        assertTrue("the row stays, marked removed", db.pcs().get("pcA")?.removedAt != null)
    }

    @Test fun removedStateSurvivesANewConnectionManager() = runBlocking<Unit> {
        db.pcs().upsert(pc(lastGood = "10.0.0.1"))
        fake.authMode = FakePc.AuthMode.UNAUTHORIZED_REPLY
        manager.setWanted(true)
        waitState("PairingRemoved") { it == PcLinkState.PairingRemoved }
        eventually(what = "row marked") { db.pcs().get("pcA")?.removedAt }
        manager.setWanted(false)
        val attempts = fake.attempts.size

        manager = ConnectionManager(scope, db.pcs(), db.jobs(), fake, auth, onPairingRemoved = { removed += it.pcId })
        manager.setWanted(true)
        waitState("PairingRemoved after restart") { it == PcLinkState.PairingRemoved }
        delay(1_500)
        assertEquals("no connection loop for a removed PC", attempts, fake.attempts.size)
        assertTrue(manager.isRemoved("pcA"))
        val outbox = dev.grabline.app.remote.outbox.Outbox(scope, db.outbox(), manager)
        assertEquals(dev.grabline.app.remote.outbox.EnqueueResult.PcRemoved, outbox.enqueue("pcA", "https://e.com/a"))
        assertTrue(db.outbox().pending("pcA").isEmpty())
    }

    @Test fun rePairingClearsTheRemovedMarkerAndReconnects() = runBlocking<Unit> {
        db.pcs().upsert(pc(lastGood = "10.0.0.1").copy(removedAt = 5L))
        manager.setWanted(true)
        waitState("PairingRemoved") { it == PcLinkState.PairingRemoved }
        db.pcs().upsert(pc(lastGood = "10.0.0.1").copy(keyAlias = "new", removedAt = null))
        online()
    }

    private fun sweeping(vararg swept: String) {
        manager = ConnectionManager(scope, db.pcs(), db.jobs(), fake, auth, sweeper = AddressSweeper { swept.toList() })
    }

    @Test fun pinMismatchOnASweptAddressIsNotTerminal() = runBlocking<Unit> { // QA-12
        db.pcs().upsert(pc(lastGood = "10.0.0.1"))
        fake.modes["10.0.0.1"] = FakePc.Mode.Unreachable()
        fake.modes["10.0.0.2"] = FakePc.Mode.Unreachable()
        fake.modes["10.0.0.50"] = FakePc.Mode.WrongKey
        fake.authOkAddresses = listOf("10.0.0.51")
        sweeping("10.0.0.50", "10.0.0.51")
        manager.setWanted(true)
        val state = online()
        assertEquals("10.0.0.51", state.address)
        assertTrue(fake.attemptsTo("10.0.0.50") >= 1)
    }

    @Test fun onlyAStrangerOnTheSweepStaysOffline() = runBlocking<Unit> {
        db.pcs().upsert(pc(lastGood = "10.0.0.1"))
        fake.modes["10.0.0.1"] = FakePc.Mode.Unreachable()
        fake.modes["10.0.0.2"] = FakePc.Mode.Unreachable()
        fake.modes["10.0.0.50"] = FakePc.Mode.WrongKey
        sweeping("10.0.0.50")
        manager.setWanted(true)
        waitState("Offline") { it is PcLinkState.Offline }
        delay(500)
        assertTrue(manager.states.value["pcA"] !is PcLinkState.IdentityChanged)
    }

    @Test fun lastGoodMismatchIsNotTerminalWhileAnotherAddressWorks() = runBlocking<Unit> {
        db.pcs().upsert(pc(lastGood = "10.0.0.1"))
        fake.modes["10.0.0.1"] = FakePc.Mode.WrongKey
        manager.setWanted(true)
        assertEquals("10.0.0.2", online().address)
        assertTrue(manager.states.value["pcA"] !is PcLinkState.IdentityChanged)
    }

    /**
     * The removal persists first, and that database change makes the loop watcher cancel this PC's loop, which is
     * the coroutine still finishing the removal. The key deletion must not be cut off by that (it was, now and then,
     * on slower CI machines; a slow key store makes it happen every time).
     */
    @Test fun aSlowKeyDeletionStillFinishesAfterThePairingIsRemoved() = runBlocking<Unit> {
        val slowRemoved = CopyOnWriteArrayList<String>()
        val slow = ConnectionManager(scope, db.pcs(), db.jobs(), fake, auth, onPairingRemoved = {
            delay(300)
            slowRemoved += it.pcId
        })
        db.pcs().upsert(pc(lastGood = "10.0.0.1"))
        fake.authMode = FakePc.AuthMode.UNAUTHORIZED_REPLY
        slow.setWanted(true)
        eventually(what = "the slow key deletion") { slowRemoved.firstOrNull() }
        assertEquals(PcLinkState.PairingRemoved, slow.states.value["pcA"])
    }

    @Test fun unauthorizedDuringAuthIsPairingRemoved() = runBlocking<Unit> {
        db.pcs().upsert(pc(lastGood = "10.0.0.1"))
        fake.authMode = FakePc.AuthMode.UNAUTHORIZED_REPLY
        manager.setWanted(true)
        waitState("PairingRemoved") { it == PcLinkState.PairingRemoved }
        eventually(what = "callback") { removed.firstOrNull() }
        val attempts = fake.attempts.size
        delay(1_500)
        assertEquals(attempts, fake.attempts.size)
    }

    @Test fun pinMismatchIsIdentityChangedWithoutReconnect() = runBlocking<Unit> { // QA-07
        db.pcs().upsert(pc(lastGood = "10.0.0.1"))
        fake.defaultMode = FakePc.Mode.WrongKey
        manager.setWanted(true)
        waitState("IdentityChanged") { it == PcLinkState.IdentityChanged }
        val attempts = fake.attempts.size
        delay(2_500)
        assertEquals(attempts, fake.attempts.size)
        assertEquals(PcLinkState.IdentityChanged, manager.states.value["pcA"])
        assertTrue(removed.isEmpty())
    }

    @Test fun pcTooOldIsTerminal() = runBlocking<Unit> { // QA-17
        db.pcs().upsert(pc(lastGood = "10.0.0.1"))
        fake.helloFrame = fake.hello(5)
        manager.setWanted(true)
        waitState("PcTooOld") { it == PcLinkState.PcTooOld }
    }

    @Test fun close1001GoesOfflineThenReconnects() = runBlocking<Unit> {
        db.pcs().upsert(pc(lastGood = "10.0.0.1"))
        val seen = CopyOnWriteArrayList<PcLinkState>()
        val collector = scope.launch { manager.states.collect { it["pcA"]?.let(seen::add) } }
        manager.setWanted(true)
        online()
        val first = fake.lastLive!!
        first.closeFromPc(1001)
        // Offline can last only a moment before the reconnect starts, so look for
        // it in everything the collector saw rather than polling the current state.
        eventually(what = "Offline") { seen.firstOrNull { it is PcLinkState.Offline } }
        eventually(what = "reconnected") { fake.liveConns.firstOrNull { it !== first } }
        online()
        collector.cancel()
        val offlineAt = seen.indexOfFirst { it is PcLinkState.Offline }
        assertTrue(offlineAt >= 0)
        assertTrue(seen.drop(offlineAt).any { it is PcLinkState.Online })
        assertEquals(2, fake.attemptsTo("10.0.0.1"))
    }

    @Test fun setWantedFalseClosesAndStopsReconnecting() = runBlocking<Unit> {
        db.pcs().upsert(pc(lastGood = "10.0.0.1"))
        manager.setWanted(true)
        online()
        val conn = fake.lastLive!!
        manager.setWanted(false)
        eventually(what = "socket closed") { conn.takeIf { it.closed } }
        assertTrue(manager.states.value["pcA"] is PcLinkState.Offline)
        val attempts = fake.attempts.size
        delay(2_000)
        assertEquals(attempts, fake.attempts.size)
        try {
            manager.request("pcA") { PingRequest(it) }
            fail("expected RemoteException")
        } catch (e: RemoteException) {
            assertEquals(ErrorCode.INTERNAL, e.code)
        }
    }

    @Test fun requestWhileOfflineThrows() = runBlocking<Unit> {
        try {
            manager.request("pcA") { PingRequest(it) }
            fail("expected RemoteException")
        } catch (e: RemoteException) {
            assertEquals(ErrorCode.INTERNAL, e.code)
        }
    }

    @Test fun requestWhileOnlineReturnsReply() = runBlocking<Unit> {
        db.pcs().upsert(pc(lastGood = "10.0.0.1"))
        manager.setWanted(true)
        online()
        val reply = manager.request("pcA") { PingRequest(it) }
        assertEquals("pong", reply.type)
    }
}
