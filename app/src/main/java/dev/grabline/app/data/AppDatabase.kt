package dev.grabline.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class Converters {
    @TypeConverter
    fun fromList(value: List<String>): String =
        Json.encodeToString(ListSerializer(String.serializer()), value)

    @TypeConverter
    fun toList(value: String): List<String> =
        Json.decodeFromString(ListSerializer(String.serializer()), value)
}

/** v1 -> v2: per-pairing key alias and the "PC removed this phone" marker. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE paired_pcs ADD COLUMN keyAlias TEXT")
        db.execSQL("ALTER TABLE paired_pcs ADD COLUMN removedAt INTEGER")
    }
}

/** v2 -> v3: phone download jobs and their segments (Phase 2 engine). */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `phone_jobs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `url` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, `folderUri` TEXT, `status` TEXT NOT NULL, `downloaded` INTEGER NOT NULL, `total` INTEGER, " +
                "`connections` INTEGER NOT NULL, `connectionCap` INTEGER, `notice` TEXT, `error` TEXT, `fileUri` TEXT, `etag` TEXT, " +
                "`lastModified` TEXT, `acceptRanges` INTEGER NOT NULL, `mobileOverride` INTEGER NOT NULL, `quality` TEXT, " +
                "`createdAt` INTEGER NOT NULL, `finishedAt` INTEGER)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `phone_segments` (`jobId` INTEGER NOT NULL, `idx` INTEGER NOT NULL, `start` INTEGER NOT NULL, " +
                "`end` INTEGER NOT NULL, `done` INTEGER NOT NULL, PRIMARY KEY(`jobId`, `idx`), " +
                "FOREIGN KEY(`jobId`) REFERENCES `phone_jobs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_phone_segments_jobId` ON `phone_segments` (`jobId`)")
    }
}

@Database(
    entities = [PairedPc::class, PcJob::class, OutboxItem::class, PhoneJobRow::class, PhoneSegmentRow::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pcs(): PairedPcDao
    abstract fun jobs(): PcJobDao
    abstract fun outbox(): OutboxDao
    abstract fun phoneJobs(): PhoneJobDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "grabline.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
    }
}
