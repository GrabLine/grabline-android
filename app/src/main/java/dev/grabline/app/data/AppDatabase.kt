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

/** v3 -> v4: phone jobs say whether the segment engine or the video engine runs them. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE phone_jobs ADD COLUMN kind TEXT NOT NULL DEFAULT 'file'")
    }
}

/** v4 -> v5: start-later time, pasted checksum and its result, and video options (format, subtitles, clip). */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE phone_jobs ADD COLUMN startAt INTEGER")
        db.execSQL("ALTER TABLE phone_jobs ADD COLUMN expectedSha256 TEXT")
        db.execSQL("ALTER TABLE phone_jobs ADD COLUMN checksumState TEXT")
        db.execSQL("ALTER TABLE phone_jobs ADD COLUMN formatSpec TEXT")
        db.execSQL("ALTER TABLE phone_jobs ADD COLUMN subLang TEXT")
        db.execSQL("ALTER TABLE phone_jobs ADD COLUMN clipStartSec INTEGER")
        db.execSQL("ALTER TABLE phone_jobs ADD COLUMN clipEndSec INTEGER")
    }
}

/** v5 -> v6: transfers and PC outbox links on phone jobs, the send-to-PC marker, and `fetchable` on cached PC jobs. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE phone_jobs ADD COLUMN pcId TEXT")
        db.execSQL("ALTER TABLE phone_jobs ADD COLUMN remoteJobId INTEGER")
        db.execSQL("ALTER TABLE phone_jobs ADD COLUMN outboxItemId INTEGER")
        db.execSQL("ALTER TABLE phone_jobs ADD COLUMN sendRequestId TEXT")
        db.execSQL("ALTER TABLE pc_jobs ADD COLUMN fetchable INTEGER NOT NULL DEFAULT 0")
    }
}

/** v6 -> v7: torrent jobs (peers, upload counter, sequential, file list and selection, info hash, seeding). */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE phone_jobs ADD COLUMN peers INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE phone_jobs ADD COLUMN uploaded INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE phone_jobs ADD COLUMN sequential INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE phone_jobs ADD COLUMN torrentFiles TEXT")
        db.execSQL("ALTER TABLE phone_jobs ADD COLUMN fileMask TEXT")
        db.execSQL("ALTER TABLE phone_jobs ADD COLUMN infoHash TEXT")
        db.execSQL("ALTER TABLE phone_jobs ADD COLUMN seeding INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE phone_jobs ADD COLUMN seedStartedAt INTEGER")
    }
}

@Database(
    entities = [PairedPc::class, PcJob::class, OutboxItem::class, PhoneJobRow::class, PhoneSegmentRow::class],
    version = 7,
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
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build().also { instance = it }
            }
    }
}
