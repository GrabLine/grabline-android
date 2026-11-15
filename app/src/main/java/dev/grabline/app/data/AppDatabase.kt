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

@Database(
    entities = [PairedPc::class, PcJob::class, OutboxItem::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pcs(): PairedPcDao
    abstract fun jobs(): PcJobDao
    abstract fun outbox(): OutboxDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "grabline.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
