package dev.grabline.app.engine.cloud

import dev.grabline.app.engine.EngineConfig
import dev.grabline.app.engine.EngineText
import dev.grabline.app.engine.PhoneAddRequest
import dev.grabline.app.engine.PhoneJobStatus
import dev.grabline.app.engine.content
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The runner's behaviour with scripted connections: failures, drops, pause and cancel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudRunnerTest {
    private class Script(val connect: () -> Unit = {}, val stat: () -> RemoteStat, val open: (Long) -> RemoteStream) : RemoteConnection {
        @Volatile var closed = false
        override fun connect() = connect.invoke()
        override fun stat() = stat.invoke()
        override fun open(offset: Long, identity: String?) = open.invoke(offset)
        override fun list(): List<RemoteEntry> = emptyList()
        override fun close() { closed = true }
    }

    private fun backend(create: () -> RemoteConnection, resolveFails: Throwable? = null) = { _: CredentialStore, _: MemHostKeys, _: okhttp3.OkHttpClient ->
        object : CloudBackend {
            override fun sealInline(password: String): String? = null
            override suspend fun resolve(url: String, sealedSecret: String?): CloudConnection {
                resolveFails?.let { throw it }
                return CloudConnection("ftp", "h", 21, "u", "", "", "", "", "", "/f", "/f", null)
            }
            override fun create(connection: CloudConnection) = create()
        }
    }

    private val schemes = listOf("ftp", "sftp", "s3", "webdav")

    @Test fun aRejectedConnectionFailsTheJobInsteadOfEscaping() {
        for (scheme in schemes) {
            cloudRig(backend = backend({ Script(connect = { throw CloudException("test server rejected credentials") }, stat = { RemoteStat(null, null) }, open = { error("no") }) })) {
                val job = download("$scheme://files.example/file.bin")
                assertEquals(scheme, PhoneJobStatus.FAILED, job.status)
                assertEquals("test server rejected credentials", job.error)
            }
        }
    }

    @Test fun anUnexpectedErrorIsPlainWordsNotAStackTrace() = cloudRig(backend = backend({ Script(connect = { throw IllegalStateException("boom secret-in-message") }, stat = { RemoteStat(null, null) }, open = { error("no") }) })) {
        val job = download("ftp://files.example/file.bin")
        assertEquals(PhoneJobStatus.FAILED, job.status)
        assertEquals(EngineText.UNEXPECTED, job.error)
    }

    @Test fun aResolveFailureFailsTheJob() = cloudRig(backend = backend({ error("unused") }, resolveFails = CloudException(CloudText.AUTH))) {
        val job = download("ftp://files.example/file.bin")
        assertEquals(CloudText.AUTH, job.error)
    }

    @Test fun aShortTransferIsNeverPublishedAndKeepsItsPartialForResume() = cloudRig(
        backend = backend({ Script(stat = { RemoteStat(1000, "x:1000") }, open = { RemoteStream(ByteArrayInputStream(ByteArray(400)), 0, null, "x:1000") }) }),
    ) {
        val job = download("ftp://files.example/file.bin")
        assertEquals(PhoneJobStatus.FAILED, job.status)
        assertEquals(EngineText.CONNECTION, job.error)
        assertTrue(storage.published.isEmpty())
        assertEquals(400L, storage.scratchFile(job.id).length())
    }

    @Test fun aDroppedTransferIsRetriedAndResumedWithinTheBudget() {
        val data = content(10_000)
        var attempt = 0
        cloudRig(
            config = EngineConfig(onlyWifi = false, autoRetry = 2),
            backend = backend({
                Script(stat = { RemoteStat(data.size.toLong(), "x") }, open = { offset ->
                    attempt++
                    if (attempt == 1) RemoteStream(object : InputStream() {
                        var n = 0
                        override fun read(): Int = if (n < 4000) data[n++].toInt() and 0xff else throw IOException("reset")
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (n >= 4000) throw IOException("reset")
                            val k = minOf(len, 4000 - n)
                            System.arraycopy(data, n, b, off, k)
                            n += k
                            return k
                        }
                    }, 0, null, "x") else RemoteStream(ByteArrayInputStream(data, offset.toInt(), data.size - offset.toInt()), offset, null, "x")
                })
            }),
        ) {
            val job = download("ftp://files.example/file.bin")
            assertEquals(job.error, PhoneJobStatus.COMPLETED, job.status)
            assertArrayEquals(data, storage.published["file.bin"])
            assertEquals(2, attempt)
        }
    }

    @Test fun pauseStopsABlockedReadPromptlyAndKeepsTheScratchFile() {
        val release = CountDownLatch(1)
        val connection = Script(
            stat = { RemoteStat(1_000_000, "x") },
            open = {
                RemoteStream(
                    object : InputStream() {
                        var sent = 0
                        override fun read(): Int = 0
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (sent < 1000) { sent += 1000; return 1000 }
                            release.await(60, TimeUnit.SECONDS) // blocks like a stalled socket until the connection is closed
                            throw IOException("closed")
                        }
                    },
                    0, null, "x",
                )
            },
        )
        val c = object : RemoteConnection by connection {
            override fun close() { connection.close(); release.countDown() }
        }
        cloudRig(backend = backend({ c })) {
            val added = engine.add(PhoneAddRequest("ftp://files.example/big.bin"))
            val run = scope.launch { engine.runUntilIdle() }
            awaitStatus(added.id, PhoneJobStatus.DOWNLOADING)
            delay(300)
            withTimeout(10_000) { engine.pause(added.id) }
            assertEquals(PhoneJobStatus.PAUSED, job(added.id).status)
            assertTrue(connection.closed)
            assertTrue("the partial copy stays for resume", storage.scratchFile(added.id).exists())
            run.join()
            engine.cancel(added.id)
            assertFalse("cancel removes the scratch file", storage.scratchFile(added.id).exists())
        }
    }

    private suspend fun CloudRig.awaitStatus(id: Long, status: PhoneJobStatus) =
        withTimeout(10_000) { while (job(id).status != status) delay(20) }
}
