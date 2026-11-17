package dev.grabline.app.engine.video

import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

enum class QualityKind { VIDEO, AUDIO }

/** One row of the quality picker. [wire] is the lower-case label shared with the PC protocol (best, 1080p, mp3). */
data class QualityPreset(
    val wire: String,
    val kind: QualityKind,
    val formatSpec: String,
    val audioFormat: String? = null,
    val height: Int? = null,
)

/** The same presets and yt-dlp format selectors as the desktop (app/engines/smart.py). */
object VideoPresets {
    val TIERS = listOf(2160, 1440, 1080, 720, 480, 360)

    /** A tier selector that never dead-ends: best at or below the tier, else the best available. */
    fun tierSelector(tier: Int) = "bv*[height<=$tier]+ba/b[height<=$tier]/bv*+ba/b"

    val BEST = QualityPreset("best", QualityKind.VIDEO, "bv*+ba/b")
    val MP3 = QualityPreset("mp3", QualityKind.AUDIO, "ba/b", audioFormat = "mp3")
    val M4A = QualityPreset("m4a", QualityKind.AUDIO, "ba[ext=m4a]/ba/b", audioFormat = "m4a")
    val FLAC = QualityPreset("flac", QualityKind.AUDIO, "ba/b", audioFormat = "flac")

    val ALL: List<QualityPreset> =
        listOf(BEST) + TIERS.map { QualityPreset("${it}p", QualityKind.VIDEO, tierSelector(it), height = it) } +
            listOf(MP3, M4A, FLAC)

    /** Wire values in picker order. */
    val WIRE: List<String> = ALL.map { it.wire }

    /** The preset for a wire value or label, case-insensitive. "audio" means MP3; anything unknown means Best. */
    fun forWire(wire: String?): QualityPreset {
        val w = wire?.trim()?.lowercase(Locale.ROOT)
        if (w == "audio") return MP3
        return ALL.firstOrNull { it.wire == w } ?: BEST
    }
}

data class QualityOption(val wire: String, val sizeBytes: Long?)

/** One raw yt-dlp format, for "More formats". [vcodec]/[acodec] are yt-dlp's codec strings ("avc1.640028"); null when absent. */
data class VideoFormat(
    val id: String,
    val ext: String?,
    val height: Int?,
    val width: Int? = null,
    val fps: Double? = null,
    val vcodec: String? = null,
    val acodec: String? = null,
    val sizeBytes: Long? = null,
)

/** A subtitle track the site offers; [auto] marks machine-made captions. */
data class SubtitleTrack(val lang: String, val name: String?, val auto: Boolean = false)

data class VideoAnalysis(
    val title: String?,
    val durationSeconds: Long?,
    val uploader: String?,
    /** In picker order; always starts with Best. */
    val qualities: List<QualityOption>,
    val isPlaylist: Boolean = false,
    val playlistCount: Int? = null,
    /** Every usable video or audio format, as yt-dlp lists them. Empty for playlists. */
    val formats: List<VideoFormat> = emptyList(),
    val subtitles: List<SubtitleTrack> = emptyList(),
)

/** Why a video job or analysis failed. */
enum class VideoFailure { DRM, NO_MEDIA, SITE_CHANGED, REFUSED, NOT_FOUND, NO_SPACE, CONNECTION, TIMEOUT, UNEXPECTED }

class VideoException(val failure: VideoFailure, message: String, cause: Throwable? = null) : Exception(message, cause)

data class VideoDownload(
    val url: String,
    val quality: String,
    val dir: java.io.File,
    val processId: String,
    /** yt-dlp retries per request; -1 = unlimited. */
    val retries: Int,
    /** A format selector chosen under "More formats"; replaces the preset's selector. */
    val formatSpec: String? = null,
    /** Subtitle language to fetch and embed. */
    val subtitleLang: String? = null,
    /** Keep only this part of the video (seconds); either end may be open. */
    val clipStartSec: Long? = null,
    val clipEndSec: Long? = null,
)

/** Whole-job progress. [total] is an estimate while yt-dlp is still choosing streams. */
data class VideoProgress(val downloaded: Long, val total: Long?, val speedBytesPerSecond: Long, val etaSeconds: Long?)

sealed interface EngineUpdateState {
    data object Idle : EngineUpdateState
    data object Running : EngineUpdateState
    data class Done(val version: String?, val changed: Boolean) : EngineUpdateState
    data class Failed(val message: String) : EngineUpdateState
}

