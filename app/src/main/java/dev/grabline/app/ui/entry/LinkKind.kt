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
            VideoRule("youtu.be"),
            VideoRule("vimeo.com"),
            VideoRule("twitter.com", Regex("""/[^/]+/status/\d+.*""")),
            VideoRule("x.com", Regex("""/[^/]+/status/\d+.*""")),
            VideoRule("tiktok.com"),
            VideoRule("instagram.com", Regex("""/(reels?|p|tv)/.+""")),
            VideoRule("facebook.com", Regex("""/(watch|reel|[^/]+/videos)(/.*)?""")),
            VideoRule("fb.watch"),
            VideoRule("reddit.com"),
            VideoRule("dailymotion.com"),
            VideoRule("dai.ly"),
            VideoRule("twitch.tv", Regex("""/(videos/\d+|[^/]+/clip/.+|clip/.+).*""")),
            VideoRule("soundcloud.com"),
        )

        private val fileExtensions = setOf(
            "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "iso", "img", "dmg", "exe", "msi",
            "apk", "deb", "rpm", "pdf", "epub", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt",
            "csv", "mp3", "m4a", "flac", "wav", "ogg", "opus", "aac", "mp4", "mkv", "avi", "mov",
            "webm", "m4v", "wmv", "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ttf", "otf",
        )

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
            val isVideo = videoRules.any { r ->
                (host == r.host || host.endsWith("." + r.host)) &&
                    (r.path == null || r.path.matches(path))
            }
            if (isVideo) return VIDEO
            return if (ext in fileExtensions) FILE else PAGE
        }
    }
}
