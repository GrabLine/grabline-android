package dev.grabline.app.ui.entry

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Asks the server whether a link that looks like a web page is really a file.
 * Real download links often have no extension or an unknown one (`/get/12345`, `tool.bin`).
 */
fun interface LinkProbe {
    /** True when the server serves a file at [url]; false for a page or when the server can't be reached. */
    suspend fun isFile(url: String): Boolean

    companion object {
        /** Never probes: every link keeps its extension-based kind. */
        val None = LinkProbe { false }
    }
}

/** Probes with HEAD, falling back to a one-byte ranged GET when the server refuses HEAD. */
class HttpLinkProbe(client: OkHttpClient = OkHttpClient()) : LinkProbe {
    private val http = client.newBuilder()
        .addInterceptor(dev.grabline.app.engine.BrowserUserAgent)
        .followRedirects(true)
        .followSslRedirects(true)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private class Answer(val code: Int, val file: Boolean)

    override suspend fun isFile(url: String): Boolean = withContext(Dispatchers.IO) {
        val head = try {
            Request.Builder().url(url).head().build()
        } catch (e: IllegalArgumentException) {
            return@withContext false
        }
        val first = ask(head)
        if (first != null && first.code !in HEAD_REFUSED) return@withContext first.file
        val get = head.newBuilder().get().header("Range", "bytes=0-0").build()
        ask(get)?.file ?: false
    }

    /** What the server said, or null when it could not be reached in time. */
    private fun ask(request: Request): Answer? = try {
        http.newCall(request).execute().use { r ->
            val disposition = r.header("Content-Disposition").orEmpty().lowercase()
            val type = r.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
            val attached = "attachment" in disposition || "filename" in disposition
            Answer(r.code, r.isSuccessful && (attached || (type != null && type !in PAGE_TYPES)))
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val TIMEOUT_SECONDS = 6L
        val HEAD_REFUSED = setOf(403, 405, 501)
        val PAGE_TYPES = setOf("text/html", "application/xhtml+xml")
    }
}
