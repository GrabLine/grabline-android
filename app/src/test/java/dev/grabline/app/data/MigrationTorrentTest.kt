package dev.grabline.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Schema 6 -> 7 (torrent columns): builds a real version 6 database from the exported schema, then lets Room open it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTorrentTest {
    private val schemaDir = File("schemas/dev.grabline.app.data.AppDatabase")

    private fun createVersion6(file: File) {
        val db = json(6)["database"]!!.jsonObject
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        for (entity in db["entities"]!!.jsonArray) {
            val e = entity.jsonObject
            raw.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", e["tableName"]!!.jsonPrimitive.content))
            for (index in e["indices"]?.jsonArray.orEmpty()) {
                raw.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", e["tableName"]!!.jsonPrimitive.content))
            }
        }
        for (q in db["setupQueries"]!!.jsonArray) raw.execSQL(q.jsonPrimitive.content)
        raw.execSQL(
            "INSERT INTO phone_jobs (url, name, status, downloaded, connections, acceptRanges, mobileOverride, createdAt, kind) " +
                "VALUES ('https://example.com/a.zip', 'a.zip', 'COMPLETED', 10, 0, 1, 0, 5, 'file')",
        )
        raw.version = 6
        raw.close()
    }

    private fun json(version: Int) = Json.parseToJsonElement(File(schemaDir, "$version.json").readText()).jsonObject

    @Test fun version6OpensAsVersion7WithTorrentColumnsAndKeepsItsRows() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = "migrate-6-7-${System.nanoTime()}.db"
        val path = ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }
        createVersion6(path)

        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, name).addMigrations(MIGRATION_6_7, MIGRATION_7_8).allowMainThreadQueries().build()
        try {
            val old = db.phoneJobs().all().single()
            assertEquals("a.zip", old.name)
            assertEquals(0, old.peers)
            assertEquals(0L, old.uploaded)
            assertFalse(old.sequential)
            assertFalse(old.seeding)
            assertNull(old.infoHash)
            assertNull(old.fileMask)
            val id = db.phoneJobs().insert(
                PhoneJobRow(
                    url = "magnet:?xt=urn:btih:abc", name = "Movie", status = "QUEUED", createdAt = 6, kind = PhoneJobRow.KIND_TORRENT,
                    sequential = true, fileMask = "101", infoHash = "abc", torrentFiles = "[]",
                ),
            )
            db.phoneJobs().saveTorrentProgress(id, 5, 10, 7, 99)
            val row = db.phoneJobs().get(id)!!
            assertEquals(PhoneJobRow.KIND_TORRENT, row.kind)
            assertTrue(row.sequential)
            assertEquals("101", row.fileMask)
            // Room validated the migrated table against the entity: opening it above would have thrown otherwise.
            assertEquals(2, db.phoneJobs().all().size)
        } finally {
            db.close()
            path.delete()
        }
    }

    @Test fun schema7IsExportedWithTheNewColumns() {
        val v7 = json(7)["database"]!!.jsonObject
        assertEquals(7, v7["version"]!!.jsonPrimitive.content.toInt())
        val phoneJobs = v7["entities"]!!.jsonArray.map { it.jsonObject }.first { it["tableName"]!!.jsonPrimitive.content == "phone_jobs" }
        val columns = phoneJobs["fields"]!!.jsonArray.map { it.jsonObject["columnName"]!!.jsonPrimitive.content }
        assertTrue(columns.containsAll(listOf("peers", "uploaded", "sequential", "torrentFiles", "fileMask", "infoHash", "seeding", "seedStartedAt")))
    }
}
