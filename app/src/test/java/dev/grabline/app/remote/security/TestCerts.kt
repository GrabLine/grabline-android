package dev.grabline.app.remote.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory

/**
 * Self-signed ECDSA P-256 certificates shaped like the PC's (CN only, no SubjectAltName), built by hand
 * so the tests need no extra library.
 */
object TestCerts {
    class Identity(val keyPair: KeyPair, val certificate: X509Certificate) {
        val pin: String get() = Pin.ofCertificate(certificate)

        /** A TLS server socket factory presenting this certificate. */
        fun serverSocketFactory(): SSLSocketFactory {
            val password = "test".toCharArray()
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setKeyEntry("pc", keyPair.private, password, arrayOf(certificate))
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
            return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }.socketFactory
        }
    }

    fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private const val DAY_MS = 86_400_000L

    fun selfSigned(
        commonName: String = "GrabLine test",
        keyPair: KeyPair = newKeyPair(),
        notBefore: Date = Date(System.currentTimeMillis() - DAY_MS),
        notAfter: Date = Date(System.currentTimeMillis() + 3650 * DAY_MS),
    ): Identity {
        val ecdsaSha256 = seq(oid(0x2a, 0x86, 0x48, 0xce, 0x3d, 0x04, 0x03, 0x02))
        val name = seq(set(seq(oid(0x55, 0x04, 0x03), tlv(0x0c, commonName.toByteArray(Charsets.UTF_8)))))
        val tbs = seq(
            tlv(0xa0, tlv(0x02, byteArrayOf(2))), // v3
            tlv(0x02, BigInteger(63, SecureRandom()).add(BigInteger.ONE).toByteArray()),
            ecdsaSha256,
            name,
            seq(time(notBefore), time(notAfter)),
            name,
            keyPair.public.encoded,
        )
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }
        val der = seq(tbs, ecdsaSha256, tlv(0x03, byteArrayOf(0) + signature))
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        certificate.verify(keyPair.public)
        return Identity(keyPair, certificate)
    }

    private fun time(date: Date): ByteArray {
        val format = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(0x18, (format.format(date) + "Z").toByteArray(Charsets.US_ASCII)) // GeneralizedTime
    }

    private fun oid(vararg bytes: Int): ByteArray = tlv(0x06, ByteArray(bytes.size) { bytes[it].toByte() })
    private fun seq(vararg parts: ByteArray): ByteArray = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    private fun set(vararg parts: ByteArray): ByteArray = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })

    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        when {
            value.size < 0x80 -> out.write(value.size)
            value.size < 0x100 -> {
                out.write(0x81)
                out.write(value.size)
            }
            else -> {
                out.write(0x82)
                out.write(value.size shr 8)
                out.write(value.size and 0xff)
            }
        }
        out.write(value)
        return out.toByteArray()
    }
}
