package dev.grabline.app.engine

import dev.grabline.app.data.PhoneJobDao
import dev.grabline.app.data.PhoneJobRow
import dev.grabline.app.data.PhoneSegmentRow
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Everything a runner needs from the engine. */
internal class Env(
    val dao: PhoneJobDao,
    val client: OkHttpClient,
    val storage: PhoneStorage,
    val bucket: TokenBucket,
    val clock: () -> Long,
    val io: CoroutineDispatcher,
    val minSegment: Long,
    val backoffBaseMs: Long,
    val random: Random,
    /** False once the phone has no network; a runner then waits for it instead of failing. */
    val online: () -> Boolean = { true },
    /** How long a runner out of retries waits for Android to report the network gone. */
    val networkGraceMs: Long = 3_000,
    val onSpeed: (Long, Long) -> Unit,
)

private class Permanent(val text: String, val keepScratch: Boolean = false) : IOException(text)
private class Changed : IOException("file changed")
private class RangesIgnored : IOException("ranges ignored")
private class Pushback(val code: Int, val retryAfterMs: Long?) : IOException("pushback $code")
/** The phone lost its network: not the server's fault, so it does not use up the retries. */
private class Offline : IOException("offline")

internal class Probe(val size: Long?, val ranges: Boolean, val etag: String?, val lastModified: String?) {
    val validator: String? get() = etag ?: lastModified
}

internal class Seg(val idx: Int, val start: Long, var end: Long, var pos: Long, var done: Long) {
    var owned = false
}

/** The byte ranges of one job. Every access is under the plan's lock. */
internal class SegmentPlan(initial: List<Seg>, private val minSeg: Long) {
    private val segs = initial.toMutableList()
    private var nextIdx = (initial.maxOfOrNull { it.idx } ?: -1) + 1

    /** An unowned unfinished range, else the tail half of the biggest owned one (work stealing). */
    @Synchronized fun acquire(allowSteal: Boolean): Seg? {
        segs.firstOrNull { !it.owned && it.pos < it.end }?.let { it.owned = true; return it }
        if (!allowSteal) return null
        val victim = segs.filter { it.owned && it.end != Long.MAX_VALUE }.maxByOrNull { it.end - it.pos } ?: return null
        val remaining = victim.end - victim.pos
        if (remaining < 2 * minSeg) return null
        val mid = victim.pos + remaining / 2
        val stolen = Seg(nextIdx++, mid, victim.end, mid, mid).also { it.owned = true }
        victim.end = mid
        segs += stolen
        return stolen
    }

    @Synchronized fun range(s: Seg): Pair<Long, Long> = s.pos to s.end

    /** Bytes this worker may write now (0 = range finished or stolen). */
    @Synchronized fun claim(s: Seg, n: Int): Int {
        val a = minOf(n.toLong(), s.end - s.pos).toInt()
        if (a > 0) s.pos += a
        return a.coerceAtLeast(0)
    }

    @Synchronized fun wrote(s: Seg, n: Int) { s.done += n }
    @Synchronized fun release(s: Seg) { s.pos = s.done; s.owned = false }
    @Synchronized fun restart(s: Seg) { s.pos = s.start; s.done = s.start }
    @Synchronized fun finishUnknown(s: Seg) { s.end = s.done; s.pos = s.done }
    @Synchronized fun remaining(s: Seg) = s.end - s.pos
    @Synchronized fun isComplete() = segs.all { it.done >= it.end }
    @Synchronized fun totalDone() = segs.sumOf { it.done - it.start }
    @Synchronized fun snapshot(jobId: Long) = segs.map { PhoneSegmentRow(jobId, it.idx, it.start, it.end, it.done) }
    @Synchronized fun count() = segs.size
}

private class RunState(var cap: Int, var remembered: Int?) {
    val failures = AtomicInteger(0)
    @Volatile var lastCheckpoint = 0L
    var lastHalved = 0L
    var lastSampleAt = 0L
    var lastSampleBytes = 0L
    var speed = 0L
}

/** Downloads one job: probe, plan, fetch, verify, publish. Pausing or cancelling is done by cancelling this coroutine. */
internal class JobRunner(private val env: Env, private val id: Long, private val cfg: EngineConfig) {
    private val dao get() = env.dao
    private val budget = if (cfg.autoRetry < 0) Int.MAX_VALUE else cfg.autoRetry
    private var forceSingle = false
    private val checkpointMutex = Mutex()
    private var state = RunState(1, null)

