package dev.grabline.app.engine

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Base64
import java.util.Locale

/** Reads the name a server gives a download: Content-Disposition, the redirect target, the content type. */

/** Splits a header value on ';' outside double quotes. */
private fun headerParams(h: String): List<String> {
    val out = ArrayList<String>()
    val cur = StringBuilder()
    var quoted = false
    var escaped = false
    for (c in h) {
        when {
            escaped -> { cur.append(c); escaped = false }
            quoted && c == '\\' -> { cur.append(c); escaped = true }
            c == '"' -> { quoted = !quoted; cur.append(c) }
            c == ';' && !quoted -> { out += cur.toString(); cur.clear() }
            else -> cur.append(c)
        }
    }
    out += cur.toString()
    return out
}

private fun unquoteHeader(v: String): String {
    val t = v.trim()
    if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) {
        // Only \" and \\ are quoted-pair escapes; any other backslash is an old-style Windows path separator.
        return t.substring(1, t.length - 1).replace(Regex("""\\([\\"])"""), "$1")
    }
    return t
}

/** RFC 5987 value such as UTF-8''%E6%96%87.txt; null when it is malformed or in a charset we do not know. */
private fun decodeExtValue(v: String): String? {
    val m = Regex("""^([^']*)'[^']*'(.*)$""").matchEntire(v.trim()) ?: return null
    val charset = when (m.groupValues[1].lowercase(Locale.ROOT)) {
        "utf-8", "" -> Charsets.UTF_8
        "iso-8859-1" -> Charsets.ISO_8859_1
        else -> return null
    }
    val text = m.groupValues[2]
    val bytes = ByteArrayOutputStream()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '%') {
            if (i + 2 >= text.length) return null
            val hex = text.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
            bytes.write(hex)
            i += 3
        } else {
            bytes.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
    }
    return String(bytes.toByteArray(), charset)
}

/** RFC 2047 =?UTF-8?B?...?= or =?UTF-8?Q?...?= words, which some servers put in a plain filename. */
private fun decodeEncodedWord(v: String): String {
    val m = Regex("""^=\?([^?]+)\?([bBqQ])\?([^?]*)\?=$""").matchEntire(v.trim()) ?: return v
    val charset = runCatching { Charset.forName(m.groupValues[1]) }.getOrNull() ?: return v
    return runCatching {
        if (m.groupValues[2].equals("b", ignoreCase = true)) {
            String(Base64.getDecoder().decode(m.groupValues[3]), charset)
        } else {
            val q = m.groupValues[3].replace('_', ' ')
            val out = ByteArrayOutputStream()
            var i = 0
            while (i < q.length) {
                if (q[i] == '=' && i + 2 < q.length) {
                    out.write(q.substring(i + 1, i + 3).toInt(16))
                    i += 3
                } else {
                    out.write(q[i].code)
                    i++
                }
            }
            String(out.toByteArray(), charset)
        }
    }.getOrDefault(v)
}

/**
 * The file name a Content-Disposition header asks for (RFC 6266: filename* beats filename), reduced to one safe
 * path segment; null when the header gives none that is usable.
 */
fun dispositionName(header: String?): String? {
    if (header.isNullOrBlank()) return null
    var plain: String? = null
    var star: String? = null
    for (part in headerParams(header)) {
        val eq = part.indexOf('=')
        if (eq < 0) continue
        val key = part.substring(0, eq).trim().lowercase(Locale.ROOT)
        val value = part.substring(eq + 1)
        when (key) {
            "filename*" -> star = decodeExtValue(value)
            "filename" -> plain = decodeEncodedWord(unquoteHeader(value))
        }
    }
    val raw = (star ?: plain)?.substringAfterLast('/')?.substringAfterLast('\\') ?: return null
    if (raw.isBlank() || raw.trim('.', ' ').isEmpty()) return null
    return fileNameFor(raw, "")
}

private val MIME_EXTENSIONS = mapOf(
    "application/pdf" to "pdf", "application/zip" to "zip", "application/x-7z-compressed" to "7z",
    "application/vnd.rar" to "rar", "application/gzip" to "gz", "application/json" to "json",
    "application/epub+zip" to "epub", "application/x-iso9660-image" to "iso",
    "application/vnd.android.package-archive" to "apk", "video/mp4" to "mp4", "video/webm" to "webm",
    "video/x-matroska" to "mkv", "audio/mpeg" to "mp3", "audio/mp4" to "m4a", "audio/flac" to "flac",
    "audio/ogg" to "ogg", "audio/wav" to "wav", "image/jpeg" to "jpg", "image/png" to "png", "image/gif" to "gif",
    "image/webp" to "webp", "text/plain" to "txt", "text/csv" to "csv",
)

private fun hasExtension(name: String): Boolean {
    val dot = name.lastIndexOf('.')
    return dot > 0 && dot < name.length - 1 && name.length - dot <= 11
}

/**
 * A better name than the one taken from the link, from what the server said: its Content-Disposition name first,
 * then the redirect target's name, then an extension for the content type. Returns [current] when nothing is better.
 */
fun betterFileName(current: String, disposition: String?, contentType: String?, finalUrl: String?): String {
    dispositionName(disposition)?.let { return it }
    if (hasExtension(current)) return current
    finalUrl?.let { fileNameFor(null, it) }?.takeIf { it != "download" && hasExtension(it) }?.let { return it }
    val ext = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.let { MIME_EXTENSIONS[it] }
    return if (ext != null) "$current.$ext" else current
}
