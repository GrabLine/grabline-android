package dev.grabline.app.engine.video

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The phone's video engine: yt-dlp with bundled Python, and FFmpeg for merging, HLS/DASH remuxing and audio extraction
 * (SPEC "Phone download engine"). Implemented by [YoutubeDlVideoEngine]; the interface lets tests fake it.
 */
interface VideoEngine {
    /** Starts yt-dlp and FFmpeg once, off the main thread. Safe to call again. */
    suspend fun ensureReady()

    /** Title, duration, quality list with size estimates and the playlist flag. Throws [VideoException]. */
    suspend fun analyze(url: String): VideoAnalysis

    /** Downloads into [VideoDownload.dir]; returns the finished files. Cancelling the coroutine kills yt-dlp. Throws [VideoException]. */
    suspend fun download(request: VideoDownload, onProgress: (VideoProgress) -> Unit): List<File>

    /** The yt-dlp version in use (bundled, or the updated one), or null if the engine did not start. */
    suspend fun version(): String?

    val updateState: StateFlow<EngineUpdateState>

    /** Updates yt-dlp from its STABLE channel without a new APK; progress and failures arrive in [updateState]. */
    suspend fun update()
}

/** The thin layer over youtubedl-android, so the engine's own logic stays testable. */
interface YtDlpBackend {
    fun init()
    fun version(): String?
    /** Runs yt-dlp to completion; [onProgress] gets (percent, eta seconds, line). Failures throw [YtDlpFailed]. */
    fun run(url: String, args: List<String>, processId: String?, onProgress: ((Float, Long, String) -> Unit)?): String
    fun cancel(processId: String)
    /** Returns true when a newer yt-dlp was installed. */
    fun update(): Boolean
}

class YtDlpFailed(val output: String) : Exception(output)

class AndroidYtDlpBackend(context: Context) : YtDlpBackend {
    private val app = context.applicationContext
    private val ytdlp get() = YoutubeDL.getInstance()

    override fun init() {
        ytdlp.init(app)
        FFmpeg.getInstance().init(app)
    }

    override fun version(): String? =
        ytdlp.versionName(app)?.takeIf { it.isNotBlank() }
            ?: ytdlp.version(app)?.takeIf { it.isNotBlank() }
            ?: runCatching {
                // Ask the binary itself when no version was recorded.
                ytdlp.execute(YoutubeDLRequest(emptyList<String>()).addOption("--version")).out.trim().takeIf { it.isNotBlank() }
            }.getOrNull()

    override fun run(url: String, args: List<String>, processId: String?, onProgress: ((Float, Long, String) -> Unit)?): String {
        val request = YoutubeDLRequest(url).addCommands(args)
        try {
            val response = ytdlp.execute(request, processId) { percent, eta, line -> onProgress?.invoke(percent, eta, line) }
            return response.out
        } catch (e: YoutubeDL.CanceledException) {
            throw CancellationException("yt-dlp was cancelled")
        } catch (e: YoutubeDLException) {
            throw YtDlpFailed(e.message.orEmpty())
        }
    }

    override fun cancel(processId: String) {
        ytdlp.destroyProcessById(processId)
    }

    override fun update(): Boolean =
        ytdlp.updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE) == YoutubeDL.UpdateStatus.DONE
}

class YoutubeDlVideoEngine(
    private val backend: YtDlpBackend,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val analyzeTimeoutMs: Long = 120_000,
) : VideoEngine {
    constructor(context: Context) : this(AndroidYtDlpBackend(context))

    private val initMutex = Mutex()
    @Volatile private var started = false
    private val update = MutableStateFlow<EngineUpdateState>(EngineUpdateState.Idle)
    override val updateState: StateFlow<EngineUpdateState> = update

    override suspend fun ensureReady() {
        if (started) return
        initMutex.withLock {
            if (started) return
            try {
                withContext(io) { backend.init() }
                started = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw VideoException(VideoFailure.UNEXPECTED, VideoText.ENGINE_START, e)
            }
        }
    }

    override suspend fun version(): String? = try {
        ensureReady()
        withContext(io) { backend.version() }
    } catch (e: VideoException) {
        null
    }

    /** Runs the blocking yt-dlp call, killing the process if the coroutine is cancelled. */
    private suspend fun <T> blocking(processId: String?, block: () -> T): T = coroutineScope {
        val killer = launch(io) {
            try {
                awaitCancellation()
            } finally {
                if (processId != null) backend.cancel(processId)
            }
        }
        try {
            withContext(io) { block() }
        } finally {
            killer.cancel()
        }
    }

    override suspend fun analyze(url: String): VideoAnalysis {
        DrmGuard.refusal(url)?.let { throw VideoException(VideoFailure.DRM, it) }
        ensureReady()
        val processId = "analyze-${System.nanoTime()}"
        val json = try {
            withTimeout(analyzeTimeoutMs) { blocking(processId) { backend.run(url.trim(), VideoArgs.analyze(url), processId, null) } }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw VideoException(VideoFailure.TIMEOUT, VideoText.TIMEOUT, e)
        } catch (e: YtDlpFailed) {
            throw failure(e.output, e)
        }
        return try {
            AnalysisParser.parse(json)
        } catch (e: Exception) {
            throw VideoException(VideoFailure.UNEXPECTED, VideoText.UNEXPECTED, e)
        }
    }

    override suspend fun download(request: VideoDownload, onProgress: (VideoProgress) -> Unit): List<File> {
        DrmGuard.refusal(request.url)?.let { throw VideoException(VideoFailure.DRM, it) }
        ensureReady()
        request.dir.mkdirs()
        val tracker = ProgressTracker()
        try {
            blocking(request.processId) {
                backend.run(request.url.trim(), VideoArgs.download(request), request.processId) { pct, eta, line ->
                    onProgress(tracker.update(pct, eta, line))
                }
            }
        } catch (e: YtDlpFailed) {
            throw failure(e.output, e)
        }
        val finished = request.dir.listFiles().orEmpty()
            .filter { it.isFile && !isScratch(it.name) }
            .sortedBy { it.name }
        if (finished.isEmpty()) throw VideoException(VideoFailure.UNEXPECTED, VideoText.UNEXPECTED)
        return finished
    }

    override suspend fun update() {
        if (update.value == EngineUpdateState.Running) return
        update.value = EngineUpdateState.Running
        try {
            ensureReady()
            val changed = withContext(io) { backend.update() }
            update.value = EngineUpdateState.Done(withContext(io) { backend.version() }, changed)
        } catch (e: CancellationException) {
            update.value = EngineUpdateState.Idle
            throw e
        } catch (e: Throwable) {
            update.value = EngineUpdateState.Failed(VideoText.UPDATE_FAILED)
        }
    }

    private fun failure(output: String, cause: Throwable): VideoException {
        val kind = VideoErrors.classify(output)
        return VideoException(kind, VideoText.forFailure(kind), cause)
    }

    private fun isScratch(name: String) =
        name.endsWith(".part") || name.endsWith(".ytdl") || name.endsWith(".temp") || name.endsWith(".json") ||
            Regex("""\.f[\w-]+\.\w+$""").containsMatchIn(name)
}