/** DRM services refused up front (mirror of _DRM_SERVICES in the desktop's app/core/resolver.py). */
object DrmGuard {
    private val services: List<Pair<Regex, String>> = listOf(
        """(^|\.)netflix\.com$""" to "Netflix",
        """(^|\.)primevideo\.com$""" to "Prime Video",
        """(^|\.)disneyplus\.com$""" to "Disney+",
        """(^|\.)max\.com$""" to "Max",
        """(^|\.)hulu\.com$""" to "Hulu",
        """(^|\.)peacocktv\.com$""" to "Peacock",
        """(^|\.)paramountplus\.com$""" to "Paramount+",
        """(^|\.)crunchyroll\.com$""" to "Crunchyroll",
        """(^|\.)spotify\.com$""" to "Spotify",
        """(^|\.)music\.apple\.com$""" to "Apple Music",
        """(^|\.)tidal\.com$""" to "TIDAL",
        """(^|\.)deezer\.com$""" to "Deezer",
        """^music\.amazon\.""" to "Amazon Music",
    ).map { Regex(it.first) to it.second }

    /** Spotify podcasts are not DRM-protected. */
    private val spotifyPodcast = Regex("""^/(episode|show)/""")

    /** The plain-words refusal for a DRM service, or null when the link is fine. */
    fun refusal(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        for ((pattern, service) in services) {
            if (pattern.containsMatchIn(host)) {
                if (service == "Spotify" && spotifyPodcast.containsMatchIn(uri.rawPath.orEmpty())) return null
                return "$service protects its media with DRM. GrabLine can't download it"
            }
        }
        return null
    }
}

/** Plain-words messages for the video engine (SPEC "Error states and edge cases"). */
object VideoText {
    const val DRM = "This video is protected by DRM. GrabLine can't download it"
    const val NO_MEDIA = "This is a web page, not a file"
    const val SITE_CHANGED = "This site changed. Update the video engine?"
    const val SITE_CHANGED_MANUAL = "This site changed. Update the video engine in About, then try again"
    const val REFUSED = "The site refused this download. Open it in your browser and try again from there"
    const val NOT_FOUND = "The site says this video isn't there any more"
    const val NO_SPACE = "There is no space left on this phone. Free some up and try again"
    const val CONNECTION = "The connection kept dropping. Check your network and try again"
    const val TIMEOUT = "The site took too long to answer. Try again"
    const val UNEXPECTED = "Something went wrong with this video. Try again"
    const val ENGINE_START = "The video engine couldn't start. Restart GrabLine and try again"
    const val UPDATE_FAILED = "Couldn't update the video engine. Check your connection and try again"

    fun forFailure(f: VideoFailure) = when (f) {
        VideoFailure.DRM -> DRM
        VideoFailure.NO_MEDIA -> NO_MEDIA
        VideoFailure.SITE_CHANGED -> SITE_CHANGED
        VideoFailure.REFUSED -> REFUSED
        VideoFailure.NOT_FOUND -> NOT_FOUND
        VideoFailure.NO_SPACE -> NO_SPACE
        VideoFailure.CONNECTION -> CONNECTION
        VideoFailure.TIMEOUT -> TIMEOUT
        VideoFailure.UNEXPECTED -> UNEXPECTED
    }
}

/** Turns yt-dlp's error output into a [VideoFailure]. Order matters: the most specific causes first. */
object VideoErrors {
    fun classify(output: String?): VideoFailure {
        val t = output.orEmpty().lowercase(Locale.ROOT)
        return when {
            "drm" in t && ("protected" in t || "has drm" in t || "drm media" in t) -> VideoFailure.DRM
            "unsupported url" in t -> VideoFailure.NO_MEDIA
            "no space left" in t || "enospc" in t -> VideoFailure.NO_SPACE
            "http error 404" in t || "http error 410" in t || "video unavailable" in t || "has been removed" in t ||
                "does not exist" in t -> VideoFailure.NOT_FOUND
            "http error 403" in t || "http error 401" in t || "sign in" in t || "log in" in t || "login required" in t ||
                "private video" in t || "members-only" in t || "age-restricted" in t || "confirm your age" in t ->
                VideoFailure.REFUSED
            "unable to extract" in t || "failed to extract" in t || "nsig extraction failed" in t ||
                "latest version" in t || "update yt-dlp" in t || "requested format is not available" in t ||
                "unable to find" in t -> VideoFailure.SITE_CHANGED
            "timed out" in t || "unable to download" in t || "connection" in t || "network is unreachable" in t ||
                "temporary failure" in t || "name or service not known" in t || "incomplete read" in t ->
                VideoFailure.CONNECTION
            else -> VideoFailure.UNEXPECTED
        }
    }
}

/** yt-dlp arguments, kept pure so they can be unit tested. */
object VideoArgs {
    /** watch?v=ID&list=... is one video the user picked, not the whole playlist. */
    fun singleVideoOfPlaylist(url: String): Boolean {
        val q = runCatching { URI(url.trim()).rawQuery }.getOrNull().orEmpty()
        val keys = q.split('&').map { it.substringBefore('=') }
        return "v" in keys && "list" in keys
    }

