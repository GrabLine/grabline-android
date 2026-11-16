package dev.grabline.app.engine.cloud

import dev.grabline.app.engine.EngineText
import java.io.IOException
import java.time.Instant
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * S3 and S3-compatible servers over OkHttp with our own SigV4 signing (no AWS SDK, to keep the app small).
 * The host of an s3:// link is the bucket; the key is the path. Without an access key the request is anonymous
 * (public buckets). With an endpoint the bucket goes in the path; on AWS a bucket with dots does too, because
 * its name would not match the virtual-host certificate.
 */
class S3Connection(
    private val c: CloudConnection,
    private val http: OkHttpClient,
    private val clock: () -> Instant = Instant::now,
) : RemoteConnection {
    @Volatile private var current: Call? = null
    @Volatile private var closed = false
    private var region = c.region.ifEmpty { "us-east-1" }

    override fun connect() = Unit

    private fun url(key: String, query: List<Pair<String, String>>): HttpUrl {
        val bucket = c.host
        val b = if (c.endpoint.isNotEmpty()) {
            val base = c.endpoint.toHttpUrlOrNull() ?: throw CloudException(CloudText.BAD_LINK)
            base.newBuilder().addEncodedPathSegment(CloudUrl.encode(bucket, false))
        } else if ('.' in bucket) {
            HttpUrl.Builder().scheme("https").host("s3.$region.amazonaws.com").addEncodedPathSegment(CloudUrl.encode(bucket, false))
        } else {
            HttpUrl.Builder().scheme("https").host("$bucket.s3.$region.amazonaws.com")
        }
        if (key.isNotEmpty()) key.split('/').forEach { b.addEncodedPathSegment(CloudUrl.encode(it, false)) }
        query.forEach { (k, v) -> b.addEncodedQueryParameter(k, v) }
        return b.build()
    }

    private fun hostHeader(u: HttpUrl): String {
        val h = if (u.host.contains(':')) "[${u.host}]" else u.host
        return if (u.port == HttpUrl.defaultPort(u.scheme)) h else "$h:${u.port}"
    }

    private fun execute(method: String, key: String, query: List<Pair<String, String>>, range: Long?): Response {
        var tried = false
        while (true) {
            if (closed) throw IOException("closed")
            val u = url(key, query)
            val rb = Request.Builder().url(u).method(method, null).header("Accept-Encoding", "identity")
            val headers = mutableMapOf<String, String>()
            if (range != null && range > 0) headers["range"] = "bytes=$range-"
            if (c.user.isNotEmpty() && c.password.isNotEmpty()) {
                val encodedPath = "/" + u.encodedPathSegments.joinToString("/")
                SigV4.sign(method, hostHeader(u), encodedPath, query, headers, c.user, c.password, region, clock())
                    .forEach { (k, v) -> headers[k] = v }
            }
            headers.forEach { (k, v) -> rb.header(k, v) }
            val call = http.newCall(rb.build())
            current = call
            val response = call.execute()
            // A bucket in another region answers with its region: retry there once.
            val real = response.header("x-amz-bucket-region")
            if (!tried && real != null && real != region && (response.code == 301 || response.code == 400 || response.code == 307)) {
                response.close()
                region = real
                tried = true
                continue
            }
            return response
        }
    }

    private fun fail(r: Response): Nothing {
        val code = r.code
        r.close()
        throw when (code) {
            401, 403 -> CloudException(CloudText.AUTH)
            404 -> CloudException(CloudText.NOT_FOUND)
            else -> CloudException(EngineText.server(code))
        }
    }

    override fun stat(): RemoteStat {
        execute("HEAD", c.path.trimStart('/'), emptyList(), null).use { r ->
            if (!r.isSuccessful) fail(r)
            val size = r.header("Content-Length")?.toLongOrNull()
            val etag = r.header("ETag").orEmpty().trim('"')
            val identity = "s3:$size:$etag:${r.header("x-amz-version-id").orEmpty()}:${r.header("Last-Modified").orEmpty()}"
            return RemoteStat(size, identity)
        }
    }

    override fun open(offset: Long, identity: String?): RemoteStream {
        val r = execute("GET", c.path.trimStart('/'), emptyList(), offset)
        if (!r.isSuccessful) fail(r)
        val start = if (offset > 0 && r.code == 206) offset else 0L
        val total = if (r.code == 206) r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() else r.header("Content-Length")?.toLongOrNull()
        val body = r.body ?: throw IOException("empty body")
        return RemoteStream(body.byteStream(), start, total, identity)
    }

    override fun list(): List<RemoteEntry> {
        val prefix = c.path.trimStart('/')
        val out = ArrayList<RemoteEntry>()
        var token: String? = null
        while (true) {
            val q = mutableListOf("list-type" to "2")
            if (token != null) q += "continuation-token" to CloudUrl.encode(token, keepSlash = false)
            if (prefix.isNotEmpty()) q += "prefix" to CloudUrl.encode(prefix, keepSlash = false)
            val page = execute("GET", "", q, null).use { r ->
                if (!r.isSuccessful) fail(r)
                parseListing(r.body?.string().orEmpty())
            }
            for ((key, size) in page.objects) {
                if (key.endsWith("/")) continue // folder marker
                out += RemoteEntry(key.substringAfterLast('/'), size, "/$key")
            }
            token = page.next
            if (!page.truncated || token == null) break
        }
        return out
    }

    override fun close() {
        closed = true
        current?.cancel()
    }

    class Page(val objects: List<Pair<String, Long?>>, val truncated: Boolean, val next: String?)

    companion object {
        private val contents = Regex("<Contents>(.*?)</Contents>", RegexOption.DOT_MATCHES_ALL)

        private fun tag(xml: String, name: String): String? =
            Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.let(::unescape)

        fun parseListing(xml: String): Page {
            val objects = contents.findAll(xml).mapNotNull { m ->
                val key = tag(m.groupValues[1], "Key") ?: return@mapNotNull null
                key to tag(m.groupValues[1], "Size")?.trim()?.toLongOrNull()
            }.toList()
            return Page(objects, tag(xml, "IsTruncated")?.trim() == "true", tag(xml, "NextContinuationToken"))
        }

        private fun unescape(s: String): String =
            Regex("&(#x?[0-9A-Fa-f]+|amp|lt|gt|quot|apos);").replace(s) { m ->
                when (val e = m.groupValues[1]) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    else -> runCatching {
                        val cp = if (e[1] == 'x' || e[1] == 'X') e.substring(2).toInt(16) else e.substring(1).toInt()
                        String(Character.toChars(cp))
                    }.getOrDefault(m.value)
                }
            }
    }
}
