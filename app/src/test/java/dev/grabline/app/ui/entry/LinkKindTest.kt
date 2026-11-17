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
}
