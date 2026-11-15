package dev.grabline.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** One download made by the phone's own engine. [status] holds a PhoneJobStatus name. */
@Entity(tableName = "phone_jobs")
data class PhoneJobRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val name: String,
    /** SAF tree URI, or null for Downloads/GrabLine. */
    val folderUri: String? = null,
    val status: String,
    val downloaded: Long = 0,
    val total: Long? = null,
    /** Connections in use now (or last used). */
    val connections: Int = 0,
    /** Connection limit remembered after server pushback (rule 5); null = no pushback yet. */
    val connectionCap: Int? = null,
    val notice: String? = null,
    val error: String? = null,
    val fileUri: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val acceptRanges: Boolean = false,
    /** "Download now anyway": ignore Wi-Fi only for this job. */
    val mobileOverride: Boolean = false,
    val quality: String? = null,
    val createdAt: Long,
    val finishedAt: Long? = null,
)

/** One byte range of a phone job: [start, end) with [done] the next byte to write. */
@Entity(
    tableName = "phone_segments",
    primaryKeys = ["jobId", "idx"],
    foreignKeys = [
        ForeignKey(
            entity = PhoneJobRow::class,
            parentColumns = ["id"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("jobId")],
)
data class PhoneSegmentRow(
    val jobId: Long,
    val idx: Int,
    val start: Long,
    val end: Long,
    val done: Long,
)

@Dao
interface PhoneJobDao {
    @Query("SELECT * FROM phone_jobs ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<PhoneJobRow>>

    @Query("SELECT * FROM phone_jobs ORDER BY createdAt, id")
    suspend fun all(): List<PhoneJobRow>

    @Query("SELECT * FROM phone_jobs WHERE id = :id")
    suspend fun get(id: Long): PhoneJobRow?

    @Query("SELECT * FROM phone_jobs WHERE status IN (:statuses) ORDER BY createdAt, id")
    suspend fun withStatus(statuses: List<String>): List<PhoneJobRow>

    @Query("SELECT COUNT(*) FROM phone_jobs WHERE status IN (:statuses)")
    fun observeCount(statuses: List<String>): Flow<Int>

    @Insert
    suspend fun insert(row: PhoneJobRow): Long

    @Update
    suspend fun update(row: PhoneJobRow)

    @Query("DELETE FROM phone_jobs WHERE id = :id")
    suspend fun delete(id: Long)

    /** Moves a job to [to] only if it is currently one of [from]; returns rows changed. */
    @Query("UPDATE phone_jobs SET status = :to WHERE id = :id AND status IN (:from)")
    suspend fun setStatusIf(id: Long, from: List<String>, to: String): Int

    @Query("UPDATE phone_jobs SET status = :to, notice = COALESCE(:notice, notice), error = :error WHERE id = :id AND status IN (:from)")
    suspend fun moveIf(id: Long, from: List<String>, to: String, notice: String?, error: String?): Int

    @Query("UPDATE phone_jobs SET total = :total, etag = :etag, lastModified = :lastModified, acceptRanges = :ranges, notice = COALESCE(:notice, notice) WHERE id = :id")
    suspend fun saveProbe(id: Long, total: Long?, etag: String?, lastModified: String?, ranges: Boolean, notice: String?)

    @Query("UPDATE phone_jobs SET status = 'COMPLETED', fileUri = :fileUri, total = :total, downloaded = :total, connections = 0, error = NULL, finishedAt = :now WHERE id = :id AND status = 'DOWNLOADING'")
    suspend fun complete(id: Long, fileUri: String, total: Long, now: Long): Int

    @Query("UPDATE phone_jobs SET downloaded = 0, connections = 0 WHERE id = :id")
    suspend fun clearProgress(id: Long)

    @Query("UPDATE phone_jobs SET mobileOverride = 1 WHERE id = :id")
    suspend fun allowMobile(id: Long)

    @Query("UPDATE phone_jobs SET downloaded = :downloaded, connections = :connections, connectionCap = :cap WHERE id = :id")
    suspend fun saveProgress(id: Long, downloaded: Long, connections: Int, cap: Int?)

    @Query("SELECT * FROM phone_segments WHERE jobId = :jobId ORDER BY start")
    suspend fun segments(jobId: Long): List<PhoneSegmentRow>

    @Query("DELETE FROM phone_segments WHERE jobId = :jobId")
    suspend fun deleteSegments(jobId: Long)

    @Insert
    suspend fun insertSegments(rows: List<PhoneSegmentRow>)

    @Transaction
    suspend fun replaceSegments(jobId: Long, rows: List<PhoneSegmentRow>) {
        deleteSegments(jobId)
        insertSegments(rows)
    }

    /** Progress and segment snapshot together, so a restart never sees one without the other. */
    @Transaction
    suspend fun checkpoint(id: Long, downloaded: Long, connections: Int, cap: Int?, rows: List<PhoneSegmentRow>) {
        saveProgress(id, downloaded, connections, cap)
        replaceSegments(id, rows)
    }
}
