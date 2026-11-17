package dev.grabline.app.engine.cloud

import dev.grabline.app.engine.PhoneJobStatus
import dev.grabline.app.engine.content
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.ssl.SslConfigurationFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FTPS against a real server (Apache MINA FtpServer, explicit TLS) with a self-made certificate. Found on a
 * real phone: Commons Net's default trust manager only checks dates, so any certificate was accepted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FtpsTest {
    private val root: File = Files.createTempDirectory("ftps-root").toFile()
    private var server: FtpServer? = null
    private var port = 0
    private val payload = content(64 * 1024)

    @After fun stop() {
        server?.stop()
        root.deleteRecursively()
    }

    /** Starts the server with a certificate naming [names] (IP addresses or host names); returns that certificate. */
    private fun start(vararg names: String): X509Certificate {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val subject = X500Name("CN=${names.first()}")
        val sans = names.map { if (it[0].isDigit()) GeneralName(GeneralName.iPAddress, it) else GeneralName(GeneralName.dNSName, it) }
        val now = System.currentTimeMillis()
        val cert = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(subject, BigInteger.valueOf(now), Date(now - 60_000), Date(now + 86_400_000), subject, keys.public)
                .addExtension(Extension.subjectAlternativeName, false, GeneralNames(sans.toTypedArray()))
                .build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private)),
        )
        val store = File(root.parentFile, "ftps-${System.nanoTime()}.p12")
        KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("server", keys.private, PASS.toCharArray(), arrayOf(cert))
            store.outputStream().use { store(it, PASS.toCharArray()) }
        }
        val ssl = SslConfigurationFactory().apply {
            keystoreFile = store
            keystorePassword = PASS
            keystoreType = "PKCS12"
            keyPassword = PASS
        }.createSslConfiguration()
        port = java.net.ServerSocket(0).use { it.localPort }
        val listener = ListenerFactory().apply {
            this.port = this@FtpsTest.port
            serverAddress = "127.0.0.1"
            sslConfiguration = ssl
            isImplicitSsl = false
        }.createListener()
        val users = PropertiesUserManagerFactory().createUserManager().apply {
            save(BaseUser().apply { name = "bob"; password = "secret"; homeDirectory = root.absolutePath; authorities = listOf<Authority>(WritePermission()) })
        }
        server = FtpServerFactory().apply { addListener("default", listener); userManager = users }.createServer().also { it.start() }
        File(root, "file.bin").writeBytes(payload)
        return cert
    }

    private fun trusting(cert: X509Certificate): SSLContext {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("server", cert) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
    }

    private fun connection() = CloudConnection(
        scheme = "ftps", host = "127.0.0.1", port = port, user = "bob", password = "secret", privateKey = "", passphrase = "",
        endpoint = "", region = "", path = "/file.bin", rawPath = "/file.bin", query = null,
    )

    @Test fun anUntrustedCertificateStopsTheDownloadBeforeTheLogin() = cloudRig {
        start("127.0.0.1")
        val job = download("ftps://bob:secret@127.0.0.1:$port/file.bin")
        assertEquals(PhoneJobStatus.FAILED, job.status)
        assertEquals(CloudText.TLS, job.error)
    }

    @Test fun aTrustedCertificateForThisServerWorks() {
        val cert = start("127.0.0.1")
        FtpConnection(connection(), secure = true, sslContext = trusting(cert)).use { c ->
            c.connect()
            assertEquals(payload.size.toLong(), c.stat().size)
            c.open(0, null).input.use { assertEquals(payload.size, it.readBytes().size) }
        }
    }

    @Test fun aTrustedCertificateForAnotherHostIsRefused() {
        val cert = start("files.example.com")
        FtpConnection(connection(), secure = true, sslContext = trusting(cert)).use { c ->
            assertThrows(SSLException::class.java) { c.connect() }
            Unit
        }
    }

    private companion object {
        const val PASS = "test-only"
    }
}
