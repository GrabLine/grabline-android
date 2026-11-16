package dev.grabline.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration34Test {
    /** Builds a real version-3 database from the exported schema, migrates it, and checks the new kind column. */
    @Test fun migration3To4KeepsJobsAsFilesAndAcceptsVideoJobs() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase("migration-34.db")
        val schema = JSONObject(File("schemas/dev.grabline.app.data.AppDatabase/3.json").readText()).getJSONObject("database")
        val raw = ctx.openOrCreateDatabase("migration-34.db", Context.MODE_PRIVATE, null)
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
            "INSERT INTO phone_jobs (url, name, status, downloaded, connections, acceptRanges, mobileOverride, createdAt) " +
                "VALUES ('http://x/y.bin', 'y.bin', 'QUEUED', 0, 0, 0, 0, 1)",
        )
        raw.version = 3
        raw.close()

        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, "migration-34.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).allowMainThreadQueries().build()
        try {
            val dao = db.phoneJobs()
            assertEquals("existing jobs stay file jobs", PhoneJobRow.KIND_FILE, dao.all().single().kind)
            val id = dao.insert(
                PhoneJobRow(url = "https://youtu.be/x", name = "x", status = "QUEUED", quality = "720p", kind = PhoneJobRow.KIND_VIDEO, createdAt = 2),
            )
            assertEquals(PhoneJobRow.KIND_VIDEO, dao.get(id)!!.kind)
            assertEquals("720p", dao.get(id)!!.quality)
        } finally {
            db.close()
            ctx.deleteDatabase("migration-34.db")
        }
    }
}
