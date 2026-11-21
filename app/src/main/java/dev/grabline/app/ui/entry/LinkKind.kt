package dev.grabline.app.ui.entry

enum class LinkKind(val label: String) {
    MAGNET("Torrent"),
    TORRENT("Torrent"),
    VIDEO("Video"),
    /** An ftp, ftps, sftp, scp, s3 or webdav link: downloaded on the phone from a server. */
    CLOUD("Server"),
    FILE("File"),
    PAGE("Web page");

    companion object {
        private class VideoRule(val host: String, val path: Regex? = null)

        /** The one list of known video sites. A host matches itself and any subdomain. */
        private val videoRules = listOf(
            VideoRule("youtube.com"),
            VideoRule("youtube-nocookie.com"),
            VideoRule("youtu.be"),
            VideoRule("vimeo.com"),
            VideoRule("twitter.com", Regex("""/([^/]+/status/\d+|i/(broadcasts|spaces|events|web/status)/.+).*""")),
            VideoRule("x.com", Regex("""/([^/]+/status/\d+|i/(broadcasts|spaces|events|web/status)/.+).*""")),
            VideoRule("t.co"),
            VideoRule("tiktok.com"),
            VideoRule("instagram.com", Regex("""/((reels?|p|tv|stories|share)/.+|[^/]+/(reels?|p|tv)/.+)""")),
            VideoRule("facebook.com", Regex("""/(watch|reel|video\.php|story\.php|permalink\.php|share/[a-z]/.+|[^/]+/(videos|posts|reel)(/.*)?).*""")),
            VideoRule("fb.watch"),
            VideoRule("reddit.com"),
            VideoRule("redd.it"),
            VideoRule("dailymotion.com"),
            VideoRule("dai.ly"),
            VideoRule("twitch.tv", Regex("""/(videos/\d+|[^/]+/(clip|video)/.+|clip/.+).*""")),
            VideoRule("clips.twitch.tv"),
            VideoRule("soundcloud.com"),
            VideoRule("bandcamp.com", Regex("""/(track|album)/.+""")),
            VideoRule("mixcloud.com"),
            VideoRule("audiomack.com"),
            VideoRule("streamable.com"),
            VideoRule("rumble.com"),
            VideoRule("odysee.com"),
            VideoRule("lbry.tv"),
            VideoRule("bitchute.com", Regex("""/(video|embed)/.+""")),
            VideoRule("bilibili.com", Regex("""/(video|bangumi|festival)/.+""")),
            VideoRule("b23.tv"),
            VideoRule("ted.com", Regex("""/talks/.+""")),
            VideoRule("pinterest.com", Regex("""/pin/.+""")),
            VideoRule("pin.it"),
            VideoRule("kick.com"),
            VideoRule("bsky.app", Regex("""/profile/[^/]+/post/.+""")),
            VideoRule("threads.net", Regex("""/@?[^/]+/post/.+""")),
            VideoRule("threads.com", Regex("""/@?[^/]+/post/.+""")),
            VideoRule("linkedin.com", Regex("""/(posts|feed/update|video)/.+""")),
            VideoRule("tumblr.com", Regex("""/(post|video)/.+""")),
            VideoRule("9gag.com", Regex("""/gag/.+""")),
            VideoRule("coub.com", Regex("""/view/.+""")),
            VideoRule("rutube.ru", Regex("""/(video|shorts)/.+""")),
            VideoRule("ok.ru", Regex("""/video(embed)?/.+""")),
            VideoRule("vk.com", Regex("""/(video|clip).+""")),
            VideoRule("vk.ru", Regex("""/(video|clip).+""")),
            VideoRule("archive.org", Regex("""/details/.+""")),
            VideoRule("imgur.com", Regex("""/(gallery|a|t)/.+""")),
            VideoRule("vevo.com"),
        )

        private val fileExtensions = setOf(
            "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "iso", "img", "dmg", "exe", "msi",
            "apk", "deb", "rpm", "pdf", "epub", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt",
            "csv", "mp3", "m4a", "flac", "wav", "ogg", "opus", "aac", "mp4", "mkv", "avi", "mov",
            "webm", "m4v", "wmv", "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ttf", "otf",
        )

        private val streamExtensions = setOf("m3u8", "mpd")

        private val urlParts =
            Regex("""^https?://(?:[^/?#@]*@)?([^/?#:]+)(?::\d+)?([^?#]*)""", RegexOption.IGNORE_CASE)

        fun classify(link: String): LinkKind {
            val t = link.trim()
            if (t.startsWith("magnet:", ignoreCase = true)) return MAGNET
            if (t.startsWith(dev.grabline.app.engine.torrent.TorrentRouting.FILE_PREFIX, ignoreCase = true)) return TORRENT
            if (dev.grabline.app.engine.cloud.CloudUrl.parse(t) != null) return CLOUD
            val m = urlParts.find(t) ?: return PAGE
            val host = m.groupValues[1].lowercase().removeSuffix(".")
            val path = m.groupValues[2]
            val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
            if (ext == "torrent") return TORRENT
            // HLS and DASH playlists are streams: yt-dlp fetches the parts and FFmpeg joins them (QA-35).
            if (ext in streamExtensions) return VIDEO
            val isVideo = videoRules.any { r ->
                (host == r.host || host.endsWith("." + r.host)) &&
                    (r.path == null || r.path.matches(path))
            }
            if (isVideo) return VIDEO
            return if (ext in fileExtensions) FILE else PAGE
        }
    }
}
