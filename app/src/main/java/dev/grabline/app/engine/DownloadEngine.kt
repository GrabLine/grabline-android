package dev.grabline.app.engine

import dev.grabline.app.data.PhoneJobDao
import dev.grabline.app.data.PhoneJobRow
import dev.grabline.app.engine.video.DrmGuard
import dev.grabline.app.engine.video.VideoEngine
import dev.grabline.app.ui.entry.LinkKind
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    random: Random = Random.Default,
    override val video: VideoEngine? = null,
    /** Copies files from a PC for jobs of kind "transfer"; null where transfers are not wired. */
    private val transfers: dev.grabline.app.remote.transfer.FetchClient? = null,
) : PhoneEngine {
    private val constraints = MutableStateFlow(EngineConstraints())
    private val speeds = MutableStateFlow<Map<Long, Long>>(emptyMap())
    private val bucket = TokenBucket(clock)
    private val runners = ConcurrentHashMap<Long, Job>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val addMutex = Mutex()
    private val runMutex = Mutex()
    private val evalMutex = Mutex()
    private val env = Env(dao, client, storage, bucket, clock, ioDispatcher, minSegmentBytes, backoffBaseMs, random) { id, bps ->
        speeds.update { if (bps <= 0) it - id else it + (id to bps) }
    }

    override val jobs: Flow<List<PhoneJob>> =
        combine(dao.observeAll(), speeds) { rows, sp -> rows.map { toJob(it, sp[it.id] ?: 0L) } }

    /** True while some job is queued or downloading (background work uses it to decide whether to schedule). */
    fun hasRunnableWork(): Flow<Boolean> =
        dao.observeDueCount(listOf(S_QUEUED, S_DOWNLOADING)).map { it > 0 }.distinctUntilChanged()

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
                    if (runners.isEmpty() && queued.isEmpty()) break
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
        val job = launch(ioDispatcher, start = CoroutineStart.LAZY) {
            val engine = video
            val fetcher = transfers
            if (isTransfer) {
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

    private suspend fun stopRunner(id: Long) {
        runners[id]?.cancelAndJoin()
    }

    private fun blockFor(row: PhoneJobRow, cfg: EngineConfig, c: EngineConstraints): PhoneJobStatus? = when {
        !c.connected -> PhoneJobStatus.WAITING_NETWORK
        cfg.onlyWifi && !row.mobileOverride && (!c.wifi || c.metered) -> PhoneJobStatus.WAITING_WIFI
        cfg.onlyCharging && !c.charging -> PhoneJobStatus.WAITING_CHARGING
        else -> null
    }

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
        wake.trySend(Unit)
    }

    override suspend fun add(request: PhoneAddRequest): PhoneJob {
        val id = addMutex.withLock {
            val now = clock()
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
        )
    }

    private companion object {
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