    fun analyze(url: String): List<String> = buildList {
        add("--dump-single-json"); add("--flat-playlist"); add("--skip-download"); add("--no-warnings")
        add("--socket-timeout"); add("20")
        if (singleVideoOfPlaylist(url)) add("--no-playlist")
    }

    /**
     * [preferCodec] is the best video codec this phone decodes in hardware ("vp9", "h264"), or null
     * for no limit. yt-dlp otherwise picks AV1 first, which a phone without an AV1 decoder plays in
     * software and stutters on. Resolution still comes first, so 1440p and 2160p keep working.
     */
    fun download(request: VideoDownload, audioBitrate: String = "192K", preferCodec: String? = null): List<String> {
        val preset = VideoPresets.forWire(request.quality)
        val retries = if (request.retries < 0) "infinite" else request.retries.toString()
        return buildList {
            add("-f"); add(request.formatSpec?.takeIf { it.isNotBlank() } ?: preset.formatSpec)
            add("-o"); add("${request.dir.path.replace('\\', '/')}/%(title).120B.%(ext)s")
            add("--windows-filenames")
            add("--newline")
            add("--no-mtime")
            add("--socket-timeout"); add("30")
            add("--retries"); add(retries)
            add("--fragment-retries"); add(retries)
            // HLS and DASH: fetch fragments in parallel, then FFmpeg remuxes them.
            add("--concurrent-fragments"); add("4")
            if (singleVideoOfPlaylist(request.url)) add("--no-playlist")
            if (preset.kind == QualityKind.AUDIO) {
                add("-x"); add("--audio-format"); add(preset.audioFormat!!)
                add("--audio-quality"); add(if (preset.audioFormat == "mp3") audioBitrate else "0")
            } else {
                add("--merge-output-format"); add("mp4")
                preferCodec?.let { add("-S"); add("res,fps,vcodec:$it") }
            }
            request.subtitleLang?.takeIf { it.isNotBlank() }?.let { lang ->
                add("--write-subs"); add("--write-auto-subs"); add("--sub-langs"); add(lang); add("--embed-subs")
            }
            clipSection(request.clipStartSec, request.clipEndSec)?.let { add("--download-sections"); add(it); add("--force-keyframes-at-cuts") }
            // Title, artist and the other tags go into the file (QA-34).
            add("--embed-metadata"); add("--add-metadata")
        }
    }

    /** yt-dlp's `--download-sections` value for a clip ("*60-150", "*60-inf", "*0-150"), or null for the whole video. */
    fun clipSection(startSec: Long?, endSec: Long?): String? {
        if (startSec == null && endSec == null) return null
        return "*${startSec ?: 0}-${endSec?.toString() ?: "inf"}"
    }
}

/** Reads yt-dlp's [download] lines and folds several streams (video, then audio) into one progress. */
class ProgressTracker {
    private var base = 0L
    private var lastPercent = -1f
    private var streamSize = 0L

    fun update(percent: Float, etaSeconds: Long, line: String): VideoProgress {
        if (lastPercent >= 0 && percent < lastPercent - 30f) base += streamSize // the next stream started
        lastPercent = percent
        parseSize(line)?.let { streamSize = it }
        val total = if (streamSize > 0) base + streamSize else null
        val done = base + (streamSize * (percent.coerceIn(0f, 100f) / 100.0)).toLong()
        return VideoProgress(done, total, parseSpeed(line) ?: 0L, etaSeconds.takeIf { it >= 0 })
    }

    companion object {
        private val SIZE = Regex("""of\s+~?\s*([\d.]+)\s*([KMGT]?i?B)""")
        private val SPEED = Regex("""at\s+~?\s*([\d.]+)\s*([KMGT]?i?B)/s""")

        private fun bytes(n: String, unit: String): Long? {
            val v = n.toDoubleOrNull() ?: return null
            val mult = when (unit.firstOrNull()?.uppercaseChar()) {
                'K' -> 1024.0
                'M' -> 1024.0 * 1024
                'G' -> 1024.0 * 1024 * 1024
                'T' -> 1024.0 * 1024 * 1024 * 1024
                else -> 1.0
            }
            return (v * mult).toLong()
        }

        fun parseSize(line: String): Long? = SIZE.find(line)?.let { bytes(it.groupValues[1], it.groupValues[2]) }
        fun parseSpeed(line: String): Long? = SPEED.find(line)?.let { bytes(it.groupValues[1], it.groupValues[2]) }
    }
}

/** Builds the quality list and size estimates from yt-dlp --dump-single-json (mirrors curate_formats on the desktop). */
object AnalysisParser {
    private val VIDEO_EXTS = setOf("mp4", "webm", "mkv", "mov", "flv", "ts", "m4v", "avi", "3gp", "ogv")
    private val json = Json { ignoreUnknownKeys = true }

