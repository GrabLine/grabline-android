package dev.grabline.app.engine.cloud

import dev.grabline.app.engine.Checksum
import dev.grabline.app.engine.EngineConfig
import dev.grabline.app.engine.EngineText
import dev.grabline.app.engine.Env
import dev.grabline.app.engine.FolderUnavailableException
import dev.grabline.app.engine.fileNameFor
import dev.grabline.app.engine.uniqueName
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Turns an exception into the plain-words message a user sees. Nothing secret is ever put in it. */
object CloudErrors {
    fun text(e: Throwable): String = when {
        e is CloudException -> e.message ?: EngineText.UNEXPECTED
        e is SSLException -> CloudText.TLS
        e is UnknownHostException || e is ConnectException || e is NoRouteToHostException || e is SocketTimeoutException -> CloudText.UNREACHABLE
        e is IOException && (e.message.orEmpty().contains("ENOSPC") || e.message.orEmpty().contains("No space left", ignoreCase = true)) -> EngineText.OUT_OF_SPACE
        e is IOException -> EngineText.CONNECTION
        else -> EngineText.UNEXPECTED
    }

    /** Worth trying again later: the network dropped, as opposed to a refusal that a retry cannot fix. */
    fun isTransient(e: Throwable): Boolean = e is IOException && e !is CloudException && e !is SSLException &&
        e !is UnknownHostException && !e.message.orEmpty().contains("ENOSPC") && !e.message.orEmpty().contains("No space left", ignoreCase = true)
}

private class NeedsSpace(text: String) : IOException(text)

/**
 * Runs one server download (kind "cloud"): connect, learn the size and identity of the file, resume the partial copy in
 * app-private scratch when it is still the same file, copy, check the size, then publish under a name that never
 * overwrites an existing file. Pause and cancel cancel this coroutine; closing the connection frees the blocked read.
 */
internal class CloudRunner(
    private val env: Env,
    private val backend: CloudBackend,
    private val id: Long,
    private val cfg: EngineConfig,
) {
    private val dao get() = env.dao
    private val budget = if (cfg.autoRetry < 0) Int.MAX_VALUE else cfg.autoRetry

    suspend fun run() {
        var failures = 0
        try {
            while (true) {
                val before = scratchLength()
                try {
                    attempt()
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: FolderUnavailableException) {
                    withContext(NonCancellable) { dao.moveIf(id, listOf("DOWNLOADING"), "PAUSED", EngineText.FOLDER, null) }
                    return
                } catch (e: Throwable) {
                    coroutineContext.ensureActive() // a pause or cancel closed the connection on purpose
                    if (e is NeedsSpace) return fail(e.message.orEmpty())
                    if (!CloudErrors.isTransient(e)) return fail(CloudErrors.text(e))
                    if (scratchLength() > before) failures = 0
                    if (++failures > budget) return fail(CloudErrors.text(e))
                    delay(env.backoffBaseMs * (1L shl (failures - 1).coerceAtMost(5)))
                }
            }
        } finally {
            env.onSpeed(id, 0)
        }
    }

    private fun scratchLength(): Long = env.storage.scratchFile(id).let { if (it.exists()) it.length() else 0L }

    private suspend fun fail(text: String) {
        withContext(NonCancellable) { dao.moveIf(id, listOf("DOWNLOADING"), "FAILED", null, text) }
    }

    private suspend fun attempt() {
        val job = dao.get(id) ?: return
        val connection = backend.create(backend.resolve(job.url, job.cloudSecret))
        coroutineScope {
            // Blocking reads cannot be cancelled; closing the connection from here is what stops them.
            val closer = launch(Dispatchers.IO) {
                try { awaitCancellation() } finally { connection.close() }
            }
            try {
                transfer(job.folderUri, job.name, job.etag, connection)
            } finally {
                closer.cancel()
                connection.close()
            }
        }
    }

    private suspend fun transfer(folderUri: String?, jobName: String, savedIdentity: String?, conn: RemoteConnection) {
        val scratch = env.storage.scratchFile(id)
        conn.connect()
        val stat = conn.stat()
        var have = if (scratch.exists()) scratch.length() else 0L
        if (have > 0) {
            // A partial copy is only the head of the file it started from: another file, or a shorter one, starts again.
            val changed = savedIdentity != null && stat.identity != null && savedIdentity != stat.identity
            val tooBig = stat.size != null && have > stat.size
            if (changed || tooBig) { scratch.delete(); have = 0 }
        }
        if (stat.size != null && stat.size > 0 && have == stat.size) {
            return publish(folderUri, jobName, scratch, have)
        }
        stat.size?.let { size ->
            val free = env.storage.freeBytes(folderUri)
            if (size - have > free) throw NeedsSpace(EngineText.needsSpace(size - have, free))
        }
        var stream = conn.open(have, savedIdentity)
        if (have > 0 && stream.identity != null && savedIdentity != null && stream.identity != savedIdentity) {
            stream.input.close()
            scratch.delete()
            have = 0
            stream = conn.open(0, null)
        }
        if (stream.startOffset == 0L && have > 0) {
            // The server could not resume (it sent the whole file): never append that to the old head.
            scratch.delete()
            have = 0
        } else if (stream.startOffset != have) {
            stream.input.close()
            throw IOException("wrong start ${stream.startOffset} for $have")
        }
        val identity = stat.identity ?: stream.identity ?: savedIdentity
        val total = stat.size ?: stream.total
        withContext(NonCancellable) { dao.saveCloudProgress(id, have, total, identity) }
        var pos = have
        if (total == null || pos != total) {
            var lastAt = env.clock()
            var lastBytes = pos
            val buffer = ByteArray(BUFFER)
            try {
                FileOutputStream(scratch, have > 0).use { out ->
                    stream.input.use { input ->
                        try {
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                env.bucket.acquire(n)
                                out.write(buffer, 0, n)
                                pos += n
                                coroutineContext.ensureActive()
                                val now = env.clock()
                                if (now - lastAt >= SAVE_MS) {
                                    env.onSpeed(id, (pos - lastBytes) * 1000 / (now - lastAt).coerceAtLeast(1))
                                    lastAt = now
                                    lastBytes = pos
                                    dao.saveCloudProgress(id, pos, total, identity)
                                }
                            }
                        } catch (e: Throwable) {
                            conn.close() // so closing the stream does not wait on a dead connection
                            throw e
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) { dao.saveCloudProgress(id, scratchLength(), total, identity) }
            }
        }
        if (total != null && pos != total) throw IOException(if (pos < total) CloudText.DROPPED else CloudText.SHORT)
        if (!scratch.exists()) scratch.createNewFile() // an empty file
        publish(folderUri, jobName, scratch, pos)
    }

    private suspend fun publish(folderUri: String?, jobName: String, scratch: File, size: Long) {
        // Never overwrite a file that is already there: save beside it under "name (1)".
        val name = uniqueName(fileNameFor(jobName, jobName), env.storage.existingNames(folderUri))
        val checksum = dao.get(id)?.expectedSha256?.let { runCatching { Checksum.verify(scratch, it) }.getOrNull() }
        val uri = env.storage.publish(folderUri, name, scratch)
        withContext(NonCancellable) {
            dao.rename(id, name)
            if (dao.complete(id, uri, size, env.clock()) > 0 && checksum != null) {
                dao.setChecksum(id, checksum, Checksum.noticeFor(checksum).orEmpty())
            }
        }
        scratch.delete()
    }

    private companion object {
        const val SAVE_MS = 500L
        const val BUFFER = 64 * 1024
    }
}
