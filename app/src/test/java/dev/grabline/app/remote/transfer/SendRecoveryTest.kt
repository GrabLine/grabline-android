package dev.grabline.app.remote.transfer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.grabline.app.data.AppDatabase
import dev.grabline.app.data.OutboxItem
import dev.grabline.app.data.OutboxState
import dev.grabline.app.data.PhoneJobRow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** QA-38 crash recovery: the PC said "queued", the process died before the phone job was cancelled. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SendRecoveryTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private suspend fun job(name: String, requestId: String?) = db.phoneJobs().insert(
        PhoneJobRow(url = "https://example.com/$name", name = name, status = "DOWNLOADING", createdAt = 1, sendRequestId = requestId),
    )

    @Test fun aSendTheOutboxAlreadyDeliveredIsCancelledOnNextStart() = runBlocking {
        val delivered = job("a.bin", "req-delivered") // outbox row gone: the PC has it
        val waiting = job("b.bin", "req-pending") // still in the outbox: not delivered yet
        val sending = job("c.bin", "req-sending")
        val plain = job("d.bin", null)
        for ((id, state) in listOf("req-pending" to OutboxState.PENDING, "req-sending" to OutboxState.SENDING)) {
            db.outbox().insert(OutboxItem(requestId = id, pcId = "pcA", url = "https://example.com/x", createdAt = 1, state = state))
        }
        val cancelled = ArrayList<Long>()
        val link = SendToPcLink(db.phoneJobs(), { requestId -> db.outbox().countByRequestId(requestId) > 0 }) { cancelled += it }

        link.recover()

        assertEquals(listOf(delivered), cancelled)
        assertNull("marker cleared once handled", db.phoneJobs().get(delivered)!!.sendRequestId)
        assertEquals("req-pending", db.phoneJobs().get(waiting)!!.sendRequestId)
        assertEquals("req-sending", db.phoneJobs().get(sending)!!.sendRequestId)
        assertNull(db.phoneJobs().get(plain)!!.sendRequestId)

        link.recover() // running it again changes nothing
        assertEquals(listOf(delivered), cancelled)
    }
}
