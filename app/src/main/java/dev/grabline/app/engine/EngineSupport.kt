package dev.grabline.app.engine

import dev.grabline.app.data.AppSettings
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Plain-words messages (SPEC "Error states and edge cases"). */
object EngineText {
    const val NO_RESUME = "This server can't resume; it will restart if interrupted"
    const val FILE_CHANGED = "The file changed on the server, so it started again"
    const val REFUSED = "The site refused this download. Open it in your browser and try again from there"
    const val COMPRESSED = "The server sent this file compressed, so it can't be saved safely"
    const val WRONG_RANGE = "The server sent the wrong part of the file, so the download was stopped to keep it safe"
    const val PUSHBACK = "The server keeps turning this download away. Try again later"
    const val CONNECTION = "The connection kept dropping. Check your network and try again"
    const val NOT_FOUND = "The server says this file isn't there any more"
    const val BAD_LINK = "That doesn't look like a web link"
    const val FOLDER = "Can't write to that folder. Choose another"
    const val OUT_OF_SPACE = "There is no space left on this phone. Free some up and try again"
    const val CHANGING = "The file kept changing on the server, so the download was stopped"
    const val UNEXPECTED = "Something went wrong saving this file. Try again"
    fun server(code: Int) = "The server answered with an error ($code). Try again later"
    fun needsSpace(need: Long, free: Long) = "Needs ${formatSize(need)}, ${formatSize(free)} free"
}

/** "2.1 GB", "900 MB", "12 KB". */
fun formatSize(bytes: Long): String {
    val b = bytes.coerceAtLeast(0)
    return when {
        b >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", b / (1024.0 * 1024 * 1024))
        b >= 1L shl 20 -> String.format(Locale.ROOT, "%.0f MB", b / (1024.0 * 1024))
        b >= 1L shl 10 -> String.format(Locale.ROOT, "%.0f KB", b / 1024.0)
        else -> "$b B"
    }
}

/** Settings the engine reads (SPEC "Settings", Phone downloads). */
data class EngineConfig(
    val connectionsPerFile: Int = 8,
    val downloadsAtOnce: Int = 3,
    val speedLimitKbps: Int = 0,
    val onlyWifi: Boolean = true,
    val onlyCharging: Boolean = false,
    /** Failures in a row with no progress before giving up; -1 = no limit. */
    val autoRetry: Int = 3,
    /** "ask", "auto" or "manual" (what to do when a site changed and yt-dlp is out of date). */
    val updateYtdlp: String = "ask",
    /** "mp3", "m4a" or "flac"; used when a quality of "audio" has no format of its own. */
    val audioFormat: String = "mp3",
)

fun interface EngineSettings {
    suspend fun current(): EngineConfig
}

class AppEngineSettings(private val s: AppSettings) : EngineSettings {
    override suspend fun current() = EngineConfig(
        connectionsPerFile = s.observe(AppSettings.CONNECTIONS_PER_FILE).first(),
        downloadsAtOnce = s.observe(AppSettings.DOWNLOADS_AT_ONCE).first(),
        speedLimitKbps = s.observe(AppSettings.SPEED_LIMIT_KBPS).first(),
        onlyWifi = s.observe(AppSettings.ONLY_WIFI).first(),
        onlyCharging = s.observe(AppSettings.ONLY_CHARGING).first(),
        autoRetry = s.observe(AppSettings.AUTO_RETRY).first(),
        updateYtdlp = s.observe(AppSettings.UPDATE_YTDLP).first(),
        audioFormat = s.observe(AppSettings.AUDIO_FORMAT).first(),
    )
}

/** Where bytes live before and after a download. Faked in tests. */
interface PhoneStorage {
    /** Temp file in app-private storage for a job. */
    fun scratchFile(jobId: Long): File
    /** Scratch folder of a video job (yt-dlp's .part files and fragments); [deleteScratch] removes it too. */
    fun scratchDir(jobId: Long): File = File(scratchFile(jobId).parentFile, "video-$jobId")
    fun deleteScratch(jobId: Long)
    fun freeBytes(folderUri: String?): Long
    /** Names already in the target folder (finished files). */
    suspend fun existingNames(folderUri: String?): Set<String>
    /** Copies the verified scratch file into the target folder and returns its content/file URI. */
    suspend fun publish(folderUri: String?, name: String, scratch: File): String
}

class FolderUnavailableException(cause: Throwable? = null) : IOException(EngineText.FOLDER, cause)

/** Shared global speed cap (KB/s, 0 = off). */
class TokenBucket(
    private val clock: () -> Long,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    @Volatile var bytesPerSecond: Long = 0
    private val mutex = Mutex()
    private var available = 0.0
    private var last = 0L

    suspend fun acquire(n: Int) {
        val rate = bytesPerSecond
        if (rate <= 0) return
        while (true) {
            val wait = mutex.withLock {
                val now = clock()
                if (last == 0L) { last = now; available = minOf(n.toDouble(), rate.toDouble()) }
                val cap = maxOf(rate.toDouble(), n.toDouble())
                available = minOf(cap, available + (now - last) * rate / 1000.0)
                last = now
                if (available >= n) { available -= n; 0L } else Math.ceil((n - available) * 1000.0 / rate).toLong().coerceAtLeast(1)
            }
            if (wait == 0L) return
            pause(wait)
        }
    }
}

internal data class ContentRange(val start: Long, val end: Long, val total: Long?)

/** Parses a Content-Range such as bytes 0-99 of 200 (total may be an asterisk); null for anything else. */
internal fun parseContentRange(h: String?): ContentRange? {
    val m = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE).find(h ?: return null) ?: return null
    val (a, b, t) = m.destructured
    val s = a.toLongOrNull() ?: return null
    val e = b.toLongOrNull() ?: return null
    if (e < s) return null
    return ContentRange(s, e, t.toLongOrNull())
}

/** "name (1).ext" style: first name not in [taken] (case-insensitive). */
fun uniqueName(name: String, taken: Set<String>): String {
    val lower = taken.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
    if (name.lowercase(Locale.ROOT) !in lower) return name
    val dot = name.lastIndexOf('.')
    val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    var n = 1
    while (true) {
        val candidate = "$stem ($n)$ext"
        if (candidate.lowercase(Locale.ROOT) !in lower) return candidate
        n++
    }
}

/** A safe single-segment file name from a title or URL. */
fun fileNameFor(title: String?, url: String): String {
    val raw = title?.takeIf { it.isNotBlank() } ?: run {
        val path = runCatching { java.net.URI(url).path }.getOrNull().orEmpty()
        val last = path.trimEnd('/').substringAfterLast('/')
        runCatching { java.net.URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") }.getOrDefault(last)
    }
    var s = raw.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim().trimStart('.').trim()
    if (s.length > 120) {
        val dot = s.lastIndexOf('.')
        val ext = if (dot > 0 && s.length - dot <= 10) s.substring(dot) else ""
        s = s.substring(0, 120 - ext.length) + ext
    }
    return s.ifBlank { "download" }
}
