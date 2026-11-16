package dev.grabline.app.ui.add

import dev.grabline.app.engine.video.AnalysisParser
import dev.grabline.app.engine.video.VideoArgs
import dev.grabline.app.engine.video.VideoDownload
import dev.grabline.app.engine.video.VideoFormat
import dev.grabline.app.remote.protocol.QualityOption
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddOptionsTest {
    @Test fun codecsGetFamiliarNames() {
        assertEquals("H.264", FormatMapping.codecName("avc1.640028"))
        assertEquals("H.265", FormatMapping.codecName("hev1.1.6.L93"))
        assertEquals("VP9", FormatMapping.codecName("vp09.00.40.08"))
        assertEquals("AV1", FormatMapping.codecName("av01.0.08M.08"))
        assertEquals("AAC", FormatMapping.codecName("mp4a.40.2"))
        assertEquals("Opus", FormatMapping.codecName("opus"))
        assertEquals("", FormatMapping.codecName("none"))
        assertEquals("", FormatMapping.codecName(null))
        assertEquals("XYZ", FormatMapping.codecName("xyz.1"))
    }

    private val formats = listOf(
        VideoFormat("251", "webm", null, vcodec = null, acodec = "opus", sizeBytes = 5_000_000),
        VideoFormat("137", "mp4", 1080, 1920, 30.0, "avc1.640028", null, 120_000_000),
        VideoFormat("18", "mp4", 360, 640, 30.0, "avc1.42001E", "mp4a.40.2", 20_000_000),
        VideoFormat("248", "webm", 1080, 1920, 30.0, "vp09.00.40.08", null, 90_000_000),
        VideoFormat("140", "m4a", null, vcodec = null, acodec = "mp4a.40.2", sizeBytes = 9_000_000),
    )

    @Test fun rowsAreSortedByResolutionThenSizeWithAudioLast() {
        val rows = FormatMapping.fromVideo(formats)
        assertEquals(listOf("137", "248", "18", "140", "251"), rows.map { it.id })
        assertEquals(listOf(1080, 1080, 360, null, null), rows.map { it.height })
        assertEquals(listOf(false, false, false, true, true), rows.map { it.audioOnly })
    }

    @Test fun rowsCarryCodecContainerAndSize() {
        val row = FormatMapping.fromVideo(formats).first { it.id == "137" }
        assertEquals("H.264", row.codec)
        assertEquals("mp4", row.container)
        assertEquals(120_000_000L, row.sizeBytes)
        assertEquals("Opus", FormatMapping.fromVideo(formats).first { it.id == "251" }.codec)
    }

    @Test fun aVideoOnlyFormatGetsTheBestAudioAdded() {
        val spec = FormatMapping.fromVideo(formats).associate { it.id to it.spec }
        assertEquals("137+ba/137", spec["137"])
        assertEquals("18", spec["18"])
        assertEquals("140", spec["140"])
    }

    @Test fun pcRowsPickTheMatchingChip() {
        val rows = FormatMapping.fromPc(
            listOf(
                QualityOption("1080p", "video", 1080, "mp4", 100),
                QualityOption("Best", "video", 2160, "mp4", null),
                QualityOption("Weird", "video", 500, "mp4", null),
                QualityOption("MP3", "audio", null, "mp3", 3),
            ),
            listOf("best", "1080p", "mp3"),
        )
        assertEquals(listOf("1080p", "best", null, "mp3"), rows.map { it.presetWire })
        assertTrue(rows.last().audioOnly)
        assertEquals(1080, rows.first().height)
    }

    @Test fun theAnalysisParserKeepsRawFormatsAndSubtitles() {
        val json = """
            {"title":"T","duration":60,
             "formats":[
               {"format_id":"sb0","ext":"mhtml","vcodec":"none","acodec":"none","format_note":"storyboard"},
               {"format_id":"137","ext":"mp4","vcodec":"avc1.640028","acodec":"none","height":1080,"width":1920,"filesize":1000},
               {"format_id":"140","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","filesize":200}],
             "subtitles":{"en":[{"name":"English"}],"live_chat":[{}]},
             "automatic_captions":{"en":[{"name":"English"}],"fr":[{"name":"French"}],"xx":[{"name":"Odd"}]}}
        """.trimIndent()
        val a = AnalysisParser.parse(json)
        assertEquals(listOf("137", "140"), a.formats.map { it.id })
        assertEquals(listOf("en", "fr"), a.subtitles.map { it.lang })
        assertFalse(a.subtitles.first().auto)
        assertTrue(a.subtitles.last().auto)
    }

    @Test fun clipTimesParse() {
        assertEquals(90L, ClipTime.parse("90"))
        assertEquals(90L, ClipTime.parse("1:30"))
        assertEquals(3723L, ClipTime.parse("1:02:03"))
        assertNull(ClipTime.parse(""))
        assertNull(ClipTime.parse("1:75"))
        assertNull(ClipTime.parse("a:b"))
        assertNull(ClipTime.parse("1:2:3:4"))
        assertEquals("1:30", ClipTime.format(90))
        assertEquals("1:02:03", ClipTime.format(3723))
    }

    @Test fun clipValidity() {
        assertTrue(ClipTime.isValid("", ""))
        assertTrue(ClipTime.isValid("0:10", ""))
        assertTrue(ClipTime.isValid("", "2:00"))
        assertTrue(ClipTime.isValid("0:10", "2:00"))
        assertFalse(ClipTime.isValid("2:00", "0:10"))
        assertFalse(ClipTime.isValid("2:00", "2:00"))
        assertFalse(ClipTime.isValid("x", ""))
    }

    private fun request(vararg extra: Pair<String, Any?>): VideoDownload {
        val m = extra.toMap()
        return VideoDownload(
            url = "https://youtu.be/x", quality = "720p", dir = File("scratch"), processId = "p", retries = 3,
            formatSpec = m["spec"] as String?, subtitleLang = m["subs"] as String?,
            clipStartSec = m["start"] as Long?, clipEndSec = m["end"] as Long?,
        )
    }

    @Test fun clipBecomesDownloadSections() {
        assertNull(VideoArgs.clipSection(null, null))
        assertEquals("*60-150", VideoArgs.clipSection(60, 150))
        assertEquals("*60-inf", VideoArgs.clipSection(60, null))
        assertEquals("*0-150", VideoArgs.clipSection(null, 150))
        val args = VideoArgs.download(request("start" to 60L, "end" to 150L))
        assertEquals("*60-150", args[args.indexOf("--download-sections") + 1])
    }

    @Test fun pickedFormatAndSubtitlesReachYtDlp() {
        val args = VideoArgs.download(request("spec" to "137+ba/137", "subs" to "en"))
        assertEquals("137+ba/137", args[args.indexOf("-f") + 1])
        assertEquals("en", args[args.indexOf("--sub-langs") + 1])
        assertTrue("--embed-subs" in args)
        val plain = VideoArgs.download(request())
        assertTrue("--download-sections" !in plain && "--write-subs" !in plain)
        assertTrue(plain[plain.indexOf("-f") + 1].contains("height<=720"))
    }
}
