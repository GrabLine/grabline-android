package dev.grabline.app.ui.entry

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LinkProbeTest {
    private lateinit var server: MockWebServer
    private val probe = HttpLinkProbe()

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After fun tearDown() = server.shutdown()

    private fun isFile(path: String = "/get/1") = runBlocking { probe.isFile(server.url(path).toString()) }

    @Test fun htmlIsAPage() {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=utf-8"))
        assertFalse(isFile())
    }

    @Test fun octetStreamIsAFile() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream"))
        assertTrue(isFile())
        assertEquals("HEAD", server.takeRequest().method)
    }

    @Test fun attachmentMakesAnHtmlTypeAFile() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/html")
                .setHeader("Content-Disposition", "attachment; filename=\"a.bin\""),
        )
        assertTrue(isFile())
    }

    @Test fun missingTypeIsAPage() {
        server.enqueue(MockResponse())
        assertFalse(isFile())
    }

    @Test fun missingTypeWithAttachmentIsAFile() {
        server.enqueue(MockResponse().setHeader("Content-Disposition", "attachment"))
        assertTrue(isFile())
    }

    @Test fun refusedHeadFallsBackToARangedGet() {
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Type", "application/zip").setBody("x"))
        assertTrue(isFile())
        assertEquals("HEAD", server.takeRequest().method)
        val get = server.takeRequest()
        assertEquals("GET", get.method)
        assertEquals("bytes=0-0", get.getHeader("Range"))
    }

    @Test fun redirectsAreFollowed() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/real.bin").toString()))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream"))
        assertTrue(isFile())
    }

    @Test fun notFoundIsAPage() {
        server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "application/octet-stream"))
        assertFalse(isFile())
    }

    @Test fun connectionRefusedIsAPage() {
        val url = server.url("/get/1").toString()
        server.shutdown()
        assertFalse(runBlocking { probe.isFile(url) })
    }
}
