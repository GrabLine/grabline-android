package dev.grabline.app.remote.pairing

import dev.grabline.app.remote.security.PinMismatchException
import dev.grabline.app.remote.security.TestCerts
import dev.grabline.app.remote.security.Vectors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The real transport against a TLS WebSocket server presenting a self-signed P-256 certificate. */
class OkHttpTransportTest {
    private val pc = TestCerts.selfSigned()
    private val server = MockWebServer()
    private val base = OkHttpClient()
    private val received = LinkedBlockingQueue<String>()
    private val hello = Vectors.message("hello.server.json")

    private fun start(identity: TestCerts.Identity, closeWith: Int? = null) {
        server.useHttps(identity.serverSocketFactory(), false)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(hello)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received += text
                    if (closeWith != null) webSocket.close(closeWith, "bye")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }
            }),
        )
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        base.dispatcher.executorService.shutdown()
        base.connectionPool.evictAll()
    }

    @Test
    fun pinnedConnectionExchangesFramesAndReportsTheCloseCode() = runBlocking {
        start(pc, closeWith = 4403)
        val connection = withTimeout(10_000) { OkHttpTransport(base).connect(server.hostName, server.port, TrustMode.Pinned(pc.pin)) }
        assertEquals(pc.pin, connection.peerPin)
        assertEquals(hello, (withTimeout(10_000) { connection.receive() } as Frame.Text).text)
        assertTrue(connection.send("""{"id":"pair-1","type":"pair"}"""))
        assertEquals("""{"id":"pair-1","type":"pair"}""", received.poll(10, TimeUnit.SECONDS))
        assertEquals(Frame.Closed(4403), withTimeout(10_000) { connection.receive() })
        assertEquals("terminal frame repeats", Frame.Closed(4403), withTimeout(10_000) { connection.receive() })

        val upgrade = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/remote", upgrade.path)
        assertNull("permessage-deflate must not be offered", upgrade.getHeader("Sec-WebSocket-Extensions"))
        assertNull(upgrade.getHeader("Origin"))
        assertNull(upgrade.getHeader("Cookie"))
    }

    @Test
    fun wrongKeyThrowsPinMismatchBeforeAnythingIsSent() = runBlocking {
        start(TestCerts.selfSigned())
        try {
            withTimeout(10_000) { OkHttpTransport(base).connect(server.hostName, server.port, TrustMode.Pinned(pc.pin)) }
            fail("must not connect")
        } catch (_: PinMismatchException) {
            // expected
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun manualPairingReportsTheKeyItSaw() = runBlocking {
        start(pc)
        val connection = withTimeout(10_000) { OkHttpTransport(base).connect(server.hostName, server.port, TrustMode.ManualPairing) }
        assertEquals(pc.pin, connection.peerPin)
        assertTrue(withTimeout(10_000) { connection.receive() } is Frame.Text)
        connection.close()
    }
}
