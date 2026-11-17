package dev.grabline.app.engine

import dev.grabline.app.engine.video.VideoEngine
import kotlinx.coroutines.flow.Flow

/**
 * The phone's own download engine (Phase 2). Contract shared by the engine
 * (package engine), background work (package engine.work) and the UI.
 *
 * Rules it keeps (SPEC "Rules the engine must keep" 1-10): identity encoding,
 * Content-Range checks, If-Range with strong validators, 416 = file changed,
 * 429/503/403-after-data = halve connections, jittered retry budget, unique
 * names at add time, free-space check (no-space is permanent), temp name then
 * rename after the size check, cancel removes all scratch files.
 */
interface PhoneEngine {
    /** Every phone job, newest first. */
    val jobs: Flow<List<PhoneJob>>

    /** Add a link. Picks a unique name in the target folder at add time (rule 7). */
    suspend fun add(request: PhoneAddRequest): PhoneJob

    /** Copy a finished PC file to this phone as a background job of kind "transfer". */
    suspend fun addTransfer(request: TransferAddRequest): PhoneJob = throw UnsupportedOperationException("no transfers")

    suspend fun pause(jobId: Long)
    suspend fun resume(jobId: Long)
    /** Cancel removes partial and scratch files (rule 10); the row stays as cancelled. */
    suspend fun cancel(jobId: Long)
    suspend fun retry(jobId: Long)
    /** Remove from the list; a finished file stays on disk. */
    suspend fun remove(jobId: Long)
    suspend fun pauseAll()
    suspend fun resumeAll()
    /** "Download now anyway" for a job held by Wi-Fi only (overrides once). */
    suspend fun allowMobileData(jobId: Long)

    /** Torrent jobs: download pieces in order (for playing while it downloads). */
    suspend fun setTorrentSequential(jobId: Long, on: Boolean) {}
    /** Torrent jobs: include or skip one file of a multi-file torrent. */
    suspend fun setTorrentFileSelected(jobId: Long, index: Int, selected: Boolean) {}
    /** Torrent jobs: stop uploading a finished torrent now. */
    suspend fun stopSeeding(jobId: Long) {}

    /** The video engine (yt-dlp + FFmpeg), or null where the phone has none. */
    val video: VideoEngine? get() = null
}

/** A "Copy to this phone" request: PC job [remoteJobId] on [pcId]. */
data class TransferAddRequest(
    val pcId: String,
    val remoteJobId: Long,
    val name: String,
    /** The job's source link (kept for Details). */
    val url: String,
    val size: Long? = null,
    val folderUri: String? = null,
)

data class PhoneAddRequest(
    val url: String,
    val title: String? = null,
    /** SAF tree URI string, or null for Downloads/GrabLine through MediaStore. */
    val folderUri: String? = null,
    /** Video quality wire value (best, 1080p, mp3...); null for files. A video link without one downloads as Best. */
    val quality: String? = null,
    /** "Start later": epoch millis before which the job waits; null = start now. */
    val startAt: Long? = null,
    /** A pasted SHA-256 (already normalised) to check the finished file against; the result is advisory. */
    val sha256: String? = null,
    /** Video only: a yt-dlp format selector from "More formats"; overrides the quality preset's selector. */
    val formatSpec: String? = null,
    /** Video only: subtitle language to fetch and embed. */
    val subtitleLang: String? = null,
    /** Video only: keep just this part, in seconds from the start. */
    val clipStartSec: Long? = null,
    val clipEndSec: Long? = null,
    /** Set when the link came from a PC's outbox: the PC and item, so an item offered again is not added twice. */
    val pcId: String? = null,
    val outboxItemId: Long? = null,
    /** A .torrent file handed to the app (content:// URI) and its name; the engine reads and keeps its bytes. */
    val torrentFileUri: String? = null,
    val torrentFileName: String? = null,
)

enum class PhoneJobStatus { QUEUED, DOWNLOADING, PAUSED, WAITING_NETWORK, WAITING_WIFI, WAITING_CHARGING, COMPLETED, FAILED, CANCELLED }

data class PhoneJob(
    val id: Long,
    val url: String,
    val name: String,
    val status: PhoneJobStatus,
    val downloaded: Long,
    val total: Long?,
    val speed: Long,
    val etaSeconds: Long?,
    val connections: Int,
    /** Plain-words notice ("This server can't resume; it will restart if interrupted"). */
    val notice: String?,
    /** Plain-words error when FAILED (SPEC "Error states and edge cases"). */
    val error: String?,
    /** content:// URI of the finished file, for Open and Share. */
    val fileUri: String?,
    val createdAt: Long,
    val finishedAt: Long?,
    val isVideo: Boolean = false,
    /** A copy of a file from a PC (shown on the Transfers screen and its notification channel). */
    val isTransfer: Boolean = false,
    /** An ftp, sftp, s3 or webdav link (reported to a PC as kind "cloud"). */
    val isCloud: Boolean = false,
    /** Epoch millis the job is waiting for ("Start later"), or null. */
    val startAt: Long? = null,
    /** "verified", "mismatch" or null (no checksum asked for). Advisory: the file is kept either way. */
    val checksum: String? = null,
    /** Set for torrent jobs: peers, ratio, files, sequential and seeding state. */
    val torrent: TorrentDetails? = null,
) {
    /** Failed because the site changed: offer "Update the video engine?" (only when the setting asks). */
    val needsEngineUpdate: Boolean get() = status == PhoneJobStatus.FAILED && error == dev.grabline.app.engine.video.VideoText.SITE_CHANGED
}

/** What Details shows for a torrent job. */
data class TorrentDetails(
    val peers: Int,
    val uploaded: Long,
    val ratio: Float,
    val sequential: Boolean,
    val seeding: Boolean,
    val files: List<TorrentFileView>,
)

data class TorrentFileView(val index: Int, val name: String, val size: Long, val selected: Boolean)
