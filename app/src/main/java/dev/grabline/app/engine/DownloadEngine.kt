package dev.grabline.app.engine

import dev.grabline.app.data.PhoneJobDao
import dev.grabline.app.data.PhoneJobRow
import dev.grabline.app.engine.cloud.CloudBackend
import dev.grabline.app.engine.cloud.CloudErrors
import dev.grabline.app.engine.cloud.CloudRunner
import dev.grabline.app.engine.cloud.CloudSchemes
import dev.grabline.app.engine.cloud.CloudText
import dev.grabline.app.engine.cloud.CloudUrl
import dev.grabline.app.engine.torrent.LinkRoute
import dev.grabline.app.engine.torrent.NetworkHold
import dev.grabline.app.engine.torrent.SeedRules
import dev.grabline.app.engine.torrent.StoredFiles
import dev.grabline.app.engine.torrent.TorrentBackend
import dev.grabline.app.engine.torrent.TorrentRouting
import dev.grabline.app.engine.torrent.TorrentRunner
import dev.grabline.app.engine.torrent.TorrentText
import dev.grabline.app.engine.video.DrmGuard
import dev.grabline.app.engine.video.VideoEngine
import dev.grabline.app.ui.entry.LinkKind
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/** What the phone knows about its connection and power, set by background work (P2-02). */
data class EngineConstraints(
    val connected: Boolean = true,
    val wifi: Boolean = true,
    val metered: Boolean = false,
    val charging: Boolean = false,
)

/**
 * The phone's download engine: probes, segments, work-steals and keeps SPEC rules 1-10.
 * It runs only inside [runUntilIdle], which background work calls; the UI talks to [PhoneEngine].
 */
