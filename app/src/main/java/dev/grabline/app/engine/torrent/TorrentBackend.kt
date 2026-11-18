package dev.grabline.app.engine.torrent

import java.io.File

data class TorrentFileInfo(
    /** Path inside the torrent, with its top folder for a multi-file torrent ("Show/ep1.mkv"). */
    val path: String,
    val size: Long,
) {
    /**
     * A padding file (BEP 47, `.pad/<size>`; BitComet's `_____padding_file_*`): it only keeps the next file aligned
     * to a piece. The library lists it but never writes it to disk, so it is not a file the user gets.
     */
    val isPad: Boolean get() = path.split('/').any { it == ".pad" } || path.substringAfterLast('/').startsWith("_____padding_file")
}

/** What the session knows about one torrent right now. */
data class TorrentSnapshot(
    val infoHash: String,
    val hasMetadata: Boolean,
    val name: String,
    val files: List<TorrentFileInfo>,
    /** Bytes of the files the user wants, and how many of them are on disk and verified. */
    val wantedBytes: Long,
    val doneBytes: Long,
    val downRate: Long,
    val upRate: Long,
    val peers: Int,
    val seeds: Int,
    /** All-time bytes uploaded for this torrent (kept across restarts by the resume data). */
    val uploaded: Long,
    /** Every wanted piece is on disk. */
    val finished: Boolean,
    val error: String?,
)

sealed interface TorrentSource {
    class Magnet(val uri: String) : TorrentSource
    class Metainfo(val bytes: ByteArray) : TorrentSource
}

/** The torrent library behind the runner; libtorrent4j in the app, a fake in unit tests. */
interface TorrentBackend {
    /** libtorrent's version string (no network needed). */
    fun libraryVersion(): String

    /** Adds the torrent to the session (or finds it again when it is already there) and returns its info hash. */
    suspend fun add(source: TorrentSource, saveDir: File, resumeData: ByteArray?, sequential: Boolean, wanted: List<Boolean>?): String

    fun snapshot(hash: String): TorrentSnapshot?
    fun setSequential(hash: String, on: Boolean)
    fun setWanted(hash: String, wanted: List<Boolean>)

    /** Resume data for [hash] (null when the library could not make it in time). */
    suspend fun saveResume(hash: String): ByteArray?

    fun remove(hash: String, deleteFiles: Boolean)
}