    suspend fun run() {
        var restarts = 0
        try {
            while (true) {
                try {
                    attempt()
                    return
                } catch (e: Changed) {
                    if (++restarts > 3) throw Permanent(EngineText.CHANGING)
                    resetProgress(EngineText.FILE_CHANGED)
                } catch (e: RangesIgnored) {
                    forceSingle = true
                    resetProgress(EngineText.NO_RESUME)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Permanent) {
            if (!e.keepScratch) env.storage.deleteScratch(id)
            fail(e.text)
        } catch (e: Offline) {
            // Keep the partial file and wait for the network. If it came back while this unwound,
            // queue the job again straight away (the network monitor has already looked at it).
            withContext(NonCancellable) {
                dao.moveIf(id, listOf("DOWNLOADING"), "WAITING_NETWORK", null, null)
                if (env.online()) dao.moveIf(id, listOf("WAITING_NETWORK"), "QUEUED", null, null)
            }
        } catch (e: FolderUnavailableException) {
            // The download is intact; hold it until the user picks another folder.
            withContext(NonCancellable) { dao.moveIf(id, listOf("DOWNLOADING"), "PAUSED", EngineText.FOLDER, null) }
        } catch (e: IOException) {
            fail(if (isNoSpace(e)) EngineText.OUT_OF_SPACE else EngineText.CONNECTION)
        } catch (e: Throwable) {
            fail(EngineText.UNEXPECTED)
        } finally {
            env.onSpeed(id, 0)
        }
    }

    private suspend fun fail(text: String) {
        withContext(NonCancellable) { dao.moveIf(id, listOf("DOWNLOADING"), "FAILED", null, text) }
    }

    private suspend fun resetProgress(notice: String) {
        env.storage.deleteScratch(id)
        dao.deleteSegments(id)
        dao.clearProgress(id)
        dao.saveProbe(id, null, null, null, false, notice)
    }

    private fun isNoSpace(e: IOException): Boolean {
        val m = e.message.orEmpty()
        return m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true)
    }

    private fun backoff(n: Int): Long {
        val base = minOf(env.backoffBaseMs shl (n - 1).coerceIn(0, 10), 30_000L)
        return (base * (0.5 + env.random.nextDouble() * 0.5)).toLong().coerceAtLeast(1)
    }

    /**
     * Counts one failure with no progress; gives up when the budget is spent (rule 6).
     * Losing the network is not counted: every connection fails at once then, well before
     * Android reports the network gone, so the job is handed back to wait for it instead.
     */
    private suspend fun countFailure(): Int {
        if (!env.online()) throw Offline()
        val n = state.failures.incrementAndGet()
        if (n > budget) {
            // Android reports a lost network a moment after the connections drop; wait for it.
            withTimeoutOrNull(env.networkGraceMs) { while (env.online()) delay(50) }
            if (!env.online()) throw Offline()
            throw Permanent(EngineText.CONNECTION, keepScratch = true)
        }
        return n
    }

    private suspend fun <T> retrying(block: suspend () -> T): T {
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Permanent) {
                throw e
            } catch (e: Pushback) {
                val n = state.failures.incrementAndGet()
                if (n > budget) throw Permanent(EngineText.PUSHBACK, keepScratch = true)
                delay(e.retryAfterMs ?: backoff(n))
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                delay(backoff(countFailure()))
            }
        }
    }

    private fun strong(etag: String?) = etag?.takeIf { it.isNotBlank() && !it.startsWith("W/") }

    private fun retryAfter(resp: Response): Long? =
        resp.header("Retry-After")?.trim()?.toLongOrNull()?.let { (it * 1000).coerceIn(0, 30_000) }

    /** Rule 1: a body the server compressed anyway is refused. */
    private fun checkEncoding(resp: Response) {
        val enc = resp.header("Content-Encoding")?.trim()
        if (enc != null && !enc.equals("identity", ignoreCase = true)) throw Permanent(EngineText.COMPRESSED)
    }

    /** Runs a call that is cancelled when the coroutine is. */
    private suspend fun <T> execute(request: Request, use: suspend (Response) -> T): T {
        val call = env.client.newCall(request)
        return coroutineScope {
            val watcher = launch(env.io) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.execute().use { use(it) }
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                throw e
            } finally {
                watcher.cancel()
            }
        }
    }

    private suspend fun probe(url: String): Probe {
        val req = Request.Builder().url(url).header("Range", "bytes=0-0").header("Accept-Encoding", "identity").build()
        return execute(req) { resp ->
            checkEncoding(resp)
            when (resp.code) {
                206 -> {
                    val cr = parseContentRange(resp.header("Content-Range"))
                    if (cr == null || cr.start != 0L) throw Permanent(EngineText.WRONG_RANGE)
                    Probe(cr.total, cr.total != null, strong(resp.header("ETag")), resp.header("Last-Modified"))
                }
                200 -> Probe(
                    resp.body?.contentLength()?.takeIf { it >= 0 },
                    false, strong(resp.header("ETag")), resp.header("Last-Modified"),
                )
                416 -> if (resp.header("Content-Range")?.trim()?.matches(Regex("""bytes \*/0""", RegexOption.IGNORE_CASE)) == true) {
                    Probe(0, false, null, null)
                } else throw Permanent(EngineText.server(416))
                429, 503 -> throw Pushback(resp.code, retryAfter(resp))
                401, 403 -> throw Permanent(EngineText.REFUSED)
                404, 410 -> throw Permanent(EngineText.NOT_FOUND)
                in 400..499 -> throw Permanent(EngineText.server(resp.code))
                else -> throw IOException("HTTP ${resp.code}")
            }
        }
    }

    private fun sameFile(job: PhoneJobRow, p: Probe): Boolean = job.total == p.size && when {
        job.etag != null && p.etag != null -> job.etag == p.etag
        job.lastModified != null && p.lastModified != null -> job.lastModified == p.lastModified
        else -> false
    }

    private suspend fun attempt() {
        val job0 = dao.get(id) ?: return
        state = RunState(1, job0.connectionCap)
        val probe = retrying { probe(job0.url) }
        val scratch = env.storage.scratchFile(id)
        var segRows = dao.segments(id)
        val hadProgress = (job0.downloaded > 0 || segRows.isNotEmpty()) && scratch.exists()
        val canResume = probe.ranges && probe.size != null && probe.validator != null && !forceSingle
        var notice: String? = if (!canResume) EngineText.NO_RESUME else null
        var reset = !hadProgress && segRows.isNotEmpty()
        if (hadProgress) {
            if (!canResume) reset = true
            else if (!sameFile(job0, probe)) { reset = true; notice = EngineText.FILE_CHANGED }
        }
        if (reset) {
            scratch.delete(); dao.deleteSegments(id); dao.clearProgress(id); segRows = emptyList()
        }
        val total = probe.size
        val rangeMode = probe.ranges && total != null && !forceSingle

        // Rule 8: free space before starting.
        if (total != null) {
            val need = total - if (reset) 0 else dao.get(id)!!.downloaded
            val free = env.storage.freeBytes(job0.folderUri)
            if (need > free) throw Permanent(EngineText.needsSpace(need, free), keepScratch = true)
        }
        dao.saveProbe(id, total, probe.etag, probe.lastModified, rangeMode, notice)

        val cap = minOf(cfg.connectionsPerFile, job0.connectionCap ?: Int.MAX_VALUE).coerceAtLeast(1)
        state.cap = if (rangeMode) cap else 1
        val plan = SegmentPlan(initialSegments(segRows, total, rangeMode, cap), env.minSegment)

        var finished = false
        RandomAccessFile(scratch, "rw").use { raf ->
            if (total != null && raf.length() != total) raf.setLength(total)
            try {
                runWorkers(Ctx(plan, raf, job0.url, total, rangeMode, probe.validator))
                finish(plan, raf, total, job0)
                finished = true
            } finally {
                if (!finished) withContext(NonCancellable) { runCatching { checkpoint(plan, force = true) } }
            }
        }
        if (finished) scratch.delete() // after the file is closed (Windows can't delete an open file)
    }

    private fun initialSegments(rows: List<PhoneSegmentRow>, total: Long?, rangeMode: Boolean, cap: Int): List<Seg> {
        if (!rangeMode) return listOf(Seg(0, 0, total ?: Long.MAX_VALUE, 0, 0))
        if (rows.isNotEmpty()) return rows.map { Seg(it.idx, it.start, it.end, it.done, it.done) }
        val size = total!!
        if (size == 0L) return emptyList()
        val n = minOf(cap.toLong(), maxOf(1L, size / env.minSegment)).toInt().coerceAtLeast(1)
        val step = size / n
        return (0 until n).map { i ->
            val s = i * step
            Seg(i, s, if (i == n - 1) size else s + step, s, s)
        }
    }

    private class Ctx(
        val plan: SegmentPlan, val raf: RandomAccessFile, val url: String,
        val total: Long?, val rangeMode: Boolean, val validator: String?,
    )

    private suspend fun runWorkers(c: Ctx) {
        while (true) {
            coroutineScope {
                repeat(if (c.rangeMode) state.cap else 1) { i -> launch(env.io) { worker(c, i) } }
            }
            if (c.plan.isComplete()) return
            delay(backoff(countFailure())) // ranges left over with nobody to take them
        }
    }

    private suspend fun worker(c: Ctx, index: Int) {
        while (true) {
            coroutineContext.ensureActive()
            if (index >= state.cap) return
            val seg = c.plan.acquire(allowSteal = c.rangeMode) ?: return
            try {
                fetch(c, seg, index)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Permanent) {
                throw e
            } catch (e: Pushback) {
                onPushback(e)
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                if (e is Changed || e is RangesIgnored || e is Offline) throw e
                if (isNoSpace(e)) throw Permanent(EngineText.OUT_OF_SPACE, keepScratch = true)
                val n = countFailure()
                if (!c.rangeMode) c.plan.restart(seg)
                delay(backoff(n))
            } finally {
                c.plan.release(seg)
            }
        }
    }

    /** Rule 5: halve the connections and remember that for this job. */
    private suspend fun onPushback(e: Pushback) {
        val now = env.clock()
        val first = synchronized(state) {
            val fresh = now - state.lastHalved > 500
            if (fresh) {
                state.lastHalved = now
                state.cap = maxOf(1, state.cap / 2)
                state.remembered = state.cap
            }
            fresh || state.cap == 1
        }
        val n = if (first) state.failures.incrementAndGet() else state.failures.get()
        if (n > budget) throw Permanent(EngineText.PUSHBACK, keepScratch = true)
        delay(e.retryAfterMs ?: backoff(maxOf(1, n)))
    }

    private suspend fun fetch(c: Ctx, seg: Seg, index: Int) {
        while (true) {
            coroutineContext.ensureActive()
            if (index >= state.cap) return
            val (reqStart, reqEnd) = c.plan.range(seg)
            if (reqStart >= reqEnd) return
            val b = Request.Builder().url(c.url).header("Accept-Encoding", "identity")
            val ifRange = c.validator?.takeIf { c.rangeMode }
            if (c.rangeMode) {
                b.header("Range", "bytes=$reqStart-${if (reqEnd == Long.MAX_VALUE) "" else (reqEnd - 1).toString()}")
                ifRange?.let { b.header("If-Range", it) } // rule 3
            }
            val before = c.plan.totalDone()
            execute(b.build()) { resp -> handle(c, seg, index, resp, reqStart, reqEnd, ifRange != null) }
            if (index < state.cap && c.plan.totalDone() == before && c.plan.remaining(seg) > 0) throw IOException("The server sent no data")
        }
    }

    private suspend fun handle(c: Ctx, seg: Seg, index: Int, resp: Response, reqStart: Long, reqEnd: Long, sentIfRange: Boolean) {
        val code = resp.code
        when {
            code == 206 && c.rangeMode -> {
                checkEncoding(resp)
                val cr = parseContentRange(resp.header("Content-Range"))
                // Rule 2: the reply must be the range we asked for, before a byte is written.
                if (cr == null || cr.start != reqStart || (reqEnd != Long.MAX_VALUE && cr.end > reqEnd - 1)) throw Permanent(EngineText.WRONG_RANGE)
                if (cr.total != null && cr.total != c.total) throw Changed()
                stream(c, seg, index, resp, reqStart)
            }
            code == 206 -> throw Permanent(EngineText.WRONG_RANGE)
            code == 200 && c.rangeMode -> throw if (sentIfRange) Changed() else RangesIgnored() // rule 3
            code == 200 -> {
                checkEncoding(resp)
                if (reqStart != 0L) throw IOException("restart needed")
                stream(c, seg, index, resp, 0)
            }
            code == 416 -> throw if (c.total != null) Changed() else Permanent(EngineText.server(416)) // rule 4
            code == 429 || code == 503 -> throw Pushback(code, retryAfter(resp))
            code == 401 -> throw Permanent(EngineText.REFUSED)
            code == 403 -> throw if (c.plan.totalDone() > 0) Pushback(403, null) else Permanent(EngineText.REFUSED)
            code == 404 || code == 410 -> throw Permanent(EngineText.NOT_FOUND)
            code in 400..499 -> throw Permanent(EngineText.server(code))
            else -> throw IOException("HTTP $code")
        }
    }

    private suspend fun stream(c: Ctx, seg: Seg, index: Int, resp: Response, startOffset: Long) {
        val src = (resp.body ?: throw IOException("Empty reply")).source()
        val buf = ByteArray(32 * 1024)
        var offset = startOffset
        while (true) {
            coroutineContext.ensureActive()
            if (index >= state.cap) return
            val n = src.read(buf)
            if (n < 0) break
            val a = c.plan.claim(seg, n)
            if (a <= 0) return
            env.bucket.acquire(a)
            try {
                val bb = ByteBuffer.wrap(buf, 0, a)
                var at = offset
                while (bb.hasRemaining()) at += c.raf.channel.write(bb, at)
            } catch (e: IOException) {
                throw if (isNoSpace(e)) Permanent(EngineText.OUT_OF_SPACE, keepScratch = true) else e
            }
            offset += a
            c.plan.wrote(seg, a)
            state.failures.set(0)
            checkpoint(c.plan, force = false)
            if (a < n) return
        }
        if (!c.rangeMode) {
            if (c.total == null) c.plan.finishUnknown(seg)
            else if (c.plan.remaining(seg) > 0) throw IOException("The server ended the file early")
        }
    }

    private suspend fun checkpoint(plan: SegmentPlan, force: Boolean) {
        val now = env.clock()
        if (!force && now - state.lastCheckpoint < CHECKPOINT_MS) return
        state.lastCheckpoint = now
        checkpointMutex.withLock {
            val done = plan.totalDone()
            val rows = plan.snapshot(id)
            dao.checkpoint(id, done, state.cap, state.remembered, rows)
            if (state.lastSampleAt != 0L && now > state.lastSampleAt) {
                val inst = (done - state.lastSampleBytes) * 1000 / (now - state.lastSampleAt)
                state.speed = if (state.speed == 0L) inst else (state.speed + inst) / 2
                env.onSpeed(id, state.speed.coerceAtLeast(0))
            }
            state.lastSampleAt = now
            state.lastSampleBytes = done
        }
    }

    private suspend fun finish(plan: SegmentPlan, raf: RandomAccessFile, total: Long?, job: PhoneJobRow) {
        val done = plan.totalDone()
        val size = total ?: done
        if (done != size) throw IOException("Only $done of $size bytes arrived")
        withContext(NonCancellable) { checkpoint(plan, force = true) } // also saves a remembered connection limit
        if (total == null) raf.channel.truncate(done)
        // Rule 9: the size check comes before the file takes its real name.
        if (raf.length() != size) throw Permanent(EngineText.UNEXPECTED)
        val scratch = env.storage.scratchFile(id)
        // Optional SHA-256 check: advisory, the file is kept either way.
        val checksum = dao.get(id)?.expectedSha256?.let { runCatching { Checksum.verify(scratch, it) }.getOrNull() }
        val uri = env.storage.publish(job.folderUri, dao.get(id)?.name ?: job.name, scratch)
        withContext(NonCancellable) {
            if (dao.complete(id, uri, size, env.clock()) > 0) {
                dao.deleteSegments(id)
                if (checksum != null) dao.setChecksum(id, checksum, Checksum.noticeFor(checksum).orEmpty())
            }
        }
    }

    private companion object {
        /** Segment progress reaches Room at most this often. */
        const val CHECKPOINT_MS = 500L
    }
}