    /** 1088 -> 1080, 2176 -> 2160: formats are rarely exactly on the ladder. */
    fun snapToTier(height: Int): Int? = VideoPresets.TIERS.firstOrNull { kotlin.math.abs(height - it) <= it * 0.08 }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    private fun JsonObject.isNone(key: String): Boolean {
        val v = str(key)
        return v == null || v == "none"
    }
    private fun JsonObject.size(): Long? = (num("filesize") ?: num("filesize_approx"))?.takeIf { it > 0 }?.toLong()

    fun parse(text: String): VideoAnalysis {
        val root = json.parseToJsonElement(text).jsonObject
        val title = root.str("title")
        val duration = root.num("duration")?.toLong()
        val uploader = root.str("uploader") ?: root.str("channel")
        val entries = root["entries"] as? JsonArray
        if (root.str("_type") == "playlist" || entries != null) {
            val generic = listOf("best", "1080p", "720p", "480p", "mp3", "m4a", "flac").map { QualityOption(it, null) }
            return VideoAnalysis(title, duration, uploader, generic, isPlaylist = true, playlistCount = entries?.size)
        }
        val formats = (root["formats"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val raw = rawFormats(formats)
        val subtitles = subtitleTracks(root)
        val video = formats.filter { f ->
            (!f.isNone("vcodec") && f.str("vcodec") != "images") ||
                ((f["vcodec"] == null || f["vcodec"] is JsonNull) && f.num("height") != null && f.str("ext") in VIDEO_EXTS)
        }
        val audio = formats.filter { f -> !f.isNone("acodec") && f.isNone("vcodec") && f !in video }
        val audioSize = audio.maxByOrNull { it.num("abr") ?: 0.0 }?.size()

        fun tierEstimate(tier: Int): Long? {
            val candidates = video.filter { f -> f.num("height")?.toInt()?.let(::snapToTier) == tier }
            val best = candidates.maxWithOrNull(compareBy({ it.num("tbr") ?: 0.0 }, { it.size() ?: 0L })) ?: return null
            val vs = best.size() ?: return null
            return if (best.isNone("acodec") && audioSize != null) vs + audioSize else vs
        }

        val tiers = video.mapNotNull { f -> f.num("height")?.toInt()?.let(::snapToTier) }.distinct().sortedDescending()
        val options = mutableListOf<QualityOption>()
        options += QualityOption("best", tiers.firstOrNull()?.let(::tierEstimate))
        tiers.forEach { options += QualityOption("${it}p", tierEstimate(it)) }
        // Without a format list (some HLS pages) the audio presets still work: yt-dlp resolves them at download time.
        options += QualityOption("mp3", audioSize)
        options += QualityOption("m4a", audioSize)
        options += QualityOption("flac", null)
        return VideoAnalysis(title, duration, uploader, options, formats = raw, subtitles = subtitles)
    }

    /** Playable formats only: storyboards and image sheets are not downloads. */
    private fun rawFormats(formats: List<JsonObject>): List<VideoFormat> = formats.mapNotNull { f ->
        val id = f.str("format_id") ?: return@mapNotNull null
        val ext = f.str("ext")
        if (f.str("vcodec") == "images" || ext == "mhtml" || f.str("format_note")?.contains("storyboard", true) == true) return@mapNotNull null
        val v = f.str("vcodec")?.takeIf { it != "none" }
        val a = f.str("acodec")?.takeIf { it != "none" }
        if (v == null && a == null && f.num("height") == null) return@mapNotNull null
        VideoFormat(id, ext, f.num("height")?.toInt(), f.num("width")?.toInt(), f.num("fps"), v, a, f.size())
    }

    private fun subtitleTracks(root: JsonObject): List<SubtitleTrack> {
        fun read(key: String, auto: Boolean): List<SubtitleTrack> = (root[key] as? JsonObject).orEmpty().map { (lang, tracks) ->
            val name = ((tracks as? JsonArray)?.firstOrNull() as? JsonObject)?.str("name")
            SubtitleTrack(lang, name, auto)
        }
        val manual = read("subtitles", false).filter { it.lang != "live_chat" }
        val have = manual.map { it.lang }.toSet()
        // Captions the site wrote come first; machine captions only for languages without one, and only the few common ones.
        val auto = read("automatic_captions", true).filter { it.lang !in have && it.lang in COMMON_AUTO }
        return manual.sortedBy { it.lang } + auto.sortedBy { it.lang }
    }

    private val COMMON_AUTO = setOf("en", "es", "fr", "de", "pt", "it", "ru", "ja", "ko", "zh", "hi", "ar", "tr")
}
