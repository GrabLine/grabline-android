package dev.grabline.app.remote.phone

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.grabline.app.data.AppDatabase
import dev.grabline.app.data.PairedPc
import dev.grabline.app.data.PhoneJobRow
import dev.grabline.app.engine.PhoneJobStatus
import dev.grabline.app.remote.DefaultPcRemote
import dev.grabline.app.remote.connection.AuthProvider
import dev.grabline.app.remote.connection.ConnectionManager
import dev.grabline.app.remote.connection.FakePc
import dev.grabline.app.remote.connection.PcLinkState
import dev.grabline.app.remote.connection.eventually
import dev.grabline.app.remote.outbox.Outbox
import dev.grabline.app.remote.protocol.AuthRequest
import dev.grabline.app.remote.transfer.SendToPcLink
import dev.grabline.app.ui.downloads.FakePhoneEngine
import dev.grabline.app.ui.downloads.phoneJob
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Section 20 over a real [ConnectionManager] and a scripted PC: who gets what, and the order of a move. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneShareConnectionTest {
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var manager: ConnectionManager
    private val fake = FakePc()
    private val engine = FakePhoneEngine()
    private val enabled = MutableStateFlow(true)
    private val auth = AuthProvider { p, _, id ->
        AuthRequest(id, deviceId = p.deviceId, signature = "MEUCIQDEkNVli0ImyofhN_Wu6-4n3CBTJwbf9PLA_GpHelIhsAIgGE-UGdJQPUyd2T_zsX1zDXFD6yn-mSsGqKBL67H4TCM")
    }

    private fun ok(id: String) = """{"id":"$id","type":"ok","protocolVersion":6}"""

    private fun pc() = PairedPc(
        pcId = "pcA", name = "Desk", fingerprint = "ab", deviceId = "KsucBZHeyJguN6H0BzJQiw",
        addresses = listOf("10.0.0.1"), port = 47821, lastGoodAddress = "10.0.0.1", protocolVersion = 6, pairedAt = 1L,
    )

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        fake.handlers["phoneJobs"] = { id, _ -> listOf(ok(id)) }
        fake.handlers["phoneCommandResult"] = { id, _ -> listOf(ok(id)) }
    }

    @After fun tearDown() {
        manager.setWanted(false)
        scope.cancel()
        db.close()
    }

    /** Builds the manager with the phone share wired like the app does; [withFeature] is what the PC lists. */
    private fun build(withFeature: Boolean): Outbox {
        fake.authOkFeatures = listOf("jobs", "control", "global") + if (withFeature) listOf("phoneJobs") else emptyList()
        lateinit var remote: DefaultPcRemote
        val share = PhoneShare(engine, enabled, { remote })
        manager = ConnectionManager(scope, db.pcs(), db.jobs(), fake, auth, phoneShare = share)
        val outbox = Outbox(scope, db.outbox(), manager)
        remote = DefaultPcRemote(db, manager, outbox, deleteKey = {})
        val link = SendToPcLink(db.phoneJobs(), { requestId -> db.outbox().countByRequestId(requestId) > 0 }) { engine.cancel(it) }
        outbox.deliveredListener = link::onDelivered
        return outbox
    }

    private suspend fun goOnline() {
        db.pcs().upsert(pc())
        manager.setWanted(true)
        eventually(what = "Online") { manager.states.value["pcA"]?.takeIf { it is PcLinkState.Online } }
    }

    private fun frames(type: String): List<JsonObject> =
        fake.conns.flatMap { c -> c.received.map { Json.parseToJsonElement(it).jsonObject } }.filter { it["type"]?.jsonPrimitive?.content == type }

    private fun commandEvent(seq: Long, commandId: Long, action: String, jobId: Long) =
        """{"type":"event","protocolVersion":6,"event":"phone.command","seq":$seq,"ts":1,"commandId":$commandId,"action":"$action","jobId":$jobId}"""

    private fun resultFor(commandId: Long): JsonObject? =
        frames("phoneCommandResult").firstOrNull { it["commandId"]?.jsonPrimitive?.content == commandId.toString() }

    @Test fun aPcWithTheFeatureGetsTheListAndTheTopic() = runBlocking<Unit> {
        build(withFeature = true)
        engine.jobsFlow.value = listOf(phoneJob(2), phoneJob(1))
        goOnline()
        val list = eventually(what = "phoneJobs") { frames("phoneJobs").firstOrNull() }
        assertEquals(true, list["shared"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(listOf(2L, 1L), list["jobs"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content.toLong() })
        val topics = frames("subscribe").single()["topics"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("jobs", "global", "phone"), topics)
    }

    @Test fun aPcWithoutTheFeatureGetsNothingFromSection20() = runBlocking<Unit> {
        build(withFeature = false)
        engine.jobsFlow.value = listOf(phoneJob(1, PhoneJobStatus.DOWNLOADING))
        goOnline()
        eventually(what = "subscribe") { frames("subscribe").firstOrNull() }
        fake.lastLive!!.push(commandEvent(1, 5, "pause", 1))
        engine.jobsFlow.value = listOf(phoneJob(1, PhoneJobStatus.DOWNLOADING).copy(downloaded = 99))
        delay(1_500)
        assertTrue("no phoneJobs", frames("phoneJobs").isEmpty())
        assertTrue("no phoneCommandResult", frames("phoneCommandResult").isEmpty())
        assertEquals(listOf("jobs", "global"), frames("subscribe").single()["topics"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue("a command from a PC without the feature is ignored", engine.calls.isEmpty())
    }

    @Test fun aCommandOnTheWireIsDoneAnsweredAndFollowedByAFreshList() = runBlocking<Unit> {
        build(withFeature = true)
        engine.jobsFlow.value = listOf(phoneJob(1, PhoneJobStatus.DOWNLOADING))
        goOnline()
        eventually(what = "first list") { frames("phoneJobs").firstOrNull() }
        fake.lastLive!!.push(commandEvent(1, 5, "pause", 1))
        val result = eventually(what = "result") { resultFor(5) }
        assertEquals("ok", result["result"]!!.jsonPrimitive.content)
        assertEquals(listOf("pause" to 1L), engine.calls)
        val lists = eventually(what = "fresh list", timeoutMs = 4_000) { frames("phoneJobs").takeIf { it.size >= 2 } }
        assertTrue(lists.size >= 2)
    }

    @Test fun anUnknownJobOverTheWireIsNotFound() = runBlocking<Unit> {
        build(withFeature = true)
        goOnline()
        eventually(what = "first list") { frames("phoneJobs").firstOrNull() }
        fake.lastLive!!.push(commandEvent(1, 6, "cancel", 404))
        assertEquals("notFound", eventually(what = "result") { resultFor(6) }["result"]!!.jsonPrimitive.content)
        assertTrue(engine.calls.isEmpty())
    }

    @Test fun withTheSettingOffTheListIsEmptyAndCommandsAreRefused() = runBlocking<Unit> {
        enabled.value = false
        build(withFeature = true)
        engine.jobsFlow.value = listOf(phoneJob(1, PhoneJobStatus.DOWNLOADING))
        goOnline()
        val list = eventually(what = "phoneJobs") { frames("phoneJobs").firstOrNull() }
        assertFalse(list["shared"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(list["jobs"]!!.jsonArray.isEmpty())
        fake.lastLive!!.push(commandEvent(1, 7, "remove", 1))
        val result = eventually(what = "result") { resultFor(7) }
        assertEquals("refused", result["result"]!!.jsonPrimitive.content)
        delay(1_300)
        assertEquals("once per connection", 1, frames("phoneJobs").size)
        assertTrue(engine.calls.isEmpty())
    }

    @Test fun moveToPcCancelsThePhoneJobOnlyAfterThePcRepliedQueued() = runBlocking<Unit> {
        build(withFeature = true)
        val rowId = db.phoneJobs().insert(PhoneJobRow(url = "https://example.com/p1.zip", name = "phone1.zip", status = "DOWNLOADING", createdAt = 1))
        assertEquals(1L, rowId)
        engine.jobsFlow.value = listOf(phoneJob(rowId, PhoneJobStatus.DOWNLOADING))
        // The PC takes its time: it holds the `queued` reply back until the test lets it go.
        val held = AtomicReference<Pair<String, String>?>(null)
        fake.handlers["download"] = { id, body ->
            held.set(id to body["requestId"]!!.jsonPrimitive.content)
            null
        }
        goOnline()
        eventually(what = "first list") { frames("phoneJobs").firstOrNull() }
        val conn = fake.lastLive!!
        conn.push(commandEvent(1, 8, "moveToPc", rowId))

        val download = eventually(what = "download request") { frames("download").firstOrNull() }
        assertEquals("https://example.com/p1.zip", download["url"]!!.jsonPrimitive.content)
        assertEquals("phone1.zip", download["title"]!!.jsonPrimitive.content)
        assertEquals("ok", eventually(what = "result") { resultFor(8) }["result"]!!.jsonPrimitive.content)
        delay(400)
        assertTrue("not cancelled while the PC has not said queued: ${engine.calls}", engine.calls.none { it.first == "cancel" })

        val (id, requestId) = held.get()!!
        conn.push("""{"id":"$id","type":"queued","protocolVersion":6,"handoffId":7,"requestId":"$requestId","duplicate":false,"appRunning":true}""")
        eventually(what = "cancel after queued") { engine.calls.firstOrNull { it == "cancel" to rowId } }
        assertEquals(1, engine.calls.count { it.first == "cancel" })
    }

    @Test fun moveToPcDoesNotCancelWhenThePcRefusesTheLink() = runBlocking<Unit> {
        build(withFeature = true)
        val rowId = db.phoneJobs().insert(PhoneJobRow(url = "https://example.com/p1.zip", name = "phone1.zip", status = "DOWNLOADING", createdAt = 1))
        engine.jobsFlow.value = listOf(phoneJob(rowId, PhoneJobStatus.DOWNLOADING))
        fake.handlers["download"] = { id, _ -> listOf(fake.error(id, "forbidden")) }
        goOnline()
        eventually(what = "first list") { frames("phoneJobs").firstOrNull() }
        fake.lastLive!!.push(commandEvent(1, 9, "moveToPc", rowId))
        eventually(what = "download request") { frames("download").firstOrNull() }
        delay(500)
        assertTrue(engine.calls.none { it.first == "cancel" })
    }
}
