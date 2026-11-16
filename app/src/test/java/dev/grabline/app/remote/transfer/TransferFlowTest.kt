package dev.grabline.app.remote.transfer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.grabline.app.data.AppDatabase
import dev.grabline.app.data.PairedPc
import dev.grabline.app.data.PhoneJobRow
import dev.grabline.app.remote.DefaultPcRemote
import dev.grabline.app.remote.connection.AuthProvider
import dev.grabline.app.remote.connection.ConnectionManager
import dev.grabline.app.remote.connection.FakePc
import dev.grabline.app.remote.connection.PcLinkState
import dev.grabline.app.remote.connection.eventually
import dev.grabline.app.remote.outbox.EnqueueResult
import dev.grabline.app.remote.outbox.Outbox
import dev.grabline.app.remote.protocol.AuthRequest
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferFlowTest {
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var manager: ConnectionManager
    private lateinit var outbox: Outbox
    private val fake = FakePc()
    private val auth = AuthProvider { p, _, id ->
        AuthRequest(id, deviceId = p.deviceId, signature = "MEUCIQDEkNVli0ImyofhN_Wu6-4n3CBTJwbf9PLA_GpHelIhsAIgGE-UGdJQPUyd2T_zsX1zDXFD6yn-mSsGqKBL67H4TCM")
    }

    private val pc = PairedPc(
        pcId = "pcA", name = "Desk", fingerprint = "ab", deviceId = "KsucBZHeyJguN6H0BzJQiw",
        addresses = listOf("10.0.0.1"), port = 47821, lastGoodAddress = "10.0.0.1", protocolVersion = 6, pairedAt = 1L,
    )

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        manager = ConnectionManager(scope, db.pcs(), db.jobs(), fake, auth)
        outbox = Outbox(scope, db.outbox(), manager)
        runBlocking { db.pcs().upsert(pc) }
    }

    @After fun tearDown() {
        manager.setWanted(false)
        scope.cancel()
        db.close()
    }

    private fun puller() = PcOutboxPuller(
        scope, manager,
        held = { pcId, itemId -> db.phoneJobs().byOutboxItem(pcId, itemId) != null },
        save = { pcId, item ->
            val id = db.phoneJobs().insert(PhoneJobRow(url = item.url, name = item.title ?: "x", status = "QUEUED", createdAt = 1))
            db.phoneJobs().setSource(id, pcId, item.itemId)
        },
    )

    private fun outboxFrame(id: String) =
        """{"id":"$id","type":"outbox","protocolVersion":6,"items":[""" +
            """{"itemId":12,"url":"https://e.com/a.pdf","title":"a.pdf","quality":null,"createdAt":1},""" +
            """{"itemId":13,"url":"https://e.com/b.pdf","title":"b.pdf","quality":null,"createdAt":2}]}"""

    @Test fun outboxItemsBecomePhoneJobsOnceAndAreAcked() = runBlocking {
        fake.authOkFeatures = listOf("jobs", "control", "global", "outbox")
        val acked = CopyOnWriteArrayList<List<Long>>()
        fake.handlers["outbox"] = { id, _ -> listOf(outboxFrame(id)) }
        fake.handlers["outboxAck"] = { id, body ->
            acked += body["itemIds"]!!.jsonArray.map { it.jsonPrimitive.long }
            listOf("""{"id":"$id","type":"ok","protocolVersion":6,"removed":2}""")
        }
        val puller = puller().also { it.start() }
        manager.setWanted(true)
        eventually(what = "online") { (manager.states.value["pcA"] as? PcLinkState.Online) }
        eventually(what = "two jobs") { db.phoneJobs().all().takeIf { it.size == 2 } }
        eventually(what = "ack") { acked.firstOrNull() }
        assertEquals(listOf(12L, 13L), acked.first())

        // The PC offers them again (an ack was lost) and says outbox.added: no new jobs, but acked again.
        fake.lastLive!!.push("""{"type":"event","protocolVersion":6,"event":"outbox.added","seq":1,"ts":1,"count":2}""")
        eventually(what = "second ack") { acked.takeIf { it.size >= 2 } }
        assertEquals(0, puller.pull("pcA"))
        assertEquals(2, db.phoneJobs().all().size)
        assertTrue(db.phoneJobs().all().all { it.pcId == "pcA" && it.outboxItemId in listOf(12L, 13L) })
        val topics = fake.conns.flatMap { it.received }.first { it.contains("\"subscribe\"") }
        assertTrue("subscribes to the outbox topic", Json.parseToJsonElement(topics).jsonObject["topics"].toString().contains("outbox"))
    }

    @Test fun sendToPcCancelsThePhoneJobOnlyAfterThePcQueuedIt() = runBlocking {
        fake.defaultMode = FakePc.Mode.Unreachable()
        fake.handlers["download"] = { id, body ->
            listOf("""{"id":"$id","type":"queued","protocolVersion":6,"handoffId":5,"requestId":"${body["requestId"]!!.jsonPrimitive.content}","duplicate":false,"appRunning":true}""")
        }
        val cancelled = CopyOnWriteArrayList<Long>()
        val link = SendToPcLink(db.phoneJobs()) { cancelled += it }
        outbox.deliveredListener = link::onDelivered
        outbox.start()
        val remote = DefaultPcRemote(db, manager, outbox, deleteKey = {})
        val jobId = db.phoneJobs().insert(PhoneJobRow(url = "https://e.com/big.iso", name = "big.iso", status = "DOWNLOADING", createdAt = 1))

        val result = remote.sendFromPhone("pcA", jobId, "https://e.com/big.iso", "big.iso", null)
        assertTrue(result is EnqueueResult.Queued)
        assertEquals((result as EnqueueResult.Queued).requestId, db.phoneJobs().get(jobId)!!.sendRequestId)
        delay(400)
        assertTrue("PC unreachable: the phone job keeps going", cancelled.isEmpty())

        fake.defaultMode = FakePc.Mode.Accept
        manager.setWanted(true)
        eventually(what = "phone job cancelled after queued") { cancelled.firstOrNull() }
        assertEquals(listOf(jobId), cancelled.toList())
        assertNotNull(db.phoneJobs().get(jobId))
        assertEquals(null, db.phoneJobs().get(jobId)!!.sendRequestId)
    }
}