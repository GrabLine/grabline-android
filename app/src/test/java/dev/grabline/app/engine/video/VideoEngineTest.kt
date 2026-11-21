package dev.grabline.app.engine.video

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fixture(name: String) = javaClass.classLoader!!.getResource("video/$name")!!.readText()

    // ---- format selectors (mirror app/engines/smart.py) ----

    @Test fun presetsUseTheDesktopFormatSelectors() {
        assertEquals("bv*+ba/b", VideoPresets.forWire("best").formatSpec)
        assertEquals("bv*[height<=1080]+ba/b[height<=1080]/bv*+ba/b", VideoPresets.forWire("1080p").formatSpec)
        assertEquals("bv*[height<=2160]+ba/b[height<=2160]/bv*+ba/b", VideoPresets.forWire("2160p").formatSpec)
        assertEquals("ba[ext=m4a]/ba/b", VideoPresets.forWire("m4a").formatSpec)
        assertEquals("ba/b", VideoPresets.forWire("mp3").formatSpec)
        assertEquals("flac", VideoPresets.forWire("flac").audioFormat)
        assertEquals(listOf("best", "2160p", "1440p", "1080p", "720p", "480p", "360p", "mp3", "m4a", "flac"), VideoPresets.WIRE)
    }

    @Test fun unknownAndMixedCaseWiresResolve() {
        assertEquals("720p", VideoPresets.forWire(" 720P ").wire)
        assertEquals("mp3", VideoPresets.forWire("audio").wire)
        assertEquals("best", VideoPresets.forWire("weird").wire)
        assertEquals("best", VideoPresets.forWire(null).wire)
    }

    @Test fun downloadArgsForVideoAndAudio() {
        val dir = tmp.newFolder("job")
        val video = VideoArgs.download(VideoDownload("https://v.example/x", "1080p", dir, "job-1", 3))
        assertEquals("bv*[height<=1080]+ba/b[height<=1080]/bv*+ba/b", video[video.indexOf("-f") + 1])
        assertTrue("--merge-output-format" in video && "-x" !in video)
        assertEquals("3", video[video.indexOf("--retries") + 1])
        assertTrue("--embed-metadata" in video && "--add-metadata" in video)
        assertTrue("no codec limit unless asked", "-S" !in video)
        val vp9 = VideoArgs.download(VideoDownload("https://v.example/x", "2160p", dir, "job-1", 3), preferCodec = "vp9")
        assertEquals("res,fps,vcodec:vp9", vp9[vp9.indexOf("-S") + 1])
        val mp3Sorted = VideoArgs.download(VideoDownload("https://v.example/x", "mp3", dir, "job-1", 3), preferCodec = "h264")
        assertTrue("audio is not sorted by video codec", "-S" !in mp3Sorted)

        val mp3 = VideoArgs.download(VideoDownload("https://v.example/x", "mp3", dir, "job-1", -1))
        assertEquals("mp3", mp3[mp3.indexOf("--audio-format") + 1])
        assertEquals("192K", mp3[mp3.indexOf("--audio-quality") + 1])
        assertEquals("infinite", mp3[mp3.indexOf("--retries") + 1])
        assertTrue("--embed-metadata" in mp3 && "--add-metadata" in mp3 && "--merge-output-format" !in mp3)
        val flac = VideoArgs.download(VideoDownload("https://v.example/x", "flac", dir, "job-1", 0))
        assertEquals("0", flac[flac.indexOf("--audio-quality") + 1])
    }

    @Test fun oneVideoOfAPlaylistLinkIsNotAPlaylistDownload() {
        assertTrue(VideoArgs.singleVideoOfPlaylist("https://www.youtube.com/watch?v=a&list=PL1"))
        assertFalse(VideoArgs.singleVideoOfPlaylist("https://www.youtube.com/playlist?list=PL1"))
        assertTrue("--no-playlist" in VideoArgs.analyze("https://www.youtube.com/watch?v=a&list=PL1"))
    }

    // ---- DRM ----

    @Test fun drmServicesAreRefusedWithTheSpecText() {
        assertEquals("Netflix protects its media with DRM. GrabLine can't download it", DrmGuard.refusal("https://www.netflix.com/title/80100172"))
        assertEquals("Prime Video protects its media with DRM. GrabLine can't download it", DrmGuard.refusal("https://www.primevideo.com/detail/x"))
        assertEquals("Spotify protects its media with DRM. GrabLine can't download it", DrmGuard.refusal("https://open.spotify.com/track/abc"))
        assertEquals("Amazon Music protects its media with DRM. GrabLine can't download it", DrmGuard.refusal("https://music.amazon.de/albums/x"))
        assertNull("podcasts are not DRM", DrmGuard.refusal("https://open.spotify.com/episode/abc"))
        assertNull(DrmGuard.refusal("https://www.youtube.com/watch?v=abc"))
        assertNull(DrmGuard.refusal("https://notnetflix.com/x"))
        assertNull(DrmGuard.refusal("not a url"))
    }

    @Test fun engineRefusesDrmWithoutStartingYtDlp() {
        val backend = FakeBackend()
        val engine = YoutubeDlVideoEngine(backend)
        try {
            runBlocking { engine.analyze("https://www.hulu.com/watch/1") }
            fail("expected a refusal")
        } catch (e: VideoException) {
            assertEquals(VideoFailure.DRM, e.failure)
            assertEquals("Hulu protects its media with DRM. GrabLine can't download it", e.message)
        }
        assertEquals(0, backend.inits)
        assertEquals(0, backend.runs)
    }

    // ---- analysis parsing from a recorded-shape yt-dlp JSON ----

    @Test fun singleVideoQualitiesAndSizeEstimates() {
        val a = AnalysisParser.parse(fixture("ytdlp-single-info.json"))
        assertEquals("Sample clip", a.title)
        assertEquals(212L, a.durationSeconds)
        assertEquals("Sample Channel", a.uploader)
        assertFalse(a.isPlaylist)
        assertEquals(listOf("best", "1080p", "720p", "360p", "mp3", "m4a", "flac"), a.qualities.map { it.wire })
        val sizes = a.qualities.associate { it.wire to it.sizeBytes }
        assertEquals(53_000_000L, sizes["best"]) // 1080p video-only + best audio
        assertEquals(53_000_000L, sizes["1080p"])
        assertEquals(28_000_000L, sizes["720p"]) // the higher-bitrate 720p stream + audio
        assertEquals(9_000_000L, sizes["360p"]) // muxed: no audio added
        assertEquals(3_000_000L, sizes["mp3"])
        assertNull(sizes["flac"])
    }

    @Test fun playlistIsFlaggedWithItsCount() {
        val a = AnalysisParser.parse(fixture("ytdlp-playlist-info.json"))
        assertTrue(a.isPlaylist)
        assertEquals(3, a.playlistCount)
        assertEquals("best", a.qualities.first().wire)
    }

    @Test fun heightsSnapToTheLadder() {
        assertEquals(1080, AnalysisParser.snapToTier(1088))
        assertEquals(2160, AnalysisParser.snapToTier(2176))
        assertNull(AnalysisParser.snapToTier(540))
    }

    @Test fun engineAnalyzesThroughTheBackend() {
        val backend = FakeBackend(out = fixture("ytdlp-single-info.json"))
        val a = runBlocking { YoutubeDlVideoEngine(backend).analyze("https://www.youtube.com/watch?v=dQw4w9WgXcQ") }
        assertEquals("Sample clip", a.title)
        assertEquals(1, backend.inits)
        assertTrue("--dump-single-json" in backend.lastArgs)
    }

    // ---- errors ----

    @Test fun errorsBecomePlainFailures() {
        assertEquals(VideoFailure.SITE_CHANGED, VideoErrors.classify("ERROR: [youtube] x: Unable to extract nsig function; please report this issue"))
        assertEquals(VideoFailure.SITE_CHANGED, VideoErrors.classify("Make sure you are using the latest version"))
        assertEquals(VideoFailure.DRM, VideoErrors.classify("ERROR: [x] This video is DRM protected"))
        assertEquals(VideoFailure.NO_MEDIA, VideoErrors.classify("ERROR: Unsupported URL: https://example.com/page"))
        assertEquals(VideoFailure.REFUSED, VideoErrors.classify("ERROR: unable to download video data: HTTP Error 403: Forbidden"))
        assertEquals(VideoFailure.NOT_FOUND, VideoErrors.classify("HTTP Error 404: Not Found"))
        assertEquals(VideoFailure.CONNECTION, VideoErrors.classify("ERROR: Unable to download webpage: <urlopen error timed out>"))
        assertEquals(VideoFailure.NO_SPACE, VideoErrors.classify("OSError: [Errno 28] No space left on device"))
        assertEquals(VideoFailure.UNEXPECTED, VideoErrors.classify("something odd"))
        // Real yt-dlp YouTube wording that used to fall through to "something went wrong".
        assertEquals(VideoFailure.NOT_FOUND, VideoErrors.classify("ERROR: [youtube] aaaaaaaaaaa: This video is unavailable"))
        assertEquals(VideoFailure.LIVE, VideoErrors.classify("ERROR: [youtube] x: This live stream recording is not available."))
        assertEquals(VideoFailure.LIVE, VideoErrors.classify("ERROR: [youtube] x: This live event will begin in 3 hours."))
        assertEquals(VideoFailure.LIVE, VideoErrors.classify("ERROR: [youtube] x: Premieres in 2 days"))
        assertEquals(VideoFailure.REFUSED, VideoErrors.classify("ERROR: [youtube] x: Video unavailable. This video contains content from X, who has blocked it in your country"))
        assertEquals(VideoFailure.REFUSED, VideoErrors.classify("ERROR: [youtube] x: Join this channel to get access to members-only content"))
        assertEquals(VideoFailure.REFUSED, VideoErrors.classify("ERROR: [youtube] x: Sign in to confirm you're not a bot"))
        assertEquals("This site changed. Update the video engine?", VideoText.forFailure(VideoFailure.SITE_CHANGED))
    }

    @Test fun failedDownloadSurfacesTheReason() {
        val backend = FakeBackend(fail = "ERROR: Unable to extract uploader id")
        try {
            runBlocking { YoutubeDlVideoEngine(backend).download(VideoDownload("https://v.example/x", "best", tmp.newFolder("d"), "job-1", 3)) { } }
            fail("expected a failure")
        } catch (e: VideoException) {
            assertEquals(VideoFailure.SITE_CHANGED, e.failure)
        }
    }

    // ---- progress ----

    @Test fun progressLinesGiveSizeAndSpeed() {
        val line = "[download]  45.3% of ~  12.34MiB at    1.20MiB/s ETA 00:05 (frag 3/10)"
        assertEquals((12.34 * 1024 * 1024).toLong(), ProgressTracker.parseSize(line))
        assertEquals((1.2 * 1024 * 1024).toLong(), ProgressTracker.parseSpeed(line))
    }

    @Test fun twoStreamsAreOneProgress() {
        val t = ProgressTracker()
        val a = t.update(100f, 0, "[download] 100% of 10.00MiB at 2.00MiB/s ETA 00:00")
        assertEquals(10L shl 20, a.downloaded)
        val b = t.update(10f, 3, "[download]  10.0% of 2.00MiB at 1.00MiB/s ETA 00:03")
        assertEquals((10L shl 20) + (0.2 * (1 shl 20)).toLong(), b.downloaded)
        assertEquals(12L shl 20, b.total)
        assertEquals(3L, b.etaSeconds)
    }

    @Test fun downloadReturnsFinishedFilesNotScratch() {
        val dir = tmp.newFolder("job")
        val backend = FakeBackend(onRun = {
            File(dir, "Title.mp4").writeText("video")
            File(dir, "Title.f137.mp4.part").writeText("x")
            File(dir, "Title.mp4.ytdl").writeText("x")
        })
        val files = runBlocking {
            YoutubeDlVideoEngine(backend).download(VideoDownload("https://v.example/x", "best", dir, "job-1", 3)) { }
        }
        assertEquals(listOf("Title.mp4"), files.map { it.name })
    }

    private class FakeBackend(
        private val out: String = "{}",
        private val fail: String? = null,
        private val onRun: () -> Unit = {},
    ) : YtDlpBackend {
        var inits = 0
        var runs = 0
        var lastArgs: List<String> = emptyList()
        override fun init() { inits++ }
        override fun version() = "2025.01.01"
        override fun run(url: String, args: List<String>, processId: String?, onProgress: ((Float, Long, String) -> Unit)?): String {
            runs++
            lastArgs = args
            fail?.let { throw YtDlpFailed(it) }
            onRun()
            return out
        }
        override fun cancel(processId: String) {}
        override fun update() = false
    }

    @Test fun aLiveStreamIsRefusedWhenAnalysed() {
        for (status in listOf("is_live", "is_upcoming")) {
            val e = runCatching { AnalysisParser.parse("""{"title":"Radio","live_status":"$status","formats":[]}""") }.exceptionOrNull()
            assertTrue("$status gives a VideoException", e is VideoException)
            assertEquals(VideoFailure.LIVE, (e as VideoException).failure)
        }
        val e2 = runCatching { AnalysisParser.parse("""{"title":"Radio","is_live":true,"formats":[]}""") }.exceptionOrNull()
        assertEquals(VideoFailure.LIVE, (e2 as VideoException).failure)
        // A finished stream is an ordinary video.
        assertEquals("Radio", AnalysisParser.parse("""{"title":"Radio","live_status":"was_live","formats":[]}""").title)
    }

    @Test fun socialSiteErrorsGetSpecificReasons() {
        assertEquals(VideoFailure.NO_VIDEO, VideoErrors.classify("ERROR: [twitter] 1: No video could be found in this tweet"))
        assertEquals(VideoFailure.NO_VIDEO, VideoErrors.classify("ERROR: [instagram] x: There is no video in this post"))
        assertEquals(VideoFailure.REFUSED, VideoErrors.classify("ERROR: [Reddit] 6rrwyj: Account authentication is required. Use --cookies-from-browser or --cookies for the authentication."))
        assertEquals(VideoFailure.REFUSED, VideoErrors.classify("ERROR: [Instagram] x: Requested content is not available, rate-limit reached or login required"))
        assertEquals(VideoFailure.REFUSED, VideoErrors.classify("ERROR: [twitter] x: NSFW tweet requires authentication"))
        assertEquals(VideoFailure.REFUSED, VideoErrors.classify("ERROR: [twitter] x: This Tweet is protected"))
        assertEquals(VideoFailure.CONNECTION, VideoErrors.classify("ERROR: [download] Got error: [Errno 7] No address associated with hostname. Giving up after 3 retries"))
        assertEquals(VideoFailure.REFUSED, VideoErrors.classify("WARNING: [kick:clip] The extractor is attempting impersonation, but no impersonate target is available."))
        assertEquals("There's no video at this link", VideoText.forFailure(VideoFailure.NO_VIDEO))
    }
}
