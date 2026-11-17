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
import org.libtorrent4j.Vectors
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.swig.bdecode_node
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent

/**
 * libtorrent4j behind [TorrentBackend]: one [SessionManager] for the whole app (DHT on, no limit on active
 * torrents because the app's own queue decides what runs). The session starts with the first torrent and stops
 * when the last one is removed, so an idle app holds no sockets.
 */
class TorrentEngine(
    /** The phone's addresses on the network Android uses now (not every interface: see [localAddresses]). */
    private val addresses: () -> List<java.net.InetAddress> = ::allInterfaceAddresses,
) : TorrentBackend {
    // Created on first use: the native library is only loaded when a torrent is really started (not in JVM tests).
    private val session by lazy { SessionManager(false) }
    private val lock = Any()
    private val handles = ConcurrentHashMap<String, TorrentHandle>()
    /** Completed with whether the add worked. Never keep an alert's handle: it points into the alert,
     * which libtorrent frees after the callback (using it later crashed the app with SIGSEGV). */
    private val pendingAdd = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val pendingResume = ConcurrentHashMap<String, CompletableDeferred<ByteArray?>>()

    private val listener = object : AlertListener {
        override fun types() = intArrayOf(
            AlertType.ADD_TORRENT.swig(), AlertType.SAVE_RESUME_DATA.swig(), AlertType.SAVE_RESUME_DATA_FAILED.swig(),
        )

        override fun alert(alert: Alert<*>) {
            when (alert) {
                is AddTorrentAlert -> {
                    val hash = runCatching { alert.handle().infoHash().toHex() }.getOrNull() ?: return
                    pendingAdd[hash]?.complete(!alert.error().isError)
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

    // Debug builds only: what libtorrent is doing, for diagnosing a torrent that does not move.
    private val debugListener = object : AlertListener {
        override fun types() = intArrayOf(
            AlertType.TRACKER_ERROR.swig(), AlertType.TRACKER_WARNING.swig(), AlertType.TRACKER_REPLY.swig(),
            AlertType.URL_SEED.swig(), AlertType.STATE_CHANGED.swig(), AlertType.TORRENT_ERROR.swig(),
            AlertType.FILE_ERROR.swig(), AlertType.LISTEN_FAILED.swig(), AlertType.DHT_BOOTSTRAP.swig(),
            AlertType.LISTEN_SUCCEEDED.swig(), AlertType.PEER_ERROR.swig(), AlertType.PEER_CONNECT.swig(),
            AlertType.PEER_DISCONNECTED.swig(),
        )

        override fun alert(alert: Alert<*>) {
            android.util.Log.d("GrabLine", "libtorrent ${alert.type()}: ${alert.message()}")
        }
    }
    private val lastDebug = ConcurrentHashMap<String, Long>()

    private fun debugStatus(hash: String, h: TorrentHandle, st: org.libtorrent4j.TorrentStatus) {
        val now = System.currentTimeMillis()
        if (now - (lastDebug[hash] ?: 0L) < 20_000) return
        lastDebug[hash] = now
        android.util.Log.d(
            "GrabLine",
            "torrent ${hash.take(8)} state=${st.state()} peers=${st.numPeers()} conns=${st.numConnections()} " +
                "candidates=${st.connectCandidates()} listed=${st.listPeers()} done=${st.totalWantedDone()} " +
                "paused=${st.flags().and_(TorrentFlags.PAUSED).non_zero()} automanaged=${st.flags().and_(TorrentFlags.AUTO_MANAGED).non_zero()} " +
                "trackers=${runCatching { h.trackers().size }.getOrNull()} seeds=${runCatching { h.urlSeeds() }.getOrNull()} " +
                "toTrackers=${st.announcingToTrackers()} toDht=${st.announcingToDht()} next=${st.nextAnnounce()} " +
                "listen=$appliedListen",
        )
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
        listenOn(pack)
        session.addListener(listener)
        if (dev.grabline.app.BuildConfig.DEBUG) session.addListener(debugListener)
        session.start(SessionParams(pack))
    }

    /**
     * libtorrent expands "0.0.0.0" by reading the routing table over netlink, which Android 11+ refuses
     * ("enum_route: Operation not supported"). With no listen socket it makes no connection at all: no
     * tracker, no web seed, no DHT. So name the phone's own addresses, read from Java.
     */
    private fun listenOn(pack: SettingsPack) {
        localAddresses().takeIf { it.isNotEmpty() }?.let { pack.listenInterfaces(it.joinToString(",")) }
        appliedListen = pack.listenInterfaces()
    }

    @Volatile private var appliedListen: String? = null
    @Volatile private var listenCheckedAt = 0L

    /** The phone's address changes with the network (Wi-Fi to mobile data): listen on the new one. */
    private fun refreshListen() {
        val now = System.currentTimeMillis()
        if (now - listenCheckedAt < LISTEN_CHECK_MS) return
        listenCheckedAt = now
        val wanted = localAddresses().joinToString(",").takeIf { it.isNotEmpty() } ?: return
        if (wanted == appliedListen || !session.isRunning) return
        appliedListen = wanted
        runCatching { session.applySettings(SettingsPack().listenInterfaces(wanted)) }
    }

    /**
     * Only the default network's addresses: listening on every interface let torrent traffic go out
     * over mobile data while the phone was on Wi-Fi. Private IPv6 (fc00::/7) is skipped: on a hotspot
     * it has no route out, and connections over it hang.
     */
    private fun localAddresses(): List<String> = runCatching {
        addresses()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress && !isPrivateV6(it) }
            .map { a ->
                val ip = a.hostAddress.orEmpty().substringBefore('%')
                if (a is java.net.Inet6Address) "[$ip]:0" else "$ip:0"
            }
            .distinct()
            .sorted()
    }.getOrDefault(emptyList())

    private fun isPrivateV6(a: java.net.InetAddress) =
        a is java.net.Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc

    /** Whether the native session is running; for the instrumented test. */
    fun isRunning(): Boolean = session.isRunning

    fun stopSession() = synchronized(lock) {
        if (session.isRunning) {
            session.removeListener(listener)
            session.removeListener(debugListener)
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
        // A .torrent file's hash lives in its torrent info; params.infoHashes is only filled for
        // magnets and resume data (left at zero here, the add alert never matched and timed out).
        // The v1 hash, as TorrentHandle.infoHash() reports it in the add alert.
        val best = params.torrentInfo?.infoHash() ?: params.infoHashes.best
        val hash = best.toHex()
        handles[hash]?.takeIf { it.isValid }?.let { return@withContext hash }
        session.find(best)?.takeIf { it.isValid }?.let { handles[hash] = it; return@withContext hash }
        val waiter = CompletableDeferred<Boolean>()
        pendingAdd[hash] = waiter
        try {
            session.swig().async_add_torrent(params.swig())
            val added = withTimeoutOrNull(ADD_TIMEOUT_MS) { waiter.await() } ?: false
            // An owned handle from the session, not the one inside the alert.
            val handle = session.find(best)?.takeIf { added && it.isValid } ?: throw IOException("Could not add the torrent")
            handles[hash] = handle
        } finally {
            pendingAdd.remove(hash)
        }
        hash
    }

    private fun buildParams(
        source: TorrentSource, saveDir: File, resumeData: ByteArray?, sequential: Boolean, wanted: List<Boolean>?,
    ): AddTorrentParams {
        val fresh = when (source) {
            is TorrentSource.Magnet -> AddTorrentParams.parseMagnetUri(source.uri)
            is TorrentSource.Metainfo -> loadTorrent(source.bytes)
        }
        val params = resumeParams(resumeData)?.also { r ->
            // Resume data saved by an older build may lack them; without trackers or web seeds nothing downloads.
            if (r.trackers.isNullOrEmpty() && !fresh.trackers.isNullOrEmpty()) {
                r.trackers = fresh.trackers
                r.trackerTiers = fresh.trackerTiers
            }
            if (r.urlSeeds.isNullOrEmpty() && !fresh.urlSeeds.isNullOrEmpty()) r.urlSeeds = fresh.urlSeeds
        } ?: fresh
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

    /**
     * A .torrent file as libtorrent itself loads one: its trackers and web seeds come with it.
     * (Setting only torrentInfo, as before, left the torrent with no tracker and no web seed.)
     */
    private fun loadTorrent(bytes: ByteArray): AddTorrentParams {
        val buffer = Vectors.bytes2byte_vector(bytes)
        val node = bdecode_node()
        val ec = error_code()
        if (bdecode_node.bdecode(buffer, node, ec) != 0 || ec.value() != 0) throw IOException("Not a torrent file")
        val p = libtorrent.load_torrent_parsed(node, ec, org.libtorrent4j.swig.load_torrent_limits())
        if (ec.value() != 0) throw IOException("Not a torrent file")
        buffer.size // the parsed node points into the buffer: keep it alive until the load is done
        return AddTorrentParams(p)
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
        refreshListen()
        if (dev.grabline.app.BuildConfig.DEBUG) debugStatus(hash, h, st)
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
                session.removeListener(debugListener)
                session.stop()
            }
        }
    }

    private companion object {
        const val ADD_TIMEOUT_MS = 15_000L
        const val LISTEN_CHECK_MS = 10_000L
        const val RESUME_TIMEOUT_MS = 5_000L
    }
}

/** Every address of every interface that is up; used where no Android context is at hand (tests). */
fun allInterfaceAddresses(): List<java.net.InetAddress> = runCatching {
    java.net.NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
}.getOrDefault(emptyList())
