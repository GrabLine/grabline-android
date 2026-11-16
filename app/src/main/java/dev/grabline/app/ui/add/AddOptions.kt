package dev.grabline.app.ui.add

import dev.grabline.app.engine.video.SubtitleTrack
import dev.grabline.app.engine.video.VideoFormat
import dev.grabline.app.remote.protocol.QualityOption as PcQuality

/**
 * One line of "More formats": resolution, codec and size. [spec] is the yt-dlp selector a phone job uses;
 * [presetWire] is the quality chip a PC job uses (protocol 6 has no per-format choice).
 */
data class FormatRow(
    val id: String,
    /** Null for audio-only formats. */
    val height: Int?,
    val codec: String,
    val container: String,
    val sizeBytes: Long?,
    val audioOnly: Boolean,
    val spec: String? = null,
    val presetWire: String? = null,
)

/** Turns yt-dlp's raw format list (phone) or the PC's analysis rows into picker rows. Pure, so it is unit tested. */
object FormatMapping {
    private const val MAX_ROWS = 40

    /** "avc1.640028" -> "H.264", "mp4a.40.2" -> "AAC"; unknown codecs keep their prefix. */
    fun codecName(codec: String?): String {
        val c = codec?.trim()?.lowercase().orEmpty()
        if (c.isEmpty() || c == "none") return ""
        return when {
            c.startsWith("avc") || c.startsWith("h264") -> "H.264"
            c.startsWith("hev") || c.startsWith("hvc") || c.startsWith("h265") -> "H.265"
            c.startsWith("vp09") || c.startsWith("vp9") -> "VP9"
            c.startsWith("vp8") -> "VP8"
            c.startsWith("av01") || c.startsWith("av1") -> "AV1"
            c.startsWith("mp4a") || c.startsWith("aac") -> "AAC"
            c.startsWith("opus") -> "Opus"
            c.startsWith("vorbis") -> "Vorbis"
            c.startsWith("ac-3") || c.startsWith("ac3") -> "AC-3"
            c.startsWith("ec-3") || c.startsWith("eac3") -> "E-AC-3"
            c.startsWith("flac") -> "FLAC"
            c.startsWith("mp3") || c.startsWith("mp4a.69") -> "MP3"
            else -> c.substringBefore('.').uppercase()
        }
    }

    /** The selector for one format: a video-only stream gets the best audio added so the file has sound. */
    fun specFor(f: VideoFormat): String {
        val hasVideo = f.vcodec != null || (f.height != null && f.acodec == null)
        val hasAudio = f.acodec != null
        return if (hasVideo && !hasAudio) "${f.id}+ba/${f.id}" else f.id
    }

    fun fromVideo(formats: List<VideoFormat>): List<FormatRow> {
        val rows = formats.map { f ->
            val audioOnly = f.height == null && f.vcodec == null
            FormatRow(
                id = f.id,
                height = f.height?.takeIf { !audioOnly },
                codec = codecName(f.vcodec ?: f.acodec),
                container = f.ext.orEmpty(),
                sizeBytes = f.sizeBytes,
                audioOnly = audioOnly,
                spec = specFor(f),
            )
        }
        val video = rows.filter { !it.audioOnly }
            .sortedWith(compareByDescending<FormatRow> { it.height ?: 0 }.thenByDescending { it.sizeBytes ?: -1L })
        val audio = rows.filter { it.audioOnly }.sortedByDescending { it.sizeBytes ?: -1L }
        return (video + audio).take(MAX_ROWS)
    }

    /** The PC's analysis rows as a list; picking one chooses the matching quality chip. */
    fun fromPc(qualities: List<PcQuality>, validWires: Collection<String>): List<FormatRow> = qualities.mapIndexed { i, q ->
        val wire = q.label.lowercase()
        FormatRow(
            id = "pc$i",
            height = q.height,
            codec = "",
            container = q.ext.orEmpty(),
            sizeBytes = q.sizeBytes,
            audioOnly = q.kind.equals("audio", ignoreCase = true),
            presetWire = wire.takeIf { it in validWires },
        )
    }.take(MAX_ROWS)

    fun subtitleLabel(t: SubtitleTrack): String = (t.name?.takeIf { it.isNotBlank() } ?: t.lang) + if (t.auto) " (auto)" else ""
}

/** Clip start and end as typed: "90", "1:30" or "1:02:03". */
object ClipTime {
    /** Seconds, or null for blank text or something that is not a time. */
    fun parse(text: String?): Long? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        val parts = t.split(':')
        if (parts.size > 3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) || it.length > 6 }) return null
        val nums = parts.map { it.toLong() }
        if (nums.size > 1 && nums.drop(1).any { it >= 60 }) return null
        return nums.fold(0L) { acc, n -> acc * 60 + n }
    }

    /** Blank boxes are fine (whole video); a typed value must be a time and the end must come after the start. */
    fun isValid(start: String, end: String): Boolean {
        val s = parse(start)
        val e = parse(end)
        if (start.isNotBlank() && s == null) return false
        if (end.isNotBlank() && e == null) return false
        return s == null || e == null || e > s
    }

    fun format(seconds: Long): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
