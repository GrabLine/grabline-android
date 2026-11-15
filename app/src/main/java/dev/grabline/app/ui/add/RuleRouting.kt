package dev.grabline.app.ui.add

import dev.grabline.app.ui.entry.LinkKind

/** Maps a link to a Rules type ("video", "music", "torrent", "other"; see RulesCodec.TYPES). */
object RuleRouting {
    private val audio = setOf("mp3", "m4a", "flac", "wav", "ogg", "opus", "aac")

    fun typeOf(link: String): String = when (LinkKind.classify(link)) {
        LinkKind.VIDEO -> "video"
        LinkKind.MAGNET, LinkKind.TORRENT -> "torrent"
        LinkKind.FILE -> {
            val path = link.trim().substringBefore('#').substringBefore('?')
            if (path.substringAfterLast('/').substringAfterLast('.', "").lowercase() in audio) "music" else "other"
        }
        LinkKind.PAGE -> "other"
    }
}
