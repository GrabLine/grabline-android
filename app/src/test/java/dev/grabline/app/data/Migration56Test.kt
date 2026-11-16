package dev.grabline.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration56Test {
    /** A real version-4 database goes through the whole chain; the schema-6 columns have safe defaults and work. */
    @Test fun transferColumnsAndFetchableArriveWithDefaults() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase("migration-56.db")
        val schema = JSONObject(File("schemas/dev.grabline.app.data.AppDatabase/4.json").readText()).getJSONObject("database")
        val raw = ctx.openOrCreateDatabase("migration-56.db", Context.MODE_PRIVATE, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            raw.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices")
            for (j in 0 until (indices?.length() ?: 0)) {
                raw.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        raw.execSQL(
            "INSERT INTO paired_pcs (pcId, name, fingerprint, deviceId, addresses, port, protocolVersion, pairedAt, notifyActivity) " +
                "VALUES ('p', 'Desk', 'ab', 'd', '[]', 47821, 6, 1, 1)",
        )
        raw.execSQL("INSERT INTO pc_jobs (pcId, jobId, url, name, status, downloaded, updatedAt) VALUES ('p', 1, 'u', 'n', 'completed', 0, 1)")
        raw.execSQL(
            "INSERT INTO phone_jobs (url, name, status, downloaded, connections, acceptRanges, mobileOverride, createdAt, kind) " +
                "VALUES ('http://x/y.bin', 'y.bin', 'QUEUED', 0, 0, 0, 0, 1, 'file')",
        )
        raw.version = 4
        raw.close()

        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, "migration-56.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .allowMainThreadQueries().build()
        try {
            assertFalse("cached PC jobs are not fetchable until the PC says so", db.jobs().observeForPc("p").first().single().fetchable)
            val old = db.phoneJobs().all().single()
            assertNull(old.pcId)
            assertNull(old.remoteJobId)
            val id = db.phoneJobs().insert(
                PhoneJobRow(
                    url = "grabline://pc", name = "big.iso", status = "QUEUED", kind = PhoneJobRow.KIND_TRANSFER,
                    pcId = "p", remoteJobId = 1, createdAt = 2,
                ),
            )
            assertEquals(id, db.phoneJobs().activeTransfer("p", 1)!!.id)
            db.phoneJobs().setSource(old.id, "p", 12)
            assertNotNull(db.phoneJobs().byOutboxItem("p", 12))
        } finally {
            db.close()
            ctx.deleteDatabase("migration-56.db")
        }
    }
}