package dev.grabline.app.engine

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Many sites and CDNs refuse a download whose User-Agent is not a browser's (HTTP 403, or an HTML page instead of the
 * file). Downloads and link checks identify as an ordinary mobile browser, as download managers do.
 * A request that already carries its own User-Agent is left alone.
 */
object BrowserUserAgent : Interceptor {
    const val VALUE = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return chain.proceed(if (request.header("User-Agent") == null) request.newBuilder().header("User-Agent", VALUE).build() else request)
    }
}
