package dev.grabline.app.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserUserAgentTest {
    @Test fun downloadsLookLikeABrowserUnlessTheRequestSaysOtherwise() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("a"))
        server.enqueue(MockResponse().setBody("b"))
        server.start()
        try {
            val client = OkHttpClient.Builder().addInterceptor(BrowserUserAgent).build()
            client.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()
            client.newCall(Request.Builder().url(server.url("/y")).header("User-Agent", "Custom/1").build()).execute().close()
            assertTrue(server.takeRequest().getHeader("User-Agent")!!.startsWith("Mozilla/5.0"))
            assertEquals("Custom/1", server.takeRequest().getHeader("User-Agent"))
        } finally {
            server.shutdown()
        }
    }
}