class DownloadEngine(
    private val dao: PhoneJobDao,
    client: OkHttpClient,
    private val storage: PhoneStorage,
    private val settings: EngineSettings,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    minSegmentBytes: Long = 1L shl 20,
    backoffBaseMs: Long = 1000,
    networkGraceMs: Long = 3_000,
    random: Random = Random.Default,
    override val video: VideoEngine? = null,
    /** Copies files from a PC for jobs of kind "transfer"; null where transfers are not wired. */
    private val transfers: dev.grabline.app.remote.transfer.FetchClient? = null,
    /** Runs jobs of kind "torrent" (libtorrent4j); null where there is none. */
    private val torrents: TorrentBackend? = null,
    /** Reads the bytes of a .torrent file handed to the app (a content:// URI); null where files cannot be read. */
    private val torrentFiles: (suspend (uri: String) -> ByteArray?)? = null,
    /** Runs jobs of kind "cloud" (ftp, ftps, sftp, scp, s3, webdav); null where there is none. */
    private val cloud: CloudBackend? = null,
) : PhoneEngine {
    private val constraints = MutableStateFlow(EngineConstraints())
    private val speeds = MutableStateFlow<Map<Long, Long>>(emptyMap())
    private val bucket = TokenBucket(clock)
    private val runners = ConcurrentHashMap<Long, Job>()
    /** Finished torrents that are uploading ("Seed after download"); they do not use a download slot. */
    private val seeders = ConcurrentHashMap<Long, Job>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val addMutex = Mutex()
    private val runMutex = Mutex()
    private val evalMutex = Mutex()
    private val env = Env(dao, client, storage, bucket, clock, ioDispatcher, minSegmentBytes, backoffBaseMs, random, online = { constraints.value.connected }, networkGraceMs = networkGraceMs) { id, bps ->
        speeds.update { if (bps <= 0) it - id else it + (id to bps) }
    }

    override val jobs: Flow<List<PhoneJob>> =
        combine(dao.observeAll(), speeds) { rows, sp -> rows.map { toJob(it, sp[it.id] ?: 0L) } }

    /** True while some job is queued or downloading (background work uses it to decide whether to schedule). */
    fun hasRunnableWork(): Flow<Boolean> =
        combine(dao.observeDueCount(listOf(S_QUEUED, S_DOWNLOADING)), dao.observeSeedingCount(), constraints) { due, seeding, c ->
            due > 0 || (seeding > 0 && torrents != null && !NetworkHold.seedBlocked(settings.current(), c))
        }.distinctUntilChanged()

    /** The earliest "Start later" time still waiting, so the scheduler can wake the app then. */
    fun nextStartAt(): Flow<Long?> = dao.observeEarliestStart().distinctUntilChanged()

    fun setNetworkState(wifi: Boolean, metered: Boolean, connected: Boolean) {
        constraints.update { it.copy(wifi = wifi, metered = metered, connected = connected) }
        scope.launch { reevaluate() }
    }

    fun setCharging(charging: Boolean) {
        constraints.update { it.copy(charging = charging) }
        scope.launch { reevaluate() }
    }

    /** Runs queued jobs up to "Downloads at once" and returns when nothing is runnable. */
    suspend fun runUntilIdle() = runMutex.withLock {
        try {
            coroutineScope {
                dao.withStatus(listOf(S_DOWNLOADING)).forEach { if (!runners.containsKey(it.id)) dao.setStatusIf(it.id, listOf(S_DOWNLOADING), S_QUEUED) }
                reevaluate()
                while (true) {
                    val cfg = settings.current()
                    bucket.bytesPerSecond = cfg.speedLimitKbps.coerceAtLeast(0) * 1024L
                    val room = cfg.downloadsAtOnce.coerceAtLeast(1) - runners.size
                    val now = clock()
                    val waiting = dao.withStatus(listOf(S_QUEUED)).filter { !runners.containsKey(it.id) }
                    val queued = waiting.filter { StartLater.isDue(it.startAt, now) }
                    for (row in queued.take(room.coerceAtLeast(0))) start(row.id, cfg)
                    val seedable = if (torrents != null && !NetworkHold.seedBlocked(cfg, constraints.value)) dao.seedingJobs().filter { !seeders.containsKey(it.id) } else emptyList()
                    for (row in seedable) startSeeder(row.id)
                    if (runners.isEmpty() && queued.isEmpty() && seeders.isEmpty()) break
                    // A "Start later" job that comes due while others run is picked up at its time.
                    val upcoming = StartLater.earliestPending(waiting.map { it.startAt }, now)
                    if (upcoming != null) withTimeoutOrNull(StartLater.delayMs(upcoming, now).coerceAtLeast(1)) { wake.receive() }
                    else wake.receive()
                }
            }
        } finally {
            withContext(NonCancellable) {
                // Cancelled from outside (work stopped): leave unfinished jobs queued, not "downloading".
                dao.withStatus(listOf(S_DOWNLOADING)).forEach { dao.setStatusIf(it.id, listOf(S_DOWNLOADING), S_QUEUED) }
            }
        }
    }

    private suspend fun CoroutineScope.start(id: Long, cfg: EngineConfig) {
        if (dao.setStatusIf(id, listOf(S_QUEUED), S_DOWNLOADING) == 0) return
        dao.clearSchedule(id)
        val kind = dao.get(id)?.kind
        val isVideo = kind == PhoneJobRow.KIND_VIDEO
        val isTransfer = kind == PhoneJobRow.KIND_TRANSFER
        val isTorrent = kind == PhoneJobRow.KIND_TORRENT
        val isCloud = kind == PhoneJobRow.KIND_CLOUD
        val job = launch(ioDispatcher, start = CoroutineStart.LAZY) {
            val engine = video
            val fetcher = transfers
            val bt = torrents
            if (isCloud) {
                val backend = cloud
                if (backend != null) CloudRunner(env, backend, id, cfg).run()
                else dao.moveIf(id, listOf("DOWNLOADING"), "FAILED", null, CloudText.UNAVAILABLE)
            } else if (isTorrent) {
                if (bt != null) TorrentRunner(env, bt, id, settings).run()
                else dao.moveIf(id, listOf("DOWNLOADING"), "FAILED", null, TorrentText.UNAVAILABLE)
            } else if (isTransfer) {
                if (fetcher != null) TransferRunner(env, fetcher, id).run()
                else dao.moveIf(id, listOf("DOWNLOADING"), "FAILED", null, TransferText.REFUSED)
            } else if (isVideo && engine != null) VideoRunner(env, engine, id, cfg).run() else JobRunner(env, id, cfg).run()
        }
        runners[id] = job
        job.invokeOnCompletion {
            runners.remove(id, job)
            wake.trySend(Unit)
        }
        job.start()
    }

    private fun CoroutineScope.startSeeder(id: Long) {
        val backend = torrents ?: return
        val job = launch(ioDispatcher, start = CoroutineStart.LAZY) { TorrentRunner(env, backend, id, settings).seed() }
        seeders[id] = job
        job.invokeOnCompletion {
            seeders.remove(id, job)
            wake.trySend(Unit)
        }
        job.start()
    }

    private suspend fun stopRunner(id: Long) {
        runners[id]?.cancelAndJoin()
        seeders[id]?.cancelAndJoin()
    }

    private fun blockFor(row: PhoneJobRow, cfg: EngineConfig, c: EngineConstraints): PhoneJobStatus? =
        NetworkHold.block(row.kind, cfg, c, row.mobileOverride)

    /** Moves jobs in and out of the waiting states to match settings and constraints. */
    private suspend fun reevaluate() = evalMutex.withLock {
        val cfg = settings.current()
        val c = constraints.value
        for (row in dao.withStatus(ACTIVE)) {
            val block = blockFor(row, cfg, c)
            val now = PhoneJobStatus.valueOf(row.status)
            if (block != null && block != now) {
                stopRunner(row.id)
                dao.setStatusIf(row.id, ACTIVE, block.name)
            } else if (block == null && now in WAITING) {
                dao.setStatusIf(row.id, listOf(row.status), S_QUEUED)
            }
        }
        // Seeding uploads: stop when mobile data is off-limits; the run loop starts it again on Wi-Fi.
        if (NetworkHold.seedBlocked(cfg, c)) seeders.keys.toList().forEach { seeders[it]?.cancelAndJoin() }
        wake.trySend(Unit)
    }

    override suspend fun add(request: PhoneAddRequest): PhoneJob {
        if (CloudSchemes.isCloud(request.url)) return addCloud(request)
        val id = addMutex.withLock {
            val now = clock()
            val route = if (request.torrentFileUri != null) LinkRoute.TORRENT_FILE else TorrentRouting.route(request.url)
            if (route == LinkRoute.MAGNET || route == LinkRoute.TORRENT_URL || route == LinkRoute.TORRENT_FILE) {
                return@withLock addTorrent(request, route, now)
            }
            if (request.url.trim().toHttpUrlOrNull() == null) {
                return@withLock dao.insert(
                    PhoneJobRow(
                        url = request.url, name = fileNameFor(request.title, request.url), folderUri = request.folderUri,
                        status = PhoneJobStatus.FAILED.name, error = EngineText.BAD_LINK, quality = request.quality, createdAt = now,
                    ),
                )
            }
            val url = request.url.trim()
            val isVideo = video != null && (request.quality != null || LinkKind.classify(url) == LinkKind.VIDEO)
            if (isVideo) {
                // DRM services are refused up front, with no job to retry.
                val refusal = DrmGuard.refusal(url)
                return@withLock dao.insert(
                    PhoneJobRow(
                        url = url, name = fileNameFor(request.title, url), folderUri = request.folderUri,
                        status = if (refusal != null) PhoneJobStatus.FAILED.name else S_QUEUED, error = refusal,
                        quality = request.quality ?: "best", kind = PhoneJobRow.KIND_VIDEO, createdAt = now,
                        startAt = request.startAt, notice = scheduledNotice(request.startAt, now), expectedSha256 = request.sha256,
                        formatSpec = request.formatSpec, subLang = request.subtitleLang,
                        clipStartSec = request.clipStartSec, clipEndSec = request.clipEndSec,
                    ),
                )
            }
            // Rule 7: unique across finished files and unfinished jobs in this folder.
            val taken = storage.existingNames(request.folderUri) +
                dao.all().filter { it.folderUri == request.folderUri && it.status !in DONE }.map { it.name }
            dao.insert(
                PhoneJobRow(
                    url = url, name = uniqueName(fileNameFor(request.title, url), taken), folderUri = request.folderUri,
                    status = S_QUEUED, quality = request.quality, createdAt = now,
                    startAt = request.startAt, notice = scheduledNotice(request.startAt, now), expectedSha256 = request.sha256,
                ),
            )
        }
        if (request.pcId != null) dao.setSource(id, request.pcId, request.outboxItemId)
        reevaluate()
        return toJob(dao.get(id)!!, 0)
    }

    /**
     * An ftp, ftps, sftp, scp, s3 or webdav link: one job, or (for a folder link) one job per file inside. A password typed
     * into the link is sealed by the Keystore and kept beside the job; the stored link never contains it.
     */
    private suspend fun addCloud(request: PhoneAddRequest): PhoneJob {
        val now = clock()
        fun failed(text: String) = PhoneJobRow(
            url = request.url.substringBefore("://") + "://", name = fileNameFor(request.title, "download"), folderUri = request.folderUri,
            status = PhoneJobStatus.FAILED.name, error = text, kind = PhoneJobRow.KIND_CLOUD, createdAt = now,
        )
        val backend = cloud
        val target = CloudUrl.parse(request.url)
        if (backend == null || target == null) {
            val id = addMutex.withLock { dao.insert(failed(if (backend == null) CloudText.UNAVAILABLE else CloudText.BAD_LINK)) }
            return toJob(dao.get(id)!!, 0)
        }
        val sealed = target.password?.let { backend.sealInline(it) }
        val clean = target.withoutPassword()
        // (file link, size) pairs; a folder link is listed first, outside the lock because it talks to the server.
        var notice: String? = null
        var files: List<Pair<String, Long?>> = listOf(clean to null)
        var listError: String? = null
        if (target.isFolder) {
            try {
                val entries = withContext(ioDispatcher) {
                    val conn = backend.create(backend.resolve(clean, sealed))
                    try { conn.connect(); conn.list() } finally { conn.close() }
                }
                val chosen = entries.sortedBy { it.name.lowercase() }
                if (chosen.size > MAX_FOLDER_FILES) notice = CloudText.tooMany(MAX_FOLDER_FILES)
                files = chosen.take(MAX_FOLDER_FILES).map { e ->
                    (if (e.fullPath != null) target.build(newRawPath = CloudUrl.encode(e.fullPath, keepSlash = true)).substringBefore('?') else target.child(e.name)) to e.size
                }
                if (files.isEmpty()) listError = CloudText.EMPTY_FOLDER
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                listError = CloudErrors.text(e)
            }
        }
        val firstId = addMutex.withLock {
            if (listError != null) return@withLock dao.insert(failed(listError).copy(url = clean))
            val taken = (storage.existingNames(request.folderUri) +
                dao.all().filter { it.folderUri == request.folderUri && it.status !in DONE }.map { it.name }).toMutableSet()
            var first = 0L
            for ((link, size) in files) {
                val name = uniqueName(fileNameFor((if (files.size == 1) request.title else null) ?: CloudUrl.suggestedFilename(link), link), taken)
                taken += name
                val id = dao.insert(
                    PhoneJobRow(
                        url = link, name = name, folderUri = request.folderUri, status = S_QUEUED, total = size, kind = PhoneJobRow.KIND_CLOUD,
                        createdAt = now, startAt = request.startAt, notice = if (first == 0L) notice ?: scheduledNotice(request.startAt, now) else scheduledNotice(request.startAt, now),
                        expectedSha256 = if (files.size == 1) request.sha256 else null, cloudSecret = sealed,
                    ),
                )
                if (first == 0L) first = id
            }
            first
        }
        reevaluate()
        return toJob(dao.get(firstId)!!, 0)
    }

    /** A magnet link, a .torrent link or a .torrent file: a job of kind "torrent" (the runner reads the metadata). */
    private suspend fun addTorrent(request: PhoneAddRequest, route: LinkRoute, now: Long): Long {
        val display = request.title?.takeIf { it.isNotBlank() } ?: request.torrentFileName
        val url = if (route == LinkRoute.TORRENT_FILE) TorrentRouting.FILE_PREFIX + (display ?: "file.torrent") else request.url.trim()
        val name = display?.removeSuffix(".torrent")?.let { fileNameFor(it, it) }
            ?: magnetName(url) ?: fileNameFor(null, url).removeSuffix(".torrent").ifBlank { "torrent" }
        var bytes: ByteArray? = null
        var failure: String? = if (torrents == null) TorrentText.UNAVAILABLE else null
        if (failure == null && route == LinkRoute.TORRENT_FILE) {
            bytes = request.torrentFileUri?.let { runCatching { torrentFiles?.invoke(it) }.getOrNull() }
            if (bytes == null || bytes.isEmpty() || bytes[0] != 'd'.code.toByte()) failure = TorrentText.NOT_A_TORRENT
        }
        val id = dao.insert(
            PhoneJobRow(
                url = url, name = name, folderUri = request.folderUri,
                status = if (failure != null) PhoneJobStatus.FAILED.name else S_QUEUED, error = failure, kind = PhoneJobRow.KIND_TORRENT,
                createdAt = now, startAt = request.startAt, notice = scheduledNotice(request.startAt, now),
            ),
        )
        if (bytes != null && failure == null) {
            // A shared content:// URI stops being readable, so the bytes live with the job.
            val dir = storage.scratchDir(id)
            dir.mkdirs()
            java.io.File(dir, "source.torrent").writeBytes(bytes)
        }
        return id
    }

    /** The "dn" (display name) of a magnet link, if it has one. */
    private fun magnetName(url: String): String? {
        if (!url.startsWith("magnet:", ignoreCase = true)) return null
        val dn = url.substringAfter('?').split('&').firstOrNull { it.startsWith("dn=") }?.substring(3) ?: return null
        val decoded = runCatching { java.net.URLDecoder.decode(dn, "UTF-8") }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return fileNameFor(decoded, decoded)
    }

    override suspend fun setTorrentSequential(jobId: Long, on: Boolean) {
        if (dao.get(jobId)?.kind == PhoneJobRow.KIND_TORRENT) dao.setSequential(jobId, on)
    }

    override suspend fun setTorrentFileSelected(jobId: Long, index: Int, selected: Boolean) {
        val row = dao.get(jobId)?.takeIf { it.kind == PhoneJobRow.KIND_TORRENT } ?: return
        val files = StoredFiles.decode(row.torrentFiles)
        if (index !in files.indices || row.status == S_COMPLETED) return
        val wanted = StoredFiles.wanted(row.fileMask, files.size).toMutableList()
        wanted[index] = selected
        if (wanted.none { it }) return // at least one file stays selected
        dao.setFileMask(jobId, StoredFiles.mask(wanted))
    }

    override suspend fun stopSeeding(jobId: Long) {
        val row = dao.get(jobId) ?: return
        if (!row.seeding) return
        dao.stopSeeding(jobId)
        seeders[jobId]?.cancelAndJoin()
        row.infoHash?.let { torrents?.remove(it, deleteFiles = true) }
        storage.deleteScratch(jobId)
        wake.trySend(Unit)
    }

    override suspend fun addTransfer(request: TransferAddRequest): PhoneJob {
        val id = addMutex.withLock {
            dao.activeTransfer(request.pcId, request.remoteJobId)?.let { return@withLock it.id }
            val taken = storage.existingNames(request.folderUri) +
                dao.all().filter { it.folderUri == request.folderUri && it.status !in DONE }.map { it.name }
            dao.insert(
                PhoneJobRow(
                    url = request.url, name = uniqueName(fileNameFor(request.name, request.url), taken), folderUri = request.folderUri,
                    status = S_QUEUED, total = request.size, kind = PhoneJobRow.KIND_TRANSFER,
                    pcId = request.pcId, remoteJobId = request.remoteJobId, createdAt = clock(),
                ),
            )
        }
        reevaluate()
        return toJob(dao.get(id)!!, 0)
    }

    private fun scheduledNotice(startAt: Long?, now: Long): String? =
        startAt?.takeIf { it > now }?.let { StartLater.notice(it, now) }

    override suspend fun pause(jobId: Long) {
        stopRunner(jobId)
        dao.setStatusIf(jobId, ACTIVE, S_PAUSED)
        wake.trySend(Unit)
    }

    override suspend fun resume(jobId: Long) {
        if (dao.setStatusIf(jobId, listOf(S_PAUSED), S_QUEUED) > 0) reevaluate()
    }

    /** Rule 10: cancelling removes the partial and scratch files. */
    override suspend fun cancel(jobId: Long) {
        stopRunner(jobId)
        if (dao.moveIf(jobId, ACTIVE + listOf(S_PAUSED, S_FAILED), S_CANCELLED, null, null) > 0) {
            storage.deleteScratch(jobId)
            dao.deleteSegments(jobId)
            dao.clearProgress(jobId)
        }
        wake.trySend(Unit)
    }

    override suspend fun retry(jobId: Long) {
        if (dao.moveIf(jobId, listOf(S_FAILED, S_CANCELLED), S_QUEUED, null, null) > 0) {
            dao.update(dao.get(jobId)!!.copy(error = null, notice = null))
            reevaluate()
        }
    }

    override suspend fun remove(jobId: Long) {
        stopRunner(jobId)
        val row = dao.get(jobId) ?: return
        if (row.seeding) stopSeeding(jobId)
        if (row.status != S_COMPLETED) storage.deleteScratch(jobId)
        dao.delete(jobId)
        wake.trySend(Unit)
    }

    override suspend fun pauseAll() {
        dao.withStatus(ACTIVE).forEach { pause(it.id) }
    }

    override suspend fun resumeAll() {
        dao.withStatus(listOf(S_PAUSED)).forEach { resume(it.id) }
    }

    override suspend fun allowMobileData(jobId: Long) {
        dao.allowMobile(jobId)
        reevaluate()
    }

    private fun toJob(r: PhoneJobRow, speed: Long): PhoneJob {
        val remaining = r.total?.minus(r.downloaded)
        return PhoneJob(
            id = r.id, url = r.url, name = r.name,
            status = runCatching { PhoneJobStatus.valueOf(r.status) }.getOrDefault(PhoneJobStatus.FAILED),
            downloaded = r.downloaded, total = r.total, speed = speed,
            etaSeconds = if (speed > 0 && remaining != null) (remaining / speed).coerceAtLeast(0) else null,
            connections = r.connections, notice = r.notice, error = r.error, fileUri = r.fileUri,
            createdAt = r.createdAt, finishedAt = r.finishedAt, isVideo = r.kind == PhoneJobRow.KIND_VIDEO, isTransfer = r.kind == PhoneJobRow.KIND_TRANSFER,
            startAt = r.startAt?.takeIf { r.status == S_QUEUED || r.status in WAITING_NAMES }, checksum = r.checksumState,
            torrent = if (r.kind != PhoneJobRow.KIND_TORRENT) null else {
                val files = StoredFiles.decode(r.torrentFiles)
                val wanted = StoredFiles.wanted(r.fileMask, files.size)
                TorrentDetails(
                    peers = r.peers, uploaded = r.uploaded, ratio = SeedRules.ratio(r.uploaded, r.total ?: files.sumOf { it.size }),
                    sequential = r.sequential, seeding = r.seeding,
                    files = files.mapIndexed { i, f -> TorrentFileView(i, f.path, f.size, wanted[i]) },
                )
            },
        )
    }

    private companion object {
        /** A folder link queues at most this many files. */
        const val MAX_FOLDER_FILES = 500
        val S_QUEUED = PhoneJobStatus.QUEUED.name
        val S_DOWNLOADING = PhoneJobStatus.DOWNLOADING.name
        val S_PAUSED = PhoneJobStatus.PAUSED.name
        val S_FAILED = PhoneJobStatus.FAILED.name
        val S_CANCELLED = PhoneJobStatus.CANCELLED.name
        val S_COMPLETED = PhoneJobStatus.COMPLETED.name
        val WAITING_NAMES = setOf("WAITING_NETWORK", "WAITING_WIFI", "WAITING_CHARGING")
        val WAITING = setOf(PhoneJobStatus.WAITING_NETWORK, PhoneJobStatus.WAITING_WIFI, PhoneJobStatus.WAITING_CHARGING)
        /** Jobs the engine still wants to run: queued, downloading or held by a constraint. */
        val ACTIVE = listOf(S_QUEUED, S_DOWNLOADING) + WAITING.map { it.name }
        val DONE = setOf(S_COMPLETED, S_CANCELLED)
    }
}
