package dev.grabline.app.engine.cloud

import dev.grabline.app.engine.PhoneJobStatus
import dev.grabline.app.engine.content
import java.io.File
import java.nio.file.Files
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A real FTP server (Apache MINA FtpServer) on loopback, driven through the real engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FtpEngineTest {
    private lateinit var root: File
    private lateinit var server: FtpServer
    private var port = 0
    private val payload = content(300 * 1024)

    @Before fun start() {
        root = Files.createTempDirectory("ftp-root").toFile()
        port = java.net.ServerSocket(0).use { it.localPort }
        val listener = ListenerFactory().apply { this.port = this@FtpEngineTest.port; serverAddress = "127.0.0.1" }.createListener()
        val factory = FtpServerFactory().apply { addListener("default", listener) }
        val users = PropertiesUserManagerFactory().createUserManager()
        users.save(
            BaseUser().apply {
                name = "bob"
                password = "secret"
                homeDirectory = root.absolutePath
                authorities = listOf<Authority>(WritePermission())
            },
        )
        factory.userManager = users
        server = factory.createServer()
        server.start()
    }

    @After fun stop() {
        server.stop()
        root.deleteRecursively()
    }

    private fun url(path: String, login: String = "bob:secret@") = "ftp://$login" + "127.0.0.1:$port$path"

    @Test fun downloadsAFileWithAnInlineLoginAndNeverStoresThePassword() = cloudRig {
        File(root, "file.bin").writeBytes(payload)
        val job = download(url("/file.bin"))
        assertEquals(job.error, PhoneJobStatus.COMPLETED, job.status)
        assertArrayEquals(payload, storage.published["file.bin"])
        val row = dao.get(job.id)!!
        assertFalse(row.url.contains("secret"))
        assertNotNull("the password is kept sealed", row.cloudSecret)
        assertFalse(row.cloudSecret!!.contains("secret"))
        assertTrue(storage.scratchFiles().isEmpty())
    }

    @Test fun resumesAPartialCopyWithRest() = cloudRig {
        File(root, "file.bin").writeBytes(payload)
        val job = download(url("/file.bin")) { added ->
            storage.scratchFile(added.id).writeBytes(payload.copyOf(50_000))
        }
        assertEquals(job.error, PhoneJobStatus.COMPLETED, job.status)
        assertArrayEquals(payload, storage.published["file.bin"])
    }

    @Test fun aPartialCopyOfAnotherVersionStartsAgain() = cloudRig {
        File(root, "file.bin").writeBytes(payload)
        val job = download(url("/file.bin")) { added ->
            storage.scratchFile(added.id).writeBytes(ByteArray(50_000) { 7 })
            dao.update(dao.get(added.id)!!.copy(etag = "ftp:12345:20000101000000"))
        }
        assertEquals(job.error, PhoneJobStatus.COMPLETED, job.status)
        assertArrayEquals("the stale head was thrown away, not joined", payload, storage.published["file.bin"])
    }

    @Test fun neverOverwritesAnExistingFile() = cloudRig {
        File(root, "file.bin").writeBytes(payload)
        storage.preexisting += "file.bin"
        val job = download(url("/file.bin"))
        assertEquals(job.error, PhoneJobStatus.COMPLETED, job.status)
        assertNull(storage.published["file.bin"])
        assertArrayEquals(payload, storage.published["file (1).bin"])
        assertEquals("file (1).bin", dao.get(job.id)!!.name)
    }

    @Test fun savedAccountSuppliesTheLoginAndPort() = cloudRig {
        File(root, "file.bin").writeBytes(payload)
        store.save(CloudAccount(service = "ftp", host = "127.0.0.1", username = "bob", port = port), AccountSecrets(password = "secret"))
        val job = download("ftp://bob@127.0.0.1/file.bin")
        assertEquals(job.error, PhoneJobStatus.COMPLETED, job.status)
        assertArrayEquals(payload, storage.published["file.bin"])
    }

    @Test fun aFolderLinkQueuesItsFilesAndSkipsDirectories() = cloudRig {
        File(root, "a.bin").writeBytes(ByteArray(10) { 1 })
        File(root, "b.bin").writeBytes(ByteArray(20) { 2 })
        File(root, "sub").mkdir()
        val first = engine.add(dev.grabline.app.engine.PhoneAddRequest(url("/")))
        val rows = dao.all()
        assertEquals(listOf("a.bin", "b.bin"), rows.map { it.name }.sorted())
        assertTrue(rows.all { it.url.startsWith("ftp://bob@127.0.0.1:$port/") && !it.url.contains("secret") })
        assertEquals(setOf(10L, 20L), rows.map { it.total }.toSet())
        engine.runUntilIdle()
        assertEquals(setOf("a.bin", "b.bin"), storage.published.keys)
        assertNotNull(first)
    }

    @Test fun aWrongPasswordFailsInPlainWords() = cloudRig {
        File(root, "file.bin").writeBytes(payload)
        val job = download(url("/file.bin", login = "bob:wrong@"))
        assertEquals(PhoneJobStatus.FAILED, job.status)
        assertEquals(CloudText.AUTH, job.error)
    }

    @Test fun aMissingFileFailsInPlainWords() = cloudRig {
        val job = download(url("/nope.bin"))
        assertEquals(PhoneJobStatus.FAILED, job.status)
        assertEquals(CloudText.NOT_FOUND, job.error)
    }
}
