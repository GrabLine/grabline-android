package dev.grabline.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneMigrationTest {
    /** Builds a real version-2 database from the exported schema, migrates it, and uses the new tables. */
    @Test fun migration2To3KeepsRowsAndAddsPhoneTables() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase("migration-23.db")
        val schema = JSONObject(File("schemas/dev.grabline.app.data.AppDatabase/2.json").readText()).getJSONObject("database")
        val raw = ctx.openOrCreateDatabase("migration-23.db", Context.MODE_PRIVATE, null)
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
                "VALUES ('pc1', 'Desk', 'ab', 'dev1', '[\"10.0.0.2\"]', 4433, 6, 1, 1)",
        )
        raw.version = 2
        raw.close()

        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, "migration-23.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).allowMainThreadQueries().build()
        try {
            assertEquals("Desk", db.pcs().get("pc1")!!.name)
            val dao = db.phoneJobs()
            val id = dao.insert(PhoneJobRow(url = "http://x/y.bin", name = "y.bin", status = "QUEUED", createdAt = 1))
            dao.replaceSegments(id, listOf(PhoneSegmentRow(id, 0, 0, 100, 10)))
            assertEquals(10L, dao.segments(id).single().done)
            dao.delete(id)
            assertEquals("segments go with their job", 0, dao.segments(id).size)
            assertNotNull(dao.all())
        } finally {
            db.close()
            ctx.deleteDatabase("migration-23.db")
        }
    }
}
