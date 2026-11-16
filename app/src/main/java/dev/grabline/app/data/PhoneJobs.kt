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
    /** "file" (the segment engine) or "video" (yt-dlp + FFmpeg). Added in schema 4. */
    val kind: String = KIND_FILE,
    /** The PC this job came from (a transfer, or a link from the PC's outbox). Added in schema 6. */
    val pcId: String? = null,
    /** For a transfer: the PC job being copied. */
    val remoteJobId: Long? = null,
    /** The PC outbox item this job was made from, so a re-offered item is not added twice. */
    val outboxItemId: Long? = null,
    /** Set while "Send to PC" waits for the PC to confirm; the job is cancelled once it has. */
    val sendRequestId: String? = null,
    /** Epoch millis before which the job must not start ("Start later"); null = no wait. Added in schema 5. */
    val startAt: Long? = null,
    /** SHA-256 the user pasted (lower-case hex), checked when the job completes. */
    val expectedSha256: String? = null,
    /** Result of that check: "verified", "mismatch", or null when none was asked for. */
    val checksumState: String? = null,
    /** yt-dlp format selector picked under "More formats"; null = the quality preset decides. */
    val formatSpec: String? = null,
    /** Subtitle language to fetch and embed (video jobs). */
    val subLang: String? = null,
    /** Clip to keep, in seconds from the start of the video (video jobs). */
    val clipStartSec: Long? = null,
    val clipEndSec: Long? = null,
    /** Torrent jobs (added in schema 7): peers connected now (or last seen). */
    val peers: Int = 0,
    /** Torrent jobs: bytes uploaded so far, for the seeding ratio. */
    val uploaded: Long = 0,
    /** Torrent jobs: download pieces in order (for playing while it downloads). */
    val sequential: Boolean = false,
    /** Torrent jobs: JSON list of the files (`[{"n":"a/b.mkv","s":123}]`), known once the metadata arrived. */
    val torrentFiles: String? = null,
    /** Torrent jobs: one character per file, 1 = download it, 0 = skip it; null = all. */
    val fileMask: String? = null,
    /** Torrent jobs: the info hash (lower-case hex), once known. */
    val infoHash: String? = null,
    /** Torrent jobs: true while the finished torrent is seeding. */
    val seeding: Boolean = false,
    /** Torrent jobs: epoch millis seeding began; the "for 1 hour" rule counts from here. */
    val seedStartedAt: Long? = null,
) {
    companion object {
        const val KIND_FILE = "file"
        const val KIND_VIDEO = "video"
        /** A copy of a finished PC file (PROTOCOL.md 13). */
        const val KIND_TRANSFER = "transfer"
        /** A magnet link or .torrent through libtorrent4j (schema 7). */
        const val KIND_TORRENT = "torrent"
    }
}

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

    /** Jobs that can run now: not waiting for a "Start later" time that has not come. */
    @Query("SELECT COUNT(*) FROM phone_jobs WHERE status IN (:statuses) AND (startAt IS NULL OR startAt <= CAST(strftime('%s','now') AS INTEGER) * 1000)")
    fun observeDueCount(statuses: List<String>): Flow<Int>

    /** The earliest "Start later" time among queued jobs, for the scheduler's wake-up. */
    @Query("SELECT MIN(startAt) FROM phone_jobs WHERE status IN ('QUEUED','WAITING_NETWORK','WAITING_WIFI','WAITING_CHARGING') AND startAt IS NOT NULL")
    fun observeEarliestStart(): Flow<Long?>

    /** The job started: forget its wait and the "Starts at" notice. */
    @Query("UPDATE phone_jobs SET startAt = NULL, notice = CASE WHEN notice LIKE 'Starts %' THEN NULL ELSE notice END WHERE id = :id AND startAt IS NOT NULL")
    suspend fun clearSchedule(id: Long)

    @Query("UPDATE phone_jobs SET checksumState = :state, notice = :notice WHERE id = :id")
    suspend fun setChecksum(id: Long, state: String, notice: String)

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

    /** Video jobs report whole-job progress, not segments. */
    @Query("UPDATE phone_jobs SET downloaded = :downloaded, total = :total, connections = 1 WHERE id = :id AND status = 'DOWNLOADING'")
    suspend fun saveVideoProgress(id: Long, downloaded: Long, total: Long?)

    /** Torrent progress: wanted bytes done of wanted bytes (null until the metadata arrived). */
    @Query("UPDATE phone_jobs SET downloaded = :downloaded, total = :total, peers = :peers, uploaded = :uploaded, connections = :peers WHERE id = :id AND status = 'DOWNLOADING'")
    suspend fun saveTorrentProgress(id: Long, downloaded: Long, total: Long?, peers: Int, uploaded: Long)

    @Query("UPDATE phone_jobs SET infoHash = :hash, torrentFiles = :files, name = :name WHERE id = :id")
    suspend fun saveTorrentMeta(id: Long, hash: String, files: String, name: String)

    @Query("UPDATE phone_jobs SET sequential = :on WHERE id = :id")
    suspend fun setSequential(id: Long, on: Boolean)

    @Query("UPDATE phone_jobs SET fileMask = :mask WHERE id = :id")
    suspend fun setFileMask(id: Long, mask: String?)

    @Query("UPDATE phone_jobs SET seeding = 1, seedStartedAt = :now, peers = 0 WHERE id = :id AND status = 'COMPLETED'")
    suspend fun startSeeding(id: Long, now: Long): Int

    @Query("UPDATE phone_jobs SET seeding = 0, peers = 0, connections = 0 WHERE id = :id")
    suspend fun stopSeeding(id: Long)

    @Query("UPDATE phone_jobs SET peers = :peers, uploaded = :uploaded, connections = :peers WHERE id = :id AND seeding = 1")
    suspend fun saveSeedProgress(id: Long, peers: Int, uploaded: Long)

    @Query("SELECT * FROM phone_jobs WHERE seeding = 1 ORDER BY id")
    suspend fun seedingJobs(): List<PhoneJobRow>

    @Query("SELECT COUNT(*) FROM phone_jobs WHERE seeding = 1")
    fun observeSeedingCount(): Flow<Int>

    @Query("UPDATE phone_jobs SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE phone_jobs SET mobileOverride = 1 WHERE id = :id")
    suspend fun allowMobile(id: Long)

    @Query("UPDATE phone_jobs SET downloaded = :downloaded, connections = :connections, connectionCap = :cap WHERE id = :id")
    suspend fun saveProgress(id: Long, downloaded: Long, connections: Int, cap: Int?)

    @Query("UPDATE phone_jobs SET pcId = :pcId, outboxItemId = :itemId WHERE id = :id")
    suspend fun setSource(id: Long, pcId: String, itemId: Long?)

    @Query("SELECT * FROM phone_jobs WHERE pcId = :pcId AND outboxItemId = :itemId LIMIT 1")
    suspend fun byOutboxItem(pcId: String, itemId: Long): PhoneJobRow?

    @Query("SELECT * FROM phone_jobs WHERE sendRequestId = :requestId")
    suspend fun bySendRequest(requestId: String): List<PhoneJobRow>

    /** Phone jobs waiting for a PC to confirm "Send to PC". */
    @Query("SELECT * FROM phone_jobs WHERE sendRequestId IS NOT NULL")
    suspend fun withSendRequest(): List<PhoneJobRow>

    @Query("UPDATE phone_jobs SET sendRequestId = :requestId WHERE id = :id")
    suspend fun setSendRequest(id: Long, requestId: String?)

    /** A copy of this PC file that is still running or waiting (a second tap on "Copy" does not start another). */
    @Query("SELECT * FROM phone_jobs WHERE kind = 'transfer' AND pcId = :pcId AND remoteJobId = :remoteJobId AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED') LIMIT 1")
    suspend fun activeTransfer(pcId: String, remoteJobId: Long): PhoneJobRow?

    @Query("UPDATE phone_jobs SET downloaded = :downloaded, total = :total, etag = :etag WHERE id = :id")
    suspend fun saveTransfer(id: Long, downloaded: Long, total: Long?, etag: String?)

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
