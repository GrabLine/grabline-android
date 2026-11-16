package dev.grabline.app.engine

import dev.grabline.app.engine.video.VideoDownload
import dev.grabline.app.engine.video.VideoEngine
import dev.grabline.app.engine.video.VideoException
import dev.grabline.app.engine.video.VideoFailure
import dev.grabline.app.engine.video.VideoPresets
import dev.grabline.app.engine.video.VideoProgress
import dev.grabline.app.engine.video.VideoText
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs one video job through yt-dlp + FFmpeg (SPEC rules 6, 7, 9, 10 apply to its scratch folder and output).
 * Pausing or cancelling cancels this coroutine, which kills the yt-dlp process; the engine's cancel then removes
 * the scratch folder, including yt-dlp's .part files (rule 10). Pausing keeps them so yt-dlp can resume.
 */
internal class VideoRunner(
    private val env: Env,
    private val video: VideoEngine,
    private val id: Long,
    private val cfg: EngineConfig,
) {
    private val dao get() = env.dao

    suspend fun run() {
        try {
            attempt(updatedOnce = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FolderUnavailableException) {
            // The download is intact; hold it until the user picks another folder.
            withContext(NonCancellable) { dao.moveIf(id, listOf("DOWNLOADING"), "PAUSED", EngineText.FOLDER, null) }
        } catch (e: VideoException) {
            val keep = e.failure == VideoFailure.CONNECTION || e.failure == VideoFailure.TIMEOUT
            if (!keep) env.storage.deleteScratch(id)
            fail(messageFor(e.failure))
        } catch (e: IOException) {
            fail(if (e.message.orEmpty().contains("ENOSPC") || e.message.orEmpty().contains("No space left", true)) EngineText.OUT_OF_SPACE else EngineText.UNEXPECTED)
        } catch (e: Throwable) {
            fail(VideoText.UNEXPECTED)
        } finally {
            env.onSpeed(id, 0)
        }
    }

    private fun messageFor(f: VideoFailure) =
        if (f == VideoFailure.SITE_CHANGED && cfg.updateYtdlp == "manual") VideoText.SITE_CHANGED_MANUAL else VideoText.forFailure(f)

    private suspend fun attempt(updatedOnce: Boolean) {
        val job = dao.get(id) ?: return
        // Rule 8, as far as it can be known before yt-dlp has chosen streams.
        val free = env.storage.freeBytes(job.folderUri)
        if (free < MIN_FREE_BYTES) throw VideoException(VideoFailure.NO_SPACE, EngineText.needsSpace(MIN_FREE_BYTES, free))

        val dir = env.storage.scratchDir(id).also { it.mkdirs() }
        val quality = when (val q = job.quality) {
            null -> VideoPresets.BEST.wire
            "audio" -> cfg.audioFormat
            else -> q
        }
        val request = VideoDownload(
            url = job.url,
            quality = quality,
            dir = dir,
            processId = "job-$id",
            retries = cfg.autoRetry,
            formatSpec = job.formatSpec,
            subtitleLang = job.subLang,
            clipStartSec = job.clipStartSec,
            clipEndSec = job.clipEndSec,
        )
        val files = try {
            download(request)
        } catch (e: VideoException) {
            // "Automatic": update yt-dlp once and try again; "ask": the Details screen offers the update.
            if (e.failure == VideoFailure.SITE_CHANGED && cfg.updateYtdlp == "auto" && !updatedOnce) {
                video.update()
                return attempt(updatedOnce = true)
            }
            throw e
        }
        publish(files, job.folderUri)
        // Rule 9 is satisfied by FFmpeg/yt-dlp finishing the file before it leaves its .part name; the copy is size-checked by storage.
        env.storage.deleteScratch(id)
    }

    /** Saves progress at most every 500 ms; the callback comes from yt-dlp's reader thread. */
    private suspend fun download(request: VideoDownload): List<File> = coroutineScope {
        val updates = Channel<VideoProgress>(Channel.CONFLATED)
        val saver = launch {
            for (p in updates) {
                dao.saveVideoProgress(id, p.downloaded, p.total)
                env.onSpeed(id, p.speedBytesPerSecond)
                delay(CHECKPOINT_MS)
            }
        }
        try {
            video.download(request) { updates.trySend(it) }
        } finally {
            updates.close()
            saver.cancel()
        }
    }

    private suspend fun publish(files: List<File>, folderUri: String?) {
        val taken = env.storage.existingNames(folderUri).toMutableSet()
        var first: String? = null
        var firstSize = 0L
        var checksum: String? = null
        for (file in files) {
            val name = uniqueName(fileNameFor(file.name, file.name), taken)
            taken += name
            val uri = env.storage.publish(folderUri, name, file)
            if (first == null) {
                first = uri
                firstSize = file.length()
                checksum = dao.get(id)?.expectedSha256?.let { runCatching { Checksum.verify(file, it) }.getOrNull() }
                dao.rename(id, name)
            }
        }
        val uri = first ?: throw VideoException(VideoFailure.UNEXPECTED, VideoText.UNEXPECTED)
        withContext(NonCancellable) {
            if (dao.complete(id, uri, firstSize, env.clock()) > 0 && checksum != null) {
                dao.setChecksum(id, checksum, Checksum.noticeFor(checksum).orEmpty())
            }
        }
    }

    private suspend fun fail(text: String) {
        withContext(NonCancellable) { dao.moveIf(id, listOf("DOWNLOADING"), "FAILED", null, text) }
    }

    private companion object {
        const val CHECKPOINT_MS = 500L
        const val MIN_FREE_BYTES = 50L shl 20
    }
}
