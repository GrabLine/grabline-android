package dev.grabline.app.engine.cloud

import dev.grabline.app.engine.PhoneAddRequest
import dev.grabline.app.engine.PhoneJobStatus
import dev.grabline.app.engine.content
import java.io.File
import java.nio.file.Files
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A real SSH/SFTP server (Apache MINA SSHD) on loopback, driven through the real engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SftpEngineTest {
    private lateinit var root: File
    private lateinit var keys: File
    private lateinit var server: SshServer
    private var port = 0
    private val payload = content(400 * 1024)

    private fun startServer(): SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = 0
        keyPairProvider = SimpleGeneratorHostKeyProvider(keys.toPath()).apply { setAlgorithm("EC"); setKeySize(256) }
        setPasswordAuthenticator { user, pass, _ -> user == "bob" && pass == "secret" }
        subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
        fileSystemFactory = VirtualFileSystemFactory(root.toPath())
        start()
    }

    @Before fun start() {
        root = Files.createTempDirectory("sftp-root").toFile()
        keys = File(Files.createTempDirectory("sftp-keys").toFile(), "host.key")
        server = startServer()
        port = server.port
    }

    @After fun stop() {
        server.stop(true)
        root.deleteRecursively()
    }

    private fun url(path: String, login: String = "bob:secret@") = "sftp://${login}127.0.0.1:$port$path"

    @Test fun downloadsAndTrustsTheHostKeyOnFirstUse() = cloudRig {
        File(root, "file.bin").writeBytes(payload)
        assertNull(hostKeys.find("127.0.0.1", port))
        val job = download(url("/file.bin"))
        assertEquals(job.error, PhoneJobStatus.COMPLETED, job.status)
        assertArrayEquals(payload, storage.published["file.bin"])
        val stored = hostKeys.find("127.0.0.1", port)!!
        assertTrue(stored.sha256, stored.sha256.startsWith("SHA256:"))
        // The same server is accepted again with the stored key.
        val again = download(url("/file.bin"))
        assertEquals(again.error, PhoneJobStatus.COMPLETED, again.status)
    }

    @Test fun aChangedHostKeyIsAHardFailure() = cloudRig {
        File(root, "file.bin").writeBytes(payload)
        hostKeys.put("127.0.0.1", port, "ecdsa-sha2-nistp256", "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        val job = download(url("/file.bin"))
        assertEquals(PhoneJobStatus.FAILED, job.status)
        assertTrue(job.error, job.error!!.contains("has changed"))
        assertTrue(storage.published.isEmpty())
        assertEquals("the stored key is never replaced", "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", hostKeys.find("127.0.0.1", port)!!.sha256)
    }

    @Test fun resumesAPartialCopy() = cloudRig {
        File(root, "file.bin").writeBytes(payload)
        val job = download(url("/file.bin")) { added -> storage.scratchFile(added.id).writeBytes(payload.copyOf(123_456)) }
        assertEquals(job.error, PhoneJobStatus.COMPLETED, job.status)
        assertArrayEquals(payload, storage.published["file.bin"])
    }

    @Test fun savedLoginAndPortAreUsedAndScpLinksWorkToo() = cloudRig {
        File(root, "file.bin").writeBytes(payload)
        store.save(CloudAccount(service = "sftp", host = "127.0.0.1", username = "bob", port = port), AccountSecrets(password = "secret"))
        val job = download("scp://bob@127.0.0.1/file.bin")
        assertEquals(job.error, PhoneJobStatus.COMPLETED, job.status)
        assertArrayEquals(payload, storage.published["file.bin"])
    }

    @Test fun aWrongPasswordFailsInPlainWords() = cloudRig {
        File(root, "file.bin").writeBytes(payload)
        val job = download(url("/file.bin", login = "bob:nope@"))
        assertEquals(PhoneJobStatus.FAILED, job.status)
        assertEquals(CloudText.AUTH, job.error)
    }

    @Test fun rootListingGivesSingleSlashLinks() = cloudRig {
        File(root, "file.bin").writeBytes(ByteArray(12))
        File(root, "dir").mkdir()
        engine.add(PhoneAddRequest(url("/")))
        val row = dao.all().single()
        assertEquals("file.bin", row.name)
        assertEquals("sftp://bob@127.0.0.1:$port/file.bin", row.url)
        assertNotNull(row.cloudSecret)
    }

    @Test fun trustOnFirstUseVerifier() {
        val store = MemHostKeys()
        val v = TofuHostKeyVerifier(store)
        val kp = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(256) }
        val a = kp.generateKeyPair().public
        val b = kp.generateKeyPair().public
        assertTrue(v.verify("h", 22, a))
        assertTrue(v.verify("h", 22, a))
        assertNull(v.changed)
        assertTrue("another host is its own first use", v.verify("other", 22, b))
        assertEquals(false, v.verify("h", 22, b))
        assertNotNull(v.changed)
        assertEquals(TofuHostKeyVerifier.fingerprint(a), store.find("h", 22)!!.sha256)
    }
}
