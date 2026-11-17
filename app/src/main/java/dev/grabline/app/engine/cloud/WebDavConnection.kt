package dev.grabline.app.engine.cloud

import dev.grabline.app.engine.EngineText
import java.io.IOException
import java.net.UnknownServiceException
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** The http(s) address behind a webdav:// or webdavs:// link (query kept, password dropped, IPv6 brackets kept). */
fun webDavHttpUrl(scheme: String, host: String, port: Int, rawPath: String, query: String?): String {
    val https = scheme == "webdavs"
    val h = if (host.contains(':')) "[$host]" else host
    val default = if (https) 443 else 80
    val hostPort = if (port > 0 && port != default) "$h:$port" else h
    return (if (https) "https://" else "http://") + hostPort + rawPath.ifEmpty { "/" } + (query?.let { "?$it" } ?: "")
}

/**
 * WebDAV file download: a plain GET with Basic auth. Resume asks for a Range and checks the answer: a server that
 * ignores Range (200) or answers a different range starts the file again, so two versions never get joined.
 */
class WebDavConnection(private val c: CloudConnection, private val http: OkHttpClient) : RemoteConnection {
    @Volatile private var current: Call? = null
    @Volatile private var closed = false
    private val url = webDavHttpUrl(c.scheme, c.host, c.port, c.rawPath, c.query)

    override fun connect() = Unit

    override fun stat() = RemoteStat(null, null) // learned from the answer to the GET itself

    private fun get(range: Long?, ifRange: String?): Response {
        if (closed) throw IOException("closed")
        val rb = Request.Builder().url(url).header("Accept-Encoding", "identity")
        if (c.user.isNotEmpty()) rb.header("Authorization", Credentials.basic(c.user, c.password, Charsets.UTF_8))
        if (range != null && range > 0) {
            rb.header("Range", "bytes=$range-")
            if (ifRange != null) rb.header("If-Range", ifRange)
        }
        val call = http.newCall(rb.build())
        current = call
        try {
            return call.execute()
        } catch (e: UnknownServiceException) {
            throw CloudException(CloudText.PLAIN_HTTP, e) // cleartext is not allowed by the app's network policy
        }
    }

    private fun fail(r: Response): Nothing {
        val code = r.code
        r.close()
        throw when (code) {
            401, 403 -> CloudException(CloudText.AUTH)
            404, 410 -> CloudException(CloudText.NOT_FOUND)
            else -> CloudException(EngineText.server(code))
        }
    }

    private fun validator(r: Response): String? =
        r.header("ETag")?.takeIf { !it.startsWith("W/") } ?: r.header("Last-Modified")

    override fun open(offset: Long, identity: String?): RemoteStream {
        val ifRange = identity?.takeIf { it.startsWith("dav:") }?.removePrefix("dav:")
        var r = get(offset, ifRange)
        if (offset > 0 && r.code == 416) {
            // "Range not satisfiable" means "already complete" only when the server says the file is exactly this long.
            val total = Regex("""bytes\s+\*/(\d+)""").find(r.header("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
            val id = validator(r)?.let { "dav:$it" } ?: identity
            r.close()
            if (total == offset) return RemoteStream(java.io.ByteArrayInputStream(ByteArray(0)), offset, total, id)
            r = get(null, null) // the file shrank or changed: start again
        }
        if (r.code != 200 && r.code != 206) fail(r)
        var start = 0L
        if (offset > 0 && r.code == 206) {
            val span = parseContentRange(r.header("Content-Range"))
            if (span != null && span.start == offset) {
                start = offset
            } else {
                r.close() // answered a different range: take the whole file instead
                r = get(null, null)
                if (r.code != 200) fail(r)
            }
        }
        val total = when {
            r.code == 206 -> parseContentRange(r.header("Content-Range"))?.total
            else -> r.header("Content-Length")?.toLongOrNull()
        }
        val id = validator(r)?.let { "dav:$it" }
        val body = r.body ?: throw IOException("empty body")
        return RemoteStream(body.byteStream(), start, total, id)
    }

    override fun list(): List<RemoteEntry> = throw CloudException(CloudText.NO_DAV_FOLDERS)

    override fun close() {
        closed = true
        current?.cancel()
    }

    private class Span(val start: Long, val end: Long, val total: Long?)

    private fun parseContentRange(h: String?): Span? {
        val m = Regex("""^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""", RegexOption.IGNORE_CASE).find(h ?: return null) ?: return null
        val s = m.groupValues[1].toLongOrNull() ?: return null
        val e = m.groupValues[2].toLongOrNull() ?: return null
        return if (e < s) null else Span(s, e, m.groupValues[3].toLongOrNull())
    }
}
