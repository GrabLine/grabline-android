package dev.grabline.app.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataLayerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun pc(id: String = "pc1") = PairedPc(
        pcId = id, name = "Desk $id", fingerprint = "ab", deviceId = "d1",
        addresses = listOf("192.168.1.5", "10.0.0.2"), port = 4433, protocolVersion = 6, pairedAt = 1L,
    )

    private fun job(pcId: String, id: Long) =
        PcJob(pcId = pcId, jobId = id, url = "https://x/$id", name = "f$id", status = "queued", updatedAt = 1L)

    private fun item(req: String, pc: String = "pc1") =
        OutboxItem(requestId = req, pcId = pc, url = "https://x", createdAt = 1L)

    @Test fun pcRoundTripKeepsAddressesAndDefaults() = runBlocking {
        db.pcs().upsert(pc())
        val got = db.pcs().get("pc1")!!
        assertEquals(listOf("192.168.1.5", "10.0.0.2"), got.addresses)
        assertTrue(got.notifyActivity)
        db.pcs().updateLastGood("pc1", "10.0.0.2")
        db.pcs().touchSeen("pc1", 99L)
        val after = db.pcs().observeAll().first().single()
        assertEquals("10.0.0.2", after.lastGoodAddress)
        assertEquals(99L, after.lastSeenAt)
    }

    @Test fun outboxDuplicateRequestIdIgnored() = runBlocking {
        assertTrue(db.outbox().insert(item("r1")) > 0)
        assertEquals(-1L, db.outbox().insert(item("r1")))
        assertEquals(1, db.outbox().pending("pc1").size)
        assertEquals(1, db.outbox().observePendingCount(null).first())
    }

    @Test fun outboxStateTransitions() = runBlocking {
        val a = db.outbox().insert(item("a"))
        val b = db.outbox().insert(item("b"))
        assertEquals(listOf(a, b), db.outbox().pending("pc1").map { it.id })
        db.outbox().markSending(a)
        assertEquals(listOf(b), db.outbox().pending("pc1").map { it.id })
        db.outbox().resetSendingToPending()
        assertEquals(listOf(a, b), db.outbox().pending("pc1").map { it.id })
        assertEquals(0, db.outbox().observePendingCount("pc2").first())
        db.outbox().markDelivered(a)
        assertEquals(listOf(b), db.outbox().pending("pc1").map { it.id })
    }

    @Test fun markFailedIncrementsAttempts() = runBlocking {
        val id = db.outbox().insert(item("a"))
        db.outbox().markFailed(id, "e1")
        db.outbox().markFailed(id, "e2")
        val row = db.outbox().get(id)!!
        assertEquals(2, row.attempts)
        assertEquals("e2", row.lastError)
        assertEquals(OutboxState.FAILED, row.state)
    }

    @Test fun replaceAllForPcReplacesOnlyThatPc() = runBlocking {
        db.pcs().upsert(pc("pc1")); db.pcs().upsert(pc("pc2"))
        db.jobs().upsertAll(listOf(job("pc1", 1), job("pc1", 2), job("pc2", 1)))
        db.jobs().replaceAllForPc("pc1", listOf(job("pc1", 3)))
        assertEquals(listOf(3L), db.jobs().observeForPc("pc1").first().map { it.jobId })
        assertEquals(1, db.jobs().observeForPc("pc2").first().size)
        val joined = db.jobs().observeAll().first()
        assertEquals(setOf("Desk pc1", "Desk pc2"), joined.map { it.pcName }.toSet())
    }

    @Test fun replaceAllForPcRollsBackOnFailure() = runBlocking {
        db.pcs().upsert(pc("pc1"))
        db.jobs().upsertAll(listOf(job("pc1", 1)))
        // A job for an unknown PC violates the foreign key, so the whole transaction rolls back.
        runCatching { db.jobs().replaceAllForPc("pc1", listOf(job("pc1", 2), job("ghost", 9))) }
        assertEquals(listOf(1L), db.jobs().observeForPc("pc1").first().map { it.jobId })
    }

    @Test fun deletingPcCascadesToJobs() = runBlocking {
        db.pcs().upsert(pc("pc1"))
        db.jobs().upsertAll(listOf(job("pc1", 1), job("pc1", 2)))
        db.pcs().delete("pc1")
        assertTrue(db.jobs().observeForPc("pc1").first().isEmpty())
        assertTrue(db.jobs().observeAll().first().isEmpty())
    }

    @Test fun settingsDefaultsMatchSpec() = runBlocking {
        val s = AppSettings(PreferenceDataStoreFactory.create { File(tmp.root, "s.preferences_pb") })
        assertEquals("ask", s.observe(AppSettings.DEFAULT_DEVICE).first())
        assertEquals("system", s.observe(AppSettings.THEME).first())
        assertEquals("grabline", s.observe(AppSettings.COLOURS).first())
        assertEquals("system", s.observe(AppSettings.LANGUAGE).first())
        assertTrue(s.observe(AppSettings.OFFER_COPIED_LINKS).first())
        assertEquals("forever", s.observe(AppSettings.KEEP_HISTORY).first())
        assertTrue(s.observe(AppSettings.NOTIFY_PC_DOWNLOADS).first())
        assertEquals(8, s.observe(AppSettings.CONNECTIONS_PER_FILE).first())
        assertEquals(3, s.observe(AppSettings.DOWNLOADS_AT_ONCE).first())
        assertTrue(s.observe(AppSettings.ONLY_WIFI).first())
        assertFalse(s.observe(AppSettings.ONLY_CHARGING).first())
        assertEquals(3, s.observe(AppSettings.AUTO_RETRY).first())
        s.set(AppSettings.THEME, "dark")
        assertEquals("dark", s.observe(AppSettings.THEME).first())
        s.reset(AppSettings.THEME)
        assertEquals("system", s.observe(AppSettings.THEME).first())
    }
}
