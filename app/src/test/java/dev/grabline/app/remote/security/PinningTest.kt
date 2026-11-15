package dev.grabline.app.remote.security

import java.io.IOException
import java.net.Proxy
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLHandshakeException
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PinningTest {
    private val pc = TestCerts.selfSigned("GrabLine A3D7Za2aw26jbGofVV3ZAQ")
    private val other = TestCerts.selfSigned("GrabLine A3D7Za2aw26jbGofVV3ZAQ")
    private val server = MockWebServer()

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    // ------------------------------------------------------------ trust manager

    @Test
    fun pinIsSha256OfSpkiBase64UrlNoPad() {
        assertEquals(43, pc.pin.length)
        assertEquals(pc.pin, Pin.ofSpki(pc.certificate.publicKey.encoded))
        assertFalse(pc.pin.contains('='))
        assertNotEquals(pc.pin, other.pin)
        assertTrue(Pin.matches(pc.pin, pc.pin))
        assertFalse(Pin.matches(pc.pin, other.pin))
        assertFalse(Pin.matches(pc.pin, null))
        assertFalse(Pin.matches("", ""))
        assertNull(Pin.bytes(pc.pin + "A"))
        assertNull(Pin.bytes(pc.pin.dropLast(1) + "="))
    }

    @Test
    fun acceptsExactlyThePinnedLeaf() {
        val tm = PinnedTrustManager(pc.pin)
        tm.checkServerTrusted(arrayOf(pc.certificate), "ECDHE_ECDSA")
        // only chain[0] counts: extra certificates after it change nothing
        tm.checkServerTrusted(arrayOf(pc.certificate, other.certificate), "ECDHE_ECDSA")
        assertEquals(0, tm.acceptedIssuers.size)
    }

    @Test
    fun rejectsAnotherKeyWithPinMismatch() {
        val tm = PinnedTrustManager(pc.pin)
        val wrong = arrayOf(other.certificate)
        assertThrows(PinMismatchException::class.java) { tm.checkServerTrusted(wrong, "ECDHE_ECDSA") }
        // the pinned certificate later in the chain does not help
        assertThrows(PinMismatchException::class.java) { tm.checkServerTrusted(arrayOf(other.certificate, pc.certificate), "ECDHE_ECDSA") }
        assertThrows(PinMismatchException::class.java) { tm.checkServerTrusted(wrong, "ECDHE_ECDSA", null as Socket?) }
        assertThrows(PinMismatchException::class.java) { tm.checkServerTrusted(wrong, "ECDHE_ECDSA", null as SSLEngine?) }
        assertThrows(PinMismatchException::class.java) { tm.checkServerTrusted(wrong, "ECDHE_ECDSA", "192.168.1.20") }
        val asCertificateException: CertificateException = PinMismatchException()
        assertNotNull(asCertificateException)
        assertEquals("Your PC's identity changed. If you reinstalled GrabLine, pair again", PinMismatchException.USER_MESSAGE)
    }

    @Test
    fun failsClosedOnEmptyChainOrMalformedPin() {
        val tm = PinnedTrustManager(pc.pin)
        assertThrows(PinMismatchException::class.java) { tm.checkServerTrusted(emptyArray(), "ECDHE_ECDSA") }
        assertThrows(PinMismatchException::class.java) { tm.checkServerTrusted(null, "ECDHE_ECDSA") }
        for (bad in listOf("", "AAAA", pc.pin + "A", pc.pin.dropLast(1) + "=")) {
            assertThrows(PinMismatchException::class.java) { PinnedTrustManager(bad).checkServerTrusted(arrayOf(pc.certificate), "ECDHE_ECDSA") }
        }
        assertThrows(CertificateException::class.java) { tm.checkClientTrusted(arrayOf(pc.certificate), "EC") }
    }

    @Test
    fun sameKeyInAReissuedCertificateStillMatches() {
        // PROTOCOL.md section 4: the PC may re-issue the certificate around the same key.
        val reissued = TestCerts.selfSigned("GrabLine renamed", keyPair = pc.keyPair)
        assertNotEquals(pc.certificate, reissued.certificate)
        PinnedTrustManager(pc.pin).checkServerTrusted(arrayOf(reissued.certificate), "ECDHE_ECDSA")
    }

    @Test
    fun validityDatesAreNotChecked() {
        val expired = TestCerts.selfSigned(notBefore = Date(0), notAfter = Date(86_400_000L))
        assertThrows(CertificateException::class.java) { expired.certificate.checkValidity() }
        PinnedTrustManager(expired.pin).checkServerTrusted(arrayOf<X509Certificate>(expired.certificate), "ECDHE_ECDSA")
    }

    @Test
    fun mismatchIsFoundInsideWrappedFailures() {
        val wrapped = IOException("outer", SSLHandshakeException("handshake").apply { initCause(PinMismatchException()) })
        assertTrue(PinMismatchException.isCauseOf(wrapped))
        assertNotNull(PinMismatchException.findIn(wrapped))
        assertFalse(PinMismatchException.isCauseOf(IOException("timeout")))
        assertFalse(PinMismatchException.isCauseOf(null))
    }

    // ------------------------------------------------------------ client settings

    @Test
    fun clientSettingsFollowTheProtocol() {
        val base = OkHttpClient.Builder()
            .addInterceptor { it.proceed(it.request().newBuilder().header("Cookie", "leak=1").build()) }
            .addNetworkInterceptor { it.proceed(it.request()) }
            .cookieJar(object : CookieJar {
                override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            })
            .build()
        val client = pinnedOkHttpClient(base, pc.pin)
        assertEquals(2_000, client.connectTimeoutMillis)
        assertEquals(20_000, client.pingIntervalMillis)
        assertNull(client.cache)
        assertTrue(client.cookieJar === CookieJar.NO_COOKIES)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertEquals("only our own header-stripping interceptor", 1, client.interceptors.size)
        assertTrue(client.networkInterceptors.isEmpty())
        assertTrue(client.x509TrustManager is PinnedTrustManager)
        assertTrue(client.hostnameVerifier.verify("anything.example", null))
        val spec = client.connectionSpecs.single()
        assertTrue(spec.isTls)
        assertEquals(listOf(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2), spec.tlsVersions)
        assertTrue(client.dispatcher === base.dispatcher)
    }

    // ------------------------------------------------------------ real TLS handshakes

    private fun startServer(identity: TestCerts.Identity) {
        server.useHttps(identity.serverSocketFactory(), false)
        server.start()
    }

    private fun get(client: OkHttpClient): Int {
        val quick = client.newBuilder().readTimeout(5, TimeUnit.SECONDS).build()
        return quick.newCall(Request.Builder().url(server.url("/remote")).build()).execute().use { it.code }
    }

    @Test
    fun handshakeSucceedsWithThePinnedSelfSignedCertificate() {
        startServer(pc)
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(204, get(pinnedOkHttpClient(OkHttpClient(), pc.pin)))
        val seen = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertNull(seen.getHeader("Cookie"))
        assertNull(seen.getHeader("Origin"))
        assertTrue(seen.tlsVersion == TlsVersion.TLS_1_3 || seen.tlsVersion == TlsVersion.TLS_1_2)
    }

    @Test
    fun handshakeSucceedsWithAnExpiredPinnedCertificate() {
        val expired = TestCerts.selfSigned(notBefore = Date(0), notAfter = Date(86_400_000L))
        startServer(expired)
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(204, get(pinnedOkHttpClient(OkHttpClient(), expired.pin)))
    }

    @Test
    fun handshakeFailsWithPinMismatchAndSendsNothing() {
        startServer(other) // a different key at the PC's address (reinstall, or impersonation)
        server.enqueue(MockResponse().setResponseCode(204))
        try {
            get(pinnedOkHttpClient(OkHttpClient(), pc.pin))
            fail("the handshake must not succeed")
        } catch (e: IOException) {
            assertTrue("cause chain must hold PinMismatchException: $e", PinMismatchException.isCauseOf(e))
        }
        assertEquals("no request may reach the wrong server", 0, server.requestCount)
    }

    @Test
    fun redirectsAreNotFollowed() {
        startServer(pc)
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.invalid/"))
        assertEquals(302, get(pinnedOkHttpClient(OkHttpClient(), pc.pin)))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun manualPairingClientAcceptsAnyKeyButIsNotThePinnedClient() {
        startServer(other)
        server.enqueue(MockResponse().setResponseCode(204))
        val manual = manualPairingOkHttpClient(OkHttpClient())
        assertFalse(manual.x509TrustManager is PinnedTrustManager)
        assertEquals(204, get(manual))
    }
}
