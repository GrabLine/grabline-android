package dev.grabline.app.ui.entry

/** Extracts downloadable links (http, https, magnet, and server links such as ftp, sftp, s3, webdav) from free text such as a share. */
object IncomingLinks {
    const val MAX_LINKS = 300

    private val urlRegex = Regex(
        """(?<![A-Za-z0-9+.\-]:)(?:https?://[^\s/?#<>"'`]+[^\s<>"'`]*|magnet:\?[^\s<>"'`]+|(?:ftps?|sftp|scp|s3|webdavs?)://[^\s<>"'`]+)""",
        RegexOption.IGNORE_CASE,
    )
    private const val TRAILING = ".,;:!?'\"]}>*"

    fun extract(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val seen = LinkedHashSet<String>()
        for (m in urlRegex.findAll(text)) {
            val link = clean(m.value) ?: continue
            seen.add(link)
            if (seen.size >= MAX_LINKS) break
        }
        return seen.toList()
    }

    private fun clean(raw: String): String? {
        var s = raw
        while (s.isNotEmpty()) {
            val last = s.last()
            val drop = when {
                last in TRAILING -> true
                last == ')' -> s.count { it == ')' } > s.count { it == '(' }
                else -> false
            }
            if (!drop) break
            s = s.dropLast(1)
        }
        val lower = s.lowercase()
        return when {
            lower.startsWith("magnet:?") -> if ("xt=" in lower) s else null
            lower.startsWith("https://") || lower.startsWith("http://") ->
                if (s.substringAfter("//").isNotEmpty()) s else null
            Regex("""^(?:ftps?|sftp|scp|s3|webdavs?)://.+""").matches(lower) -> if (dev.grabline.app.engine.cloud.CloudUrl.parse(s) != null) s else null
            else -> null
        }
    }
}
