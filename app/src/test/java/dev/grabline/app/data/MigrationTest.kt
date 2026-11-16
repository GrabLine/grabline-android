package dev.grabline.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
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
class MigrationTest {
    /** Builds a real version-1 database from the exported schema, then lets Room migrate and validate it. */
    @Test fun migration1To2KeepsRowsAndAddsAliasAndRemovedColumns() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.deleteDatabase("migration-test.db")
        val schema = JSONObject(File("schemas/dev.grabline.app.data.AppDatabase/1.json").readText()).getJSONObject("database")
        val raw = ctx.openOrCreateDatabase("migration-test.db", Context.MODE_PRIVATE, null)
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
        raw.version = 1
        raw.close()

        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, "migration-test.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).allowMainThreadQueries().build()
        try {
            val pc = db.pcs().get("pc1")!!
            assertEquals("Desk", pc.name)
            assertEquals(listOf("10.0.0.2"), pc.addresses)
            assertNull("old rows use the legacy alias", pc.keyAlias)
            assertNull(pc.removedAt)
            db.pcs().markRemoved("pc1", 42L)
            assertEquals(42L, db.pcs().get("pc1")!!.removedAt)
        } finally {
            db.close()
            ctx.deleteDatabase("migration-test.db")
        }
    }
}
