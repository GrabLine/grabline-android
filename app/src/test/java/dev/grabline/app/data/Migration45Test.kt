package dev.grabline.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration45Test {
    /** A real version-4 database, migrated forward: old jobs keep working and the new columns exist. */
    @Test fun migration4To5AddsStartLaterChecksumAndVideoOptions() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase("migration-45.db")
        val schema = JSONObject(File("schemas/dev.grabline.app.data.AppDatabase/4.json").readText()).getJSONObject("database")
        val raw = ctx.openOrCreateDatabase("migration-45.db", Context.MODE_PRIVATE, null)
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
            "INSERT INTO phone_jobs (url, name, status, downloaded, connections, acceptRanges, mobileOverride, kind, createdAt) " +
                "VALUES ('http://x/y.bin', 'y.bin', 'QUEUED', 0, 0, 0, 0, 'file', 1)",
        )
        raw.version = 4
        raw.close()

        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, "migration-45.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .allowMainThreadQueries().build()
        try {
            val dao = db.phoneJobs()
            val old = dao.all().single()
            assertNull("an old job has no wait", old.startAt)
            assertNull(old.expectedSha256)
            val id = dao.insert(
                PhoneJobRow(
                    url = "https://youtu.be/x", name = "x", status = "QUEUED", kind = PhoneJobRow.KIND_VIDEO, createdAt = 2,
                    startAt = 5_000, expectedSha256 = "ab", formatSpec = "137+ba/137", subLang = "en", clipStartSec = 10, clipEndSec = 20,
                ),
            )
            val row = dao.get(id)!!
            assertEquals(5_000L, row.startAt)
            assertEquals("137+ba/137", row.formatSpec)
            assertEquals(10L, row.clipStartSec)
            dao.setChecksum(id, "mismatch", "Checksum didn't match")
            assertEquals("mismatch", dao.get(id)!!.checksumState)
            assertEquals(5_000L, dao.observeEarliestStart().first())
        } finally {
            db.close()
            ctx.deleteDatabase("migration-45.db")
        }
    }
}
