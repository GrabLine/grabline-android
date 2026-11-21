package dev.grabline.app.ui.entry

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkKindTest {
    private fun k(s: String) = LinkKind.classify(s)

    @Test fun magnet() = assertEquals(LinkKind.MAGNET, k("magnet:?xt=urn:btih:abc"))
    @Test fun torrentPath() = assertEquals(LinkKind.TORRENT, k("https://x.org/a/b.torrent?x=1"))
    @Test fun youtube() = assertEquals(LinkKind.VIDEO, k("https://www.youtube.com/watch?v=1"))
    @Test fun youtuBe() = assertEquals(LinkKind.VIDEO, k("https://youtu.be/1"))
    @Test fun mobileYoutube() = assertEquals(LinkKind.VIDEO, k("https://m.youtube.com/watch?v=1"))
    @Test fun tweetStatus() = assertEquals(LinkKind.VIDEO, k("https://x.com/user/status/123"))
    @Test fun twitterProfileIsPage() = assertEquals(LinkKind.PAGE, k("https://twitter.com/user"))
    @Test fun instagramReel() = assertEquals(LinkKind.VIDEO, k("https://www.instagram.com/reel/abc/"))
    @Test fun instagramProfileIsPage() = assertEquals(LinkKind.PAGE, k("https://www.instagram.com/someone/"))
    @Test fun twitchVideo() = assertEquals(LinkKind.VIDEO, k("https://www.twitch.tv/videos/123"))
    @Test fun twitchChannelIsPage() = assertEquals(LinkKind.PAGE, k("https://www.twitch.tv/somechannel"))
    @Test fun fileZip() = assertEquals(LinkKind.FILE, k("https://example.com/files/a.ZIP?dl=1"))
    @Test fun plainPage() = assertEquals(LinkKind.PAGE, k("https://example.com/blog/post"))
    @Test fun hlsPlaylistIsVideo() = assertEquals(LinkKind.VIDEO, k("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"))
    @Test fun dashManifestIsVideo() = assertEquals(LinkKind.VIDEO, k("https://cdn.example/live/stream.MPD?token=1"))
    @Test fun lookalikeHostIsNotVideo() = assertEquals(LinkKind.PAGE, k("https://notyoutube.com/x"))
    @Test fun labels() {
        assertEquals("Web page", LinkKind.PAGE.label)
        assertEquals("Torrent", LinkKind.MAGNET.label)
        assertEquals("Video", LinkKind.VIDEO.label)
        assertEquals("File", LinkKind.FILE.label)
    }

    @Test fun moreVideoAndAudioSitesAreRecognised() {
        val videos = listOf(
            "https://www.youtube.com/@NASA", "https://music.youtube.com/watch?v=abc", "https://youtu.be/abc",
            "https://x.com/i/broadcasts/1ZkKzeyrPbaxv", "https://twitter.com/i/status/123", "https://twitter.com/NASA/status/123?s=20",
            "https://www.instagram.com/reel/Chunk8-jurw/", "https://www.instagram.com/stories/user/123/", "https://www.instagram.com/someone/reel/abc/",
            "https://www.facebook.com/video.php?v=274175099429670", "https://www.facebook.com/cnn/videos/10155529876156509/", "https://fb.watch/abc/",
            "https://m.facebook.com/watch/?v=1", "https://www.facebook.com/share/v/abc/", "https://clips.twitch.tv/FaintLightGull",
            "https://www.twitch.tv/xqc/clip/Cultured", "https://www.twitch.tv/videos/12345", "https://v.redd.it/abc", "https://www.reddit.com/r/x/comments/1/a/",
            "https://streamable.com/moo", "https://rumble.com/v5abc-title.html", "https://odysee.com/@a:b/c:d", "https://artist.bandcamp.com/track/song",
            "https://www.mixcloud.com/a/b/", "https://www.bilibili.com/video/BV1xx", "https://www.ted.com/talks/some_talk", "https://www.pinterest.com/pin/123/",
            "https://kick.com/spreen/clips/clip_1", "https://bsky.app/profile/a.bsky.social/post/3l4", "https://www.threads.net/@user/post/abc",
            "https://www.linkedin.com/posts/someone_activity-123", "https://soundcloud.com/a/b", "https://on.soundcloud.com/abc", "https://vm.tiktok.com/abc/",
        )
        for (u in videos) assertEquals(u, LinkKind.VIDEO, LinkKind.classify(u))
        // Ordinary pages on the same sites stay pages, so they are checked instead of handed to the video engine.
        for (u in listOf("https://x.com/home", "https://www.instagram.com/someone/", "https://www.facebook.com/", "https://bandcamp.com/", "https://www.linkedin.com/in/someone")) {
            assertEquals(u, LinkKind.PAGE, LinkKind.classify(u))
        }
    }
}
