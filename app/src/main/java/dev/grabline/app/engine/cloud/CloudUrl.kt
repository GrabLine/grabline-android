package dev.grabline.app.engine.cloud

import java.net.URLDecoder

/** The server schemes the phone downloads itself (same set as the desktop's cloud engine). */
object CloudSchemes {
    val ALL = setOf("ftp", "ftps", "sftp", "scp", "s3", "webdav", "webdavs")

    fun isCloud(url: String): Boolean = schemeOf(url) in ALL

    fun schemeOf(url: String): String? {
        val t = url.trim()
        val i = t.indexOf("://")
        if (i <= 0) return null
        return t.substring(0, i).lowercase()
    }

    fun defaultPort(scheme: String): Int = when (scheme) {
        "ftp", "ftps" -> 21
        "sftp", "scp" -> 22
        "webdav" -> 80
        "webdavs" -> 443
        else -> 0
    }
}

/** A parsed server link. [path] and [user] are percent-decoded; [rawPath] keeps the encoding (WebDAV needs it). */
data class CloudTarget(
    val scheme: String,
    val host: String,
    val port: Int?,
    val user: String,
    val password: String?,
    val path: String,
    val rawPath: String,
    val query: String?,
) {
    /** A link to a folder (ends in a slash, or has no path): downloading it queues the files inside. */
    val isFolder: Boolean get() = rawPath.isEmpty() || rawPath.endsWith("/")

    /** The link without its password: what is stored and shown. */
    fun withoutPassword(): String = build(includePassword = false)

    fun build(includePassword: Boolean = false, newRawPath: String = rawPath): String {
        val sb = StringBuilder(scheme).append("://")
        if (user.isNotEmpty()) {
            sb.append(CloudUrl.encode(user, keepSlash = false))
            if (includePassword && password != null) sb.append(':').append(CloudUrl.encode(password, keepSlash = false))
            sb.append('@')
        }
        sb.append(if (host.contains(':')) "[$host]" else host)
        if (port != null) sb.append(':').append(port)
        sb.append(newRawPath)
        if (query != null) sb.append('?').append(query)
        return sb.toString()
    }

    /** The link of a file inside this folder. */
    fun child(name: String): String {
        val base = if (rawPath.endsWith("/") || rawPath.isEmpty()) rawPath.ifEmpty { "/" } else "$rawPath/"
        return build(includePassword = false, newRawPath = base + CloudUrl.encode(name, keepSlash = false)).substringBefore('?')
    }
}

object CloudUrl {
    private val shape = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*)://([^/?#]*)([^?#]*)(?:\?([^#]*))?(?:#.*)?$""")

    fun parse(link: String): CloudTarget? {
        val m = shape.matchEntire(link.trim()) ?: return null
        val scheme = m.groupValues[1].lowercase()
        if (scheme !in CloudSchemes.ALL) return null
        val authority = m.groupValues[2]
        val at = authority.lastIndexOf('@')
        val userInfo = if (at >= 0) authority.substring(0, at) else ""
        val hostPort = authority.substring(at + 1)
        val host: String
        var port: Int? = null
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return null
            host = hostPort.substring(1, close)
            val rest = hostPort.substring(close + 1)
            if (rest.isNotEmpty()) {
                if (!rest.startsWith(":")) return null
                if (rest.length > 1) port = rest.substring(1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            }
        } else {
            val colon = hostPort.lastIndexOf(':')
            if (colon >= 0) {
                host = hostPort.substring(0, colon)
                val p = hostPort.substring(colon + 1)
                if (p.isNotEmpty()) port = p.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            } else {
                host = hostPort
            }
        }
        if (host.isBlank()) return null
        val colonAt = userInfo.indexOf(':')
        val user = decode(if (colonAt >= 0) userInfo.substring(0, colonAt) else userInfo)
        val password = if (colonAt >= 0) decode(userInfo.substring(colonAt + 1)).takeIf { it.isNotEmpty() } else null
        val rawPath = m.groupValues[3]
        return CloudTarget(
            scheme = scheme, host = host, port = port, user = user, password = password,
            path = decode(rawPath), rawPath = rawPath, query = m.groups[4]?.value?.takeIf { it.isNotEmpty() },
        )
    }

    /** A file name from the last path segment; "download" when there is none. */
    fun suggestedFilename(url: String): String {
        val t = parse(url) ?: return "download"
        return t.path.trimEnd('/').substringAfterLast('/').ifEmpty { "download" }.takeIf { !t.isFolder } ?: "download"
    }

    fun decode(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"

    /** Percent-encodes everything but unreserved characters (and '/' when [keepSlash]). */
    fun encode(s: String, keepSlash: Boolean): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xff).toChar()
            if (c in UNRESERVED || (keepSlash && c == '/')) sb.append(c) else sb.append('%').append("%02X".format(b.toInt() and 0xff))
        }
        return sb.toString()
    }
}
