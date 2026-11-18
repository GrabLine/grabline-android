package dev.grabline.app.engine.torrent

import dev.grabline.app.engine.Env
import dev.grabline.app.engine.EngineConfig
import dev.grabline.app.engine.EngineSettings
import dev.grabline.app.engine.EngineText
import dev.grabline.app.engine.FolderUnavailableException
import dev.grabline.app.engine.fileNameFor
import dev.grabline.app.engine.uniqueName
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Plain-words messages for torrents (SPEC "Error states and edge cases"). */
object TorrentText {
    const val NOT_A_TORRENT = "That isn't a torrent file, or it can't be read"
    const val UNAVAILABLE = "Torrents aren't available on this phone"
    const val NO_PEERS_FILE = "The torrent's files could not be saved. Try again"
}

/**
 * Runs one torrent job through [TorrentBackend]. Scratch layout in the job's scratch folder:
 * `data/` (the files), `resume.dat` (libtorrent resume data, so a restart does not re-check from zero) and
 * `source.torrent` (the .torrent bytes, kept because a shared content:// URI stops being readable).
 * Pausing, holding for the network and cancelling cancel this coroutine; the torrent leaves the session and
 * its resume data is saved first. [seed] keeps a finished torrent going under the "Seed after download" rule.
 */
internal class TorrentRunner(
    private val env: Env,
    private val backend: TorrentBackend,
    private val id: Long,
    private val settings: EngineSettings,
) {
    private val dao get() = env.dao
    private val dir get() = env.storage.scratchDir(id)
    private val data get() = File(dir, "data")
    private val resumeFile get() = File(dir, "resume.dat")
    private val sourceFile get() = File(dir, "source.torrent")

    suspend fun run() {
        try {
            attempt()
        } catch (e: CancellationException) {
            throw e
        } catch (e: FolderUnavailableException) {
            // The download is intact; hold it until the user picks another folder.
            withContext(NonCancellable) { dao.moveIf(id, listOf("DOWNLOADING"), "PAUSED", EngineText.FOLDER, null) }
        } catch (e: NotATorrent) {
            fail(TorrentText.NOT_A_TORRENT)
        } catch (e: IOException) {
            debugLog(e)
            fail(if (outOfSpace(e)) EngineText.OUT_OF_SPACE else e.message?.takeIf { it.startsWith("Needs ") } ?: EngineText.UNEXPECTED)
        } catch (e: Throwable) {
            debugLog(e)
            fail(EngineText.UNEXPECTED)
        } finally {
            env.onSpeed(id, 0)
        }
    }

    // Debug builds only: the user sees a plain message, a developer needs the cause.
    private fun debugLog(e: Throwable) {
        if (dev.grabline.app.BuildConfig.DEBUG) android.util.Log.w("GrabLine", "torrent job failed", e)
    }

    private fun outOfSpace(e: IOException) = e.message.orEmpty().let { it.contains("ENOSPC") || it.contains("No space left", true) }

    private suspend fun fail(text: String) {
        withContext(NonCancellable) { dao.moveIf(id, listOf("DOWNLOADING"), "FAILED", null, text) }
    }

    private class NotATorrent : IOException("not a torrent")

    private suspend fun source(url: String): TorrentSource {
        if (sourceFile.exists()) return TorrentSource.Metainfo(sourceFile.readBytes())
        return when (TorrentRouting.route(url)) {
            LinkRoute.MAGNET -> TorrentSource.Magnet(url.trim())
            LinkRoute.TORRENT_URL -> {
                val bytes = fetchTorrent(url.trim())
                dir.mkdirs()
                sourceFile.writeBytes(bytes)
                TorrentSource.Metainfo(bytes)
            }
            else -> throw NotATorrent()
        }
    }

    private suspend fun fetchTorrent(url: String): ByteArray = withContext(env.io) {
        val request = Request.Builder().url(url).header("Accept-Encoding", "identity").build()
        env.client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw NotATorrent()
            // Read at most one byte past the limit (readByteArray(n) would demand exactly n bytes).
            val source = body.source()
            val buffer = okio.Buffer()
            while (buffer.size <= MAX_TORRENT_FILE && source.read(buffer, 8192) != -1L) Unit
            val bytes = buffer.readByteArray()
            if (bytes.size > MAX_TORRENT_FILE || bytes.isEmpty() || bytes[0] != 'd'.code.toByte()) throw NotATorrent()
            bytes
        }
    }

    private suspend fun attempt() {
        val job = dao.get(id) ?: return
        dir.mkdirs()
        val src = source(job.url)
        val hash = backend.add(
            src, data, resumeFile.takeIf { it.exists() }?.readBytes(), job.sequential,
            if (job.torrentFiles != null) StoredFiles.wanted(job.fileMask, StoredFiles.decode(job.torrentFiles).size) else null,
        )
        var finished = false
        try {
            var applied = Applied(job.sequential, job.fileMask)
            var metaSaved = job.torrentFiles != null
            var fileCount = StoredFiles.decode(job.torrentFiles).size
            var lastResume = env.clock()
            var lastBytes = 0L
            var lastAt = env.clock()
            while (true) {
                val snap = backend.snapshot(hash) ?: throw IOException("The torrent went away")
                if (snap.error != null && snap.error.contains("space", true)) throw IOException("No space left on device")
                if (snap.hasMetadata && !metaSaved) {
                    metaSaved = true
                    fileCount = snap.files.size
                    val name = snap.name.ifBlank { job.name }
                    withContext(NonCancellable) { dao.saveTorrentMeta(id, hash, StoredFiles.encode(snap.files), fileNameFor(name, name)) }
                    backend.setWanted(hash, StoredFiles.wanted(applied.mask, fileCount))
                    val need = snap.wantedBytes - snap.doneBytes
                    val free = env.storage.freeBytes(job.folderUri)
                    if (need > free) throw IOException(EngineText.needsSpace(need, free))
                }
                val row = dao.get(id) ?: return
                if (row.sequential != applied.sequential) {
                    backend.setSequential(hash, row.sequential)
                    applied = applied.copy(sequential = row.sequential)
                }
                if (metaSaved && row.fileMask != applied.mask) {
                    backend.setWanted(hash, StoredFiles.wanted(row.fileMask, fileCount))
                    applied = applied.copy(mask = row.fileMask)
                }
                val now = env.clock()
                val bps = if (now > lastAt) (snap.doneBytes - lastBytes).coerceAtLeast(0) * 1000 / (now - lastAt) else 0L
                lastAt = now
                lastBytes = snap.doneBytes
                env.onSpeed(id, if (snap.downRate > 0) snap.downRate else bps)
                dao.saveTorrentProgress(id, snap.doneBytes, snap.wantedBytes.takeIf { snap.hasMetadata }, snap.peers, snap.uploaded)
                if (now - lastResume >= RESUME_EVERY_MS) {
                    lastResume = now
                    saveResume(hash)
                }
                if (snap.finished) break
                delay(POLL_MS)
            }
            saveResume(hash)
            publish(hash)
            finished = true
        } finally {
            withContext(NonCancellable) {
                if (!finished) {
                    saveResume(hash)
                    backend.remove(hash, deleteFiles = false)
                }
            }
        }
        val after = dao.get(id)
        val mode = settings.current().seedMode
        if (after != null && mode != SeedRules.OFF && after.status == "COMPLETED") {
            // The torrent stays in the session and seeds (the engine starts a seeder for it).
            withContext(NonCancellable) { dao.startSeeding(id, env.clock()) }
        } else {
            withContext(NonCancellable) {
                backend.remove(hash, deleteFiles = true)
                env.storage.deleteScratch(id)
            }
        }
    }

    private data class Applied(val sequential: Boolean, val mask: String?)

    private suspend fun saveResume(hash: String) {
        val bytes = backend.saveResume(hash) ?: return
        runCatching { dir.mkdirs(); resumeFile.writeBytes(bytes) }
    }

    /** Copies the wanted files into the target folder: one file straight in, a folder torrent into a folder of its name. */
    private suspend fun publish(hash: String) {
        val job = dao.get(id) ?: return
        val snap = backend.snapshot(hash) ?: throw IOException("The torrent went away")
        val wanted = StoredFiles.wanted(job.fileMask, snap.files.size)
        val taken = env.storage.existingNames(job.folderUri).toMutableSet()
        // Padding files are listed but never written: not part of what the user gets.
        val real = snap.files.filter { !it.isPad }
        val single = real.size == 1 && !real[0].path.contains('/')
        var singleName: String? = null
        var bestUri: String? = null
        var bestSize = -1L
        var total = 0L
        for ((i, f) in snap.files.withIndex()) {
            if (!wanted[i] || f.isPad) continue
            val file = File(data, f.path)
            if (!file.isFile) throw IOException(TorrentText.NO_PEERS_FILE)
            // The library writes the last pieces a moment after it reports the torrent finished: never publish a short file.
            var waits = 0
            while (file.length() < f.size && waits++ < 50) delay(200)
            if (file.length() < f.size) throw IOException(TorrentText.NO_PEERS_FILE)
            val uri = if (single) {
                val name = uniqueName(fileNameFor(f.path, f.path), taken)
                taken += name
                singleName = name
                env.storage.publish(job.folderUri, name, file)
            } else {
                env.storage.publishIn(job.folderUri, f.path.substringBeforeLast('/', ""), f.path.substringAfterLast('/'), file)
            }
            total += f.size
            if (f.size > bestSize) { bestSize = f.size; bestUri = uri }
        }
        val uri = bestUri ?: throw IOException(TorrentText.NO_PEERS_FILE)
        withContext(NonCancellable) {
            singleName?.let { dao.rename(id, it) }
            dao.complete(id, uri, total, env.clock())
        }
    }

    /** Keeps a finished torrent uploading until [SeedRules] says stop; a cancel (paused, off Wi-Fi, app stopped) keeps it for later. */
    suspend fun seed() {
        val job = dao.get(id) ?: return
        if (!job.seeding) return
        val total = job.total ?: 0L
        var hash: String? = null
        var stopped = false
        try {
            hash = backend.add(
                source(job.url), data, resumeFile.takeIf { it.exists() }?.readBytes(), false,
                StoredFiles.wanted(job.fileMask, StoredFiles.decode(job.torrentFiles).size).takeIf { job.torrentFiles != null },
            )
            var lastResume = env.clock()
            while (true) {
                val snap = backend.snapshot(hash) ?: break
                val now = env.clock()
                dao.saveSeedProgress(id, snap.peers, snap.uploaded)
                env.onSpeed(id, snap.upRate)
                val fresh = dao.get(id) ?: return
                if (!fresh.seeding) { stopped = true; break }
                val mode = settings.current().seedMode
                if (SeedRules.shouldStop(mode, snap.uploaded, total, fresh.seedStartedAt ?: now, now)) { stopped = true; break }
                if (now - lastResume >= RESUME_EVERY_MS) { lastResume = now; saveResume(hash) }
                delay(SEED_POLL_MS)
            }
            stopped = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            stopped = true // the data is gone or the library failed: end seeding rather than loop
        } finally {
            withContext(NonCancellable) {
                env.onSpeed(id, 0)
                if (hash != null) {
                    if (stopped) backend.remove(hash, deleteFiles = true) else { saveResume(hash); backend.remove(hash, deleteFiles = false) }
                }
                if (stopped) {
                    dao.stopSeeding(id)
                    env.storage.deleteScratch(id)
                }
            }
        }
    }

    private companion object {
        const val POLL_MS = 1_000L
        const val SEED_POLL_MS = 2_000L
        const val RESUME_EVERY_MS = 30_000L
        const val MAX_TORRENT_FILE = 8L * 1024 * 1024
    }
}
