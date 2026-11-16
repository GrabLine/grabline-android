package dev.grabline.app.engine.torrent

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.LibTorrent
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.Vectors
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent

/**
 * libtorrent4j behind [TorrentBackend]: one [SessionManager] for the whole app (DHT on, no limit on active
 * torrents because the app's own queue decides what runs). The session starts with the first torrent and stops
 * when the last one is removed, so an idle app holds no sockets.
 */
class TorrentEngine : TorrentBackend {
    // Created on first use: the native library is only loaded when a torrent is really started (not in JVM tests).
    private val session by lazy { SessionManager(false) }
    private val lock = Any()
    private val handles = ConcurrentHashMap<String, TorrentHandle>()
    private val pendingAdd = ConcurrentHashMap<String, CompletableDeferred<TorrentHandle?>>()
    private val pendingResume = ConcurrentHashMap<String, CompletableDeferred<ByteArray?>>()

    private val listener = object : AlertListener {
        override fun types() = intArrayOf(
            AlertType.ADD_TORRENT.swig(), AlertType.SAVE_RESUME_DATA.swig(), AlertType.SAVE_RESUME_DATA_FAILED.swig(),
        )

        override fun alert(alert: Alert<*>) {
            when (alert) {
                is AddTorrentAlert -> {
                    val h = alert.handle()
                    val hash = runCatching { h.infoHash().toHex() }.getOrNull() ?: return
                    pendingAdd[hash]?.complete(if (alert.error().isError) null else h)
                }
                is SaveResumeDataAlert -> {
                    val hash = runCatching { alert.handle().infoHash().toHex() }.getOrNull() ?: return
                    val bytes = runCatching { AddTorrentParams.writeResumeDataBuf(alert.params()) }.getOrNull()
                    pendingResume[hash]?.complete(bytes)
                }
                is TorrentAlert<*> -> {
                    val hash = runCatching { alert.handle().infoHash().toHex() }.getOrNull() ?: return
                    pendingResume[hash]?.complete(null)
                }
                else -> Unit
            }
        }
    }

    override fun libraryVersion(): String = LibTorrent.version()

    /** Starts the session if it is not running (idempotent). */
    fun ensureStarted() = synchronized(lock) {
        if (session.isRunning) return@synchronized
        val pack = SettingsPack().apply {
            isEnableDht = true
            isEnableLsd = false
            activeDownloads(-1)
            activeSeeds(-1)
            activeLimit(-1)
        }
        session.addListener(listener)
        session.start(SessionParams(pack))
    }

    /** Whether the native session is running; for the instrumented test. */
    fun isRunning(): Boolean = session.isRunning

    fun stopSession() = synchronized(lock) {
        if (session.isRunning) {
            session.removeListener(listener)
            session.stop()
        }
        handles.clear()
    }

    override suspend fun add(
        source: TorrentSource, saveDir: File, resumeData: ByteArray?, sequential: Boolean, wanted: List<Boolean>?,
    ): String = withContext(Dispatchers.IO) {
        ensureStarted()
        saveDir.mkdirs()
        val params = buildParams(source, saveDir, resumeData, sequential, wanted)
        val best = params.infoHashes.best
        val hash = best.toHex()
        handles[hash]?.takeIf { it.isValid }?.let { return@withContext hash }
        session.find(best)?.takeIf { it.isValid }?.let { handles[hash] = it; return@withContext hash }
        val waiter = CompletableDeferred<TorrentHandle?>()
        pendingAdd[hash] = waiter
        try {
            session.swig().async_add_torrent(params.swig())
            val handle = withTimeoutOrNull(ADD_TIMEOUT_MS) { waiter.await() } ?: throw IOException("Could not add the torrent")
            handles[hash] = handle
        } finally {
            pendingAdd.remove(hash)
        }
        hash
    }

    private fun buildParams(
        source: TorrentSource, saveDir: File, resumeData: ByteArray?, sequential: Boolean, wanted: List<Boolean>?,
    ): AddTorrentParams {
        val params = resumeParams(resumeData) ?: when (source) {
            is TorrentSource.Magnet -> AddTorrentParams.parseMagnetUri(source.uri)
            is TorrentSource.Metainfo -> AddTorrentParams().also { it.torrentInfo = TorrentInfo(source.bytes) }
        }
        params.savePath = saveDir.absolutePath
        var flags = TorrentFlags.UPDATE_SUBSCRIBE.or_(TorrentFlags.APPLY_IP_FILTER)
        if (sequential) flags = flags.or_(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        params.flags = flags
        val info = params.torrentInfo
        if (wanted != null && info != null && wanted.size == info.numFiles()) {
            params.filePriorities(Array(wanted.size) { if (wanted[it]) Priority.DEFAULT else Priority.IGNORE })
        }
        return params
    }

    private fun resumeParams(data: ByteArray?): AddTorrentParams? {
        if (data == null || data.isEmpty()) return null
        val ec = error_code()
        val p = libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(data), ec)
        return if (ec.value() != 0) null else AddTorrentParams(p)
    }

    override fun snapshot(hash: String): TorrentSnapshot? {
        val h = handles[hash]?.takeIf { it.isValid } ?: return null
        val st = h.status()
        val info = h.torrentFile()
        val files = if (info != null) {
            val fs = info.files()
            List(info.numFiles()) { TorrentFileInfo(fs.filePath(it), fs.fileSize(it)) }
        } else emptyList()
        val err = st.errorCode()
        return TorrentSnapshot(
            infoHash = hash,
            hasMetadata = st.hasMetadata(),
            name = info?.name() ?: "",
            files = files,
            wantedBytes = st.totalWanted(),
            doneBytes = st.totalWantedDone(),
            downRate = st.downloadPayloadRate().toLong(),
            upRate = st.uploadPayloadRate().toLong(),
            peers = st.numPeers(),
            seeds = st.numSeeds(),
            uploaded = st.allTimeUpload(),
            finished = st.hasMetadata() && st.isFinished,
            error = if (err != null && err.isError) err.message else null,
        )
    }

    override fun setSequential(hash: String, on: Boolean) {
        val h = handles[hash]?.takeIf { it.isValid } ?: return
        if (on) h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) else h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
    }

    override fun setWanted(hash: String, wanted: List<Boolean>) {
        val h = handles[hash]?.takeIf { it.isValid } ?: return
        h.prioritizeFiles(Array(wanted.size) { if (wanted[it]) Priority.DEFAULT else Priority.IGNORE })
    }

    override suspend fun saveResume(hash: String): ByteArray? {
        val h = handles[hash]?.takeIf { it.isValid } ?: return null
        val waiter = CompletableDeferred<ByteArray?>()
        pendingResume[hash] = waiter
        try {
            h.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
            return withTimeoutOrNull(RESUME_TIMEOUT_MS) { waiter.await() }
        } finally {
            pendingResume.remove(hash)
        }
    }

    override fun remove(hash: String, deleteFiles: Boolean) {
        val h = handles.remove(hash)
        if (h != null && h.isValid) {
            if (deleteFiles) session.remove(h, org.libtorrent4j.SessionHandle.DELETE_FILES) else session.remove(h)
        }
        synchronized(lock) {
            if (handles.isEmpty() && session.isRunning) {
                session.removeListener(listener)
                session.stop()
            }
        }
    }

    private companion object {
        const val ADD_TIMEOUT_MS = 15_000L
        const val RESUME_TIMEOUT_MS = 5_000L
    }
}
