package dev.grabline.app.engine

import dev.grabline.app.remote.transfer.FetchClient
import dev.grabline.app.remote.transfer.FetchProgress
import dev.grabline.app.remote.transfer.FetchResult
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Plain-words messages of a copy from the PC (SPEC "Error states and edge cases"). */
object TransferText {
    const val HASH_MISMATCH = "The copy didn't match the file on your PC, so it was deleted. Try again"
    const val NOT_ON_PC = "That's no longer on your PC"
    const val REFUSED = "Your PC doesn't allow copying that"
    const val PC_OFFLINE = "Your PC is off or on another network"
}

private class NeedsSpace(text: String) : IOException(text)

/**
 * Runs one transfer job: copy the PC file into app-private scratch (resuming with the saved etag), check its
 * checksum, then publish it through the phone storage under a unique name. Pause and cancel cancel this coroutine;
 * the scratch file stays after a pause (so the copy resumes) and is removed by cancel (engine).
 */
internal class TransferRunner(private val env: Env, private val fetcher: FetchClient, private val id: Long) {
    private val dao get() = env.dao

    suspend fun run() {
        try {
            attempt()
        } catch (e: CancellationException) {
            throw e
        } catch (e: FolderUnavailableException) {
            withContext(NonCancellable) { dao.moveIf(id, listOf("DOWNLOADING"), "PAUSED", EngineText.FOLDER, null) }
        } catch (e: NeedsSpace) {
            fail(e.message.orEmpty())
        } catch (e: IOException) {
            fail(if (e.message.orEmpty().let { it.contains("ENOSPC") || it.contains("No space left", ignoreCase = true) }) EngineText.OUT_OF_SPACE else EngineText.UNEXPECTED)
        } catch (e: Throwable) {
            fail(EngineText.UNEXPECTED)
        } finally {
            env.onSpeed(id, 0)
        }
    }

    private suspend fun fail(text: String) {
        withContext(NonCancellable) { dao.moveIf(id, listOf("DOWNLOADING"), "FAILED", null, text) }
    }

    private suspend fun attempt() {
        val job = dao.get(id) ?: return
        val pcId = job.pcId
        val remoteJobId = job.remoteJobId
        if (pcId == null || remoteJobId == null) return fail(TransferText.NOT_ON_PC)
        val scratch = env.storage.scratchFile(id)
        val progress = object : FetchProgress {
            private var lastSave = 0L
            private var lastAt = env.clock()
            private var lastBytes = 0L
            private var etag: String? = job.etag
            private var size: Long? = job.total

            override suspend fun onStart(name: String, size: Long, etag: String?) {
                val have = if (scratch.exists()) scratch.length() else 0L
                val free = env.storage.freeBytes(job.folderUri)
                if (size - have > free) throw NeedsSpace(EngineText.needsSpace(size - have, free))
                this.size = size
                this.etag = etag
                lastBytes = have
                dao.saveTransfer(id, have, size, etag)
            }

            override suspend fun onBytes(onDisk: Long) {
                val now = env.clock()
                if (now - lastAt < SAVE_MS) return
                val bps = (onDisk - lastBytes) * 1000 / (now - lastAt).coerceAtLeast(1)
                lastAt = now
                lastBytes = onDisk
                lastSave = now
                env.onSpeed(id, bps)
                dao.saveTransfer(id, onDisk, size, etag)
            }
        }
        when (val result = fetcher.fetch(pcId, remoteJobId, scratch, job.etag, job.total, progress)) {
            is FetchResult.Complete -> publish(job.folderUri, job.name, scratch, result.size)
            FetchResult.HashMismatch -> {
                scratch.delete()
                withContext(NonCancellable) { dao.saveTransfer(id, 0, null, null) }
                fail(TransferText.HASH_MISMATCH)
            }
            FetchResult.NotFound -> { scratch.delete(); fail(TransferText.NOT_ON_PC) }
            is FetchResult.Refused -> { scratch.delete(); fail(TransferText.REFUSED) }
            FetchResult.Unreachable -> fail(TransferText.PC_OFFLINE) // the partial copy stays: Retry resumes it
        }
    }

    private suspend fun publish(folderUri: String?, jobName: String, scratch: java.io.File, size: Long) {
        val name = uniqueName(fileNameFor(jobName, jobName), env.storage.existingNames(folderUri))
        val uri = env.storage.publish(folderUri, name, scratch)
        withContext(NonCancellable) {
            dao.rename(id, name)
            dao.complete(id, uri, size, env.clock())
        }
        scratch.delete()
    }

    private companion object {
        const val SAVE_MS = 500L
    }
}