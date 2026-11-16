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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Schema 7 -> 8 (server accounts, host keys, sealed job secret): builds a real version 7 database from the exported schema, then lets Room open it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration78Test {
    private val schemaDir = File("schemas/dev.grabline.app.data.AppDatabase")

    private fun createVersion7(file: File) {
        val db = json(7)["database"]!!.jsonObject
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
            "INSERT INTO phone_jobs (url, name, status, downloaded, connections, acceptRanges, mobileOverride, createdAt, kind, peers, uploaded, sequential, seeding) " +
                "VALUES ('https://example.com/a.zip', 'a.zip', 'COMPLETED', 10, 0, 1, 0, 5, 'file', 0, 0, 0, 0)",
        )
        raw.version = 7
        raw.close()
    }

    private fun json(version: Int) = Json.parseToJsonElement(File(schemaDir, "$version.json").readText()).jsonObject

    @Test fun version7OpensAsVersion8WithServerTablesAndKeepsItsRows() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = "migrate-7-8-${System.nanoTime()}.db"
        val path = ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }
        createVersion7(path)

        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, name).addMigrations(MIGRATION_7_8).allowMainThreadQueries().build()
        try {
            val old = db.phoneJobs().all().single()
            assertEquals("a.zip", old.name)
            assertNull(old.cloudSecret)
            val id = db.cloudAccounts().insert(
                CloudAccountRow(service = "sftp", host = "h", port = 0, username = "u", label = "", endpoint = "", region = "", secrets = "sealed"),
            )
            assertEquals("sealed", db.cloudAccounts().get(id)!!.secrets)
            db.sshHostKeys().put(SshHostKeyRow("h", 22, "ssh-ed25519", "SHA256:x", 1))
            assertEquals("SHA256:x", db.sshHostKeys().find("h", 22)!!.sha256)
            val job = db.phoneJobs().insert(
                PhoneJobRow(url = "ftp://u@h/f", name = "f", status = "QUEUED", createdAt = 7, kind = PhoneJobRow.KIND_CLOUD, cloudSecret = "sealed"),
            )
            assertEquals("sealed", db.phoneJobs().get(job)!!.cloudSecret)
            // Room validated the migrated tables against the entities: opening it above would have thrown otherwise.
        } finally {
            db.close()
            path.delete()
        }
    }

    @Test fun schema8IsExportedWithTheNewTables() {
        val v8 = json(8)["database"]!!.jsonObject
        assertEquals(8, v8["version"]!!.jsonPrimitive.content.toInt())
        val tables = v8["entities"]!!.jsonArray.map { it.jsonObject["tableName"]!!.jsonPrimitive.content }
        assertTrue(tables.containsAll(listOf("cloud_accounts", "ssh_host_keys")))
    }
}
