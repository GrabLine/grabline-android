package dev.grabline.app.remote.security

import dev.grabline.app.remote.protocol.Base64Url
import java.net.Proxy
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.TlsVersion

/** The pin: SHA-256 of a certificate's SubjectPublicKeyInfo DER, base64url without padding, 43 characters. */
object Pin {
    const val LENGTH = 43

    fun ofSpki(spki: ByteArray): String = Base64Url.encode(sha256(spki))

    fun ofCertificate(certificate: Certificate): String = ofSpki(certificate.publicKey.encoded)

    /** The 32 raw bytes, or null when [pin] is not a well-formed pin. */
    fun bytes(pin: String?): ByteArray? =
        pin?.takeIf { it.length == LENGTH }?.let { Base64Url.decode(it) }?.takeIf { it.size == 32 }

    fun isValid(pin: String?): Boolean = bytes(pin) != null

    /** Constant-time; false when either side is malformed. */
    fun matches(expected: String?, actual: String?): Boolean {
        val a = bytes(expected) ?: return false
        val b = bytes(actual) ?: return false
        return MessageDigest.isEqual(a, b)
    }
}

/**
 * The PC presented a key that is not the pinned one (QA-07). The UI shows [USER_MESSAGE] and MUST NOT
 * offer "connect anyway". TLS stacks wrap this in an SSLHandshakeException: use [findIn].
 */
class PinMismatchException : CertificateException("the PC key does not match the pin") {
    companion object {
        const val USER_MESSAGE = "Your PC's identity changed. If you reinstalled GrabLine, pair again"

        /** The mismatch inside [error]'s cause chain, or null. */
        fun findIn(error: Throwable?): PinMismatchException? {
            var t = error
            var depth = 0
            while (t != null && depth < 16) {
                if (t is PinMismatchException) return t
                t.suppressed.firstOrNull { it is PinMismatchException }?.let { return it as PinMismatchException }
                t = t.cause
                depth++
            }
            return null
        }

        fun isCauseOf(error: Throwable?): Boolean = findIn(error) != null
    }
}

/**
 * Trusts exactly one key (PROTOCOL.md section 4): the leaf certificate (`chain[0]`) whose SPKI SHA-256
 * equals [pin]. No CA checks and no system trust store: a certificate from a public CA with another key
 * is refused. No validity-date checks: a wrong phone clock must not lock the user out. Anything
 * unexpected (empty chain, malformed pin, any exception) is a refusal.
 */
class PinnedTrustManager(pin: String) : X509ExtendedTrustManager() {
    private val expected: ByteArray? = Pin.bytes(pin)

    /** True once a handshake presented the pinned key. Never reset: use one instance per connection to rely on it. */
    @Volatile
    var acceptedPinnedKey: Boolean = false
        private set

    private fun check(chain: Array<out X509Certificate>?) {
        val ok = try {
            val want = expected
            val leaf = chain?.firstOrNull()
            want != null && leaf != null && MessageDigest.isEqual(want, sha256(leaf.publicKey.encoded))
        } catch (_: Exception) {
            false
        }
        if (!ok) throw PinMismatchException()
        acceptedPinnedKey = true
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)

    /** Called through android.net.http.X509TrustManagerExtensions on some Android versions. */
    @Suppress("unused")
    fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, host: String?): List<X509Certificate> {
        check(chain)
        return listOf(chain!![0])
    }

    /** This trust manager is for the phone as a TLS client only. */
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?): Unit =
        throw CertificateException("client certificates are not accepted")

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?): Unit =
        throw CertificateException("client certificates are not accepted")

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?): Unit =
        throw CertificateException("client certificates are not accepted")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * MANUAL PAIRING ONLY (PROTOCOL.md section 5.4). Accepts whatever key the PC presents so that the key's
 * hash can become the *candidate* pin. Safe only because the pairing proofs bind the 60-bit code to that
 * key: a machine in the middle cannot produce `serverProof`, so nothing is stored. Never use it for
 * anything but the pairing handshake; it is internal to this module's pairing transport.
 */
internal class ManualPairingTrustManager : X509ExtendedTrustManager() {
    /** Pin of the key presented in the last handshake: the candidate pin. One instance per connection. */
    @Volatile
    var seenPin: String? = null
        private set

    private fun check(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
        val spki = leaf.publicKey?.encoded ?: throw CertificateException("no public key")
        seenPin = Pin.ofSpki(spki)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)

    @Suppress("unused")
    fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, host: String?): List<X509Certificate> {
        check(chain)
        return listOf(chain!![0])
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?): Unit =
        throw CertificateException("client certificates are not accepted")
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?): Unit =
        throw CertificateException("client certificates are not accepted")
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?): Unit =
        throw CertificateException("client certificates are not accepted")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

const val PC_CONNECT_TIMEOUT_SECONDS = 2L
const val PC_PING_INTERVAL_SECONDS = 20L

/**
 * The hostname is deliberately not checked. The PC is reached by IP addresses that change and its
 * certificate carries no names; its identity is the pinned key, which the trust manager checks on every
 * handshake. The pin replaces the name, so accepting any name here weakens nothing. This verifier is
 * only ever installed together with [PinnedTrustManager] (or the manual-pairing trust manager).
 */
private val pinReplacesHostname = HostnameVerifier { _, _ -> true }

private fun OkHttpClient.buildForPc(trustManager: X509ExtendedTrustManager): OkHttpClient {
    val context = SSLContext.getInstance("TLS")
    context.init(null, arrayOf(trustManager), null)
    val tls = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
        .build()
    return newBuilder()
        // Nothing inherited from [this] may add headers, cookies or credentials to PC traffic.
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
        .authenticator(okhttp3.Authenticator.NONE)
        .proxyAuthenticator(okhttp3.Authenticator.NONE)
        .certificatePinner(okhttp3.CertificatePinner.DEFAULT)
        .sslSocketFactory(context.socketFactory, trustManager)
        .hostnameVerifier(pinReplacesHostname)
        .connectionSpecs(listOf(tls)) // TLS 1.2 or newer only; no cleartext
        .connectTimeout(PC_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .pingInterval(PC_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .cache(null)
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .proxy(Proxy.NO_PROXY)
        .retryOnConnectionFailure(false)
        // PROTOCOL.md section 2: permessage-deflate MUST NOT be negotiated, so it is never offered.
        // (An application interceptor: OkHttp skips network interceptors for WebSocket calls.)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().removeHeader("Sec-WebSocket-Extensions").build())
        }
        .build()
}

/**
 * A client that talks only to the PC whose key has [pin]: TLS 1.2+, the pin as the whole trust decision
 * (see [PinnedTrustManager]), any hostname, 2 s connect timeout, WebSocket pings every 20 s, no cache,
 * no cookies, no redirects, no proxy. [base] only lends its dispatcher and connection pool.
 * A mismatch surfaces as a failure whose cause chain holds [PinMismatchException].
 */
fun pinnedOkHttpClient(base: OkHttpClient, pin: String): OkHttpClient = base.buildForPc(PinnedTrustManager(pin))

/** Manual pairing only: see [ManualPairingTrustManager]. */
internal fun manualPairingOkHttpClient(base: OkHttpClient): OkHttpClient = base.buildForPc(ManualPairingTrustManager())
