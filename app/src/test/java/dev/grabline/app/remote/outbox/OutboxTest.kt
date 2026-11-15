package dev.grabline.app.remote.outbox

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.grabline.app.data.AppDatabase
import dev.grabline.app.data.OutboxItem
import dev.grabline.app.data.OutboxState
import dev.grabline.app.data.PairedPc
import dev.grabline.app.data.PcJob
import dev.grabline.app.remote.DefaultPcRemote
import dev.grabline.app.remote.connection.AuthProvider
import dev.grabline.app.remote.connection.ConnectionManager
import dev.grabline.app.remote.connection.FakePc
import dev.grabline.app.remote.connection.PcLinkState
import dev.grabline.app.remote.connection.eventually
import dev.grabline.app.remote.protocol.AuthRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxTest {
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var manager: ConnectionManager
    private lateinit var outbox: Outbox
    private val fake = FakePc()
    @Volatile private var now = 1_000_000L
    private val auth = AuthProvider { p, _, id ->
        AuthRequest(id, deviceId = p.deviceId, signature = "MEUCIQDEkNVli0ImyofhN_Wu6-4n3CBTJwbf9PLA_GpHelIhsAIgGE-UGdJQPUyd2T_zsX1zDXFD6yn-mSsGqKBL67H4TCM")
    }

    private fun pc(id: String = "pcA") = PairedPc(
        pcId = id, name = "Desk", fingerprint = "ab", deviceId = "KsucBZHeyJguN6H0BzJQiw",
        addresses = listOf("10.0.0.1"), port = 47821, lastGoodAddress = "10.0.0.1", protocolVersion = 6, pairedAt = 1L,
    )

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        manager = ConnectionManager(scope, db.pcs(), db.jobs(), fake, auth)
        outbox = Outbox(scope, db.outbox(), manager) { now }
        fake.defaultMode = FakePc.Mode.Unreachable()
    }

    @After fun tearDown() {
        manager.setWanted(false)
        scope.cancel()
        db.close()
    }

    private fun queuedFrame(id: String, requestId: String, handoff: Long = 7, duplicate: Boolean = false) =
        """{"id":"$id","type":"queued","protocolVersion":6,"handoffId":$handoff,"requestId":"$requestId","duplicate":$duplicate,"appRunning":true}"""

    private fun errorFrame(id: String, code: String, retryAfterMs: Long? = null): String {
        val retry = if (retryAfterMs != null) ""","retryAfterMs":$retryAfterMs""" else ""
        return """{"id":"$id","type":"error","protocolVersion":6,"code":"$code","message":"x"$retry}"""
    }

    /** Download requests received on every connection so far: (request id, requestId, url). */
    private fun downloads(): List<Triple<String, String, String>> = fake.conns.flatMap { c ->
        c.received.mapNotNull {
            val o = Json.parseToJsonElement(it) as JsonObject
            if (o["type"]?.jsonPrimitive?.content == "download") {
                Triple(o["id"]!!.jsonPrimitive.content, o["requestId"]!!.jsonPrimitive.content, o["url"]!!.jsonPrimitive.content)
            } else {
                null
            }
        }
    }

    /** A download handler that answers queued, with duplicate:true for a repeated requestId (like the PC). */
    private fun handoffs(): ConcurrentHashMap<String, Long> {
        val seen = ConcurrentHashMap<String, Long>()
        val next = AtomicInteger(100)
        fake.handlers["download"] = { id, body ->
            val rid = body["requestId"]!!.jsonPrimitive.content
            val dup = seen.containsKey(rid)
            val handoff = seen.computeIfAbsent(rid) { next.incrementAndGet().toLong() }
            listOf(queuedFrame(id, rid, handoff, dup))
        }
        return seen
    }

    private suspend fun goOnline() {
        fake.defaultMode = FakePc.Mode.Accept
        manager.setWanted(true)
        eventually(what = "Online") { manager.states.value["pcA"]?.takeIf { it is PcLinkState.Online } }
    }

    private suspend fun pendingCount() = db.outbox().observePendingCount(null).first()

    @Test fun offlineEnqueueThenDeliveredOnceWithSameRequestId() = runBlocking<Unit> { // QA-14
        db.pcs().upsert(pc())
        handoffs()
        val queued = outbox.enqueue("pcA", "https://e.com/a") as EnqueueResult.Queued
        assertEquals(1, pendingCount())
        assertEquals(OutboxState.PENDING, db.outbox().pending("pcA").single().state)
        assertEquals(queued.requestId, db.outbox().pending("pcA").single().requestId)
        assertTrue(downloads().isEmpty())

        outbox.start()
        goOnline()
        eventually(what = "item deleted") { pendingCount().takeIf { it == 0 } }
        delay(300)
        assertEquals(listOf(queued.requestId), downloads().map { it.second })
        assertTrue(db.outbox().observeFailed().first().isEmpty())
    }

    @Test fun lostReplyKeepsItemAndResendsSameRequestIdOnce() = runBlocking<Unit> { // QA-14
        db.pcs().upsert(pc())
        val handoffs = ConcurrentHashMap<String, Long>() // what the PC actually handed off
        val calls = AtomicInteger(0)
        fake.handlers["download"] = { id, body ->
            val rid = body["requestId"]!!.jsonPrimitive.content
            val dup = handoffs.containsKey(rid)
            handoffs.putIfAbsent(rid, 1L) // the PC acted on it...
            if (calls.incrementAndGet() == 1) {
                fake.lastLive!!.closeFromPc(1001) // ...but the reply never arrives
                null
            } else {
                listOf(queuedFrame(id, rid, 1, dup))
            }
        }
        outbox.start()
        goOnline()
        val queued = outbox.enqueue("pcA", "https://e.com/a") as EnqueueResult.Queued
        eventually(15_000, "resent after reconnect") { downloads().takeIf { it.size >= 2 } }
        eventually(what = "item deleted after duplicate:true") { pendingCount().takeIf { it == 0 } }
        val sent = downloads()
        assertEquals(2, sent.size)
        assertEquals(setOf(queued.requestId), sent.map { it.second }.toSet())
        assertEquals(1, handoffs.size) // exactly one handoff on the PC side
        assertTrue(db.outbox().observeFailed().first().isEmpty())
    }

    @Test fun duplicateShareWithinTwoSecondsIsDropped() = runBlocking<Unit> { // QA-15
        val first = outbox.enqueue("pcA", "https://e.com/a") as EnqueueResult.Queued
        now += 1_500
        assertEquals(EnqueueResult.DuplicateShare, outbox.enqueue("pcA", "https://e.com/a"))
        assertEquals(1, db.outbox().pending("pcA").size)
        // another URL or another PC is not a duplicate
        assertTrue(outbox.enqueue("pcA", "https://e.com/b") is EnqueueResult.Queued)
        assertTrue(outbox.enqueue("pcB", "https://e.com/a") is EnqueueResult.Queued)
        now += 2_500
        val again = outbox.enqueue("pcA", "https://e.com/a") as EnqueueResult.Queued
        assertNotEquals(first.requestId, again.requestId)
        assertEquals(2, db.outbox().pending("pcA").count { it.url == "https://e.com/a" })
    }

    private suspend fun refusal(code: String) {
        db.pcs().upsert(pc())
        fake.handlers["download"] = { id, _ -> listOf(errorFrame(id, code)) }
        outbox.start()
        goOnline()
        val q = outbox.enqueue("pcA", "https://e.com/x") as EnqueueResult.Queued
        val failed = eventually(what = "failed row for $code") { db.outbox().observeFailed().first().firstOrNull() }
        assertEquals(q.requestId, failed.requestId)
        assertEquals(OutboxState.FAILED, failed.state)
        assertEquals(code, failed.lastError)
        assertEquals(0, pendingCount())
        delay(300)
        assertEquals(1, downloads().size) // refused for good: never resent
    }

    @Test fun badRequestFails() = runBlocking<Unit> { refusal("badRequest") }
    @Test fun forbiddenFails() = runBlocking<Unit> { refusal("forbidden") }
    @Test fun notFoundFails() = runBlocking<Unit> { refusal("notFound") }
    @Test fun unsupportedFails() = runBlocking<Unit> { refusal("unsupported") }

    @Test fun rateLimitedIsRetriedAfterTheWait() = runBlocking<Unit> {
        db.pcs().upsert(pc())
        val calls = AtomicInteger(0)
        fake.handlers["download"] = { id, body ->
            val rid = body["requestId"]!!.jsonPrimitive.content
            if (calls.incrementAndGet() == 1) listOf(errorFrame(id, "rateLimited", 600)) else listOf(queuedFrame(id, rid))
        }
        outbox.start()
        goOnline()
        val start = System.currentTimeMillis()
        val q = outbox.enqueue("pcA", "https://e.com/x") as EnqueueResult.Queued
        eventually(what = "item delivered") { pendingCount().takeIf { it == 0 } }
        assertTrue("waited retryAfterMs", System.currentTimeMillis() - start >= 550)
        assertEquals(listOf(q.requestId, q.requestId), downloads().map { it.second })
        assertTrue(db.outbox().observeFailed().first().isEmpty())
    }

    @Test fun internalErrorLeavesItemPending() = runBlocking<Unit> {
        db.pcs().upsert(pc())
        fake.handlers["download"] = { id, _ -> listOf(errorFrame(id, "internal")) }
        outbox.start()
        goOnline()
        val q = outbox.enqueue("pcA", "https://e.com/x") as EnqueueResult.Queued
        val row = eventually(what = "pending with lastError") {
            db.outbox().pending("pcA").firstOrNull()?.takeIf { it.lastError == "internal" }
        }
        assertEquals(q.requestId, row.requestId)
        assertEquals(OutboxState.PENDING, row.state)
        assertEquals(1, pendingCount())
        assertTrue(db.outbox().observeFailed().first().isEmpty())
    }

    @Test fun itemsAreSentOldestFirst() = runBlocking<Unit> {
        db.pcs().upsert(pc())
        handoffs()
        val urls = listOf("https://e.com/1", "https://e.com/2", "https://e.com/3", "https://e.com/4")
        urls.forEach {
            outbox.enqueue("pcA", it)
            now += 10
        }
        outbox.start()
        goOnline()
        eventually(what = "all delivered") { pendingCount().takeIf { it == 0 } }
        assertEquals(urls, downloads().map { it.third })
    }

    @Test fun startResetsInterruptedSendingRows() = runBlocking<Unit> {
        db.pcs().upsert(pc())
        val rid = "AAAAAAAAAAAAAAAAAAAAAA"
        val id = db.outbox().insert(OutboxItem(requestId = rid, pcId = "pcA", url = "https://e.com/a", createdAt = 1L, state = OutboxState.SENDING))
        assertEquals(OutboxState.SENDING, db.outbox().get(id)!!.state)
        outbox.start() // PC stays unreachable
        eventually(what = "reset to pending") { db.outbox().get(id)?.takeIf { it.state == OutboxState.PENDING } }
        assertEquals(rid, db.outbox().get(id)!!.requestId)
    }

    @Test fun requestIdFormat() {
        val ids = (1..200).map { outbox.newRequestId() }
        val pattern = Regex("^[A-Za-z0-9_-]{16,64}$")
        ids.forEach {
            assertEquals(22, it.length)
            assertTrue(it, pattern.matches(it))
        }
        assertEquals(ids.size, ids.toSet().size)
    }

    // ---- DefaultPcRemote

    private fun remote(keys: MutableList<String> = CopyOnWriteArrayList()) =
        DefaultPcRemote(db, manager, outbox) { keys += it }

    @Test fun backgroundKeepsConnectionsWhileItemsPendingAndStopsOtherwise() = runBlocking<Unit> {
        db.pcs().upsert(pc())
        fake.defaultMode = FakePc.Mode.Accept
        val remote = remote()
        remote.setForeground(true)
        eventually(what = "Online") { manager.states.value["pcA"]?.takeIf { it is PcLinkState.Online } }
        val conn = fake.lastLive!!

        remote.onPendingCount(1)
        remote.setForeground(false)
        delay(1_000)
        assertTrue("connection kept for pending items", !conn.closed)
        assertTrue(manager.states.value["pcA"] is PcLinkState.Online)

        remote.onPendingCount(0)
        eventually(what = "socket closed") { conn.takeIf { it.closed } }
        assertTrue(manager.states.value["pcA"] is PcLinkState.Offline)

        // backgrounded with nothing pending: nothing connects
        val attempts = fake.attempts.size
        remote.setForeground(false)
        delay(800)
        assertEquals(attempts, fake.attempts.size)
    }

    @Test fun unpairDeletesEverythingEvenWhenPcIsUnreachable() = runBlocking<Unit> {
        db.pcs().upsert(pc())
        db.jobs().upsertAll(listOf(PcJob(pcId = "pcA", jobId = 1, url = "https://x/1", name = "f1", status = "queued", updatedAt = 1L)))
        val keys = CopyOnWriteArrayList<String>()
        val remote = remote(keys)
        remote.unpair("pcA") // PC unreachable (never connected)
        assertNull(db.pcs().get("pcA"))
        assertTrue(db.jobs().observeForPc("pcA").first().isEmpty())
        assertEquals(listOf("pcA"), keys.toList())
    }
}
