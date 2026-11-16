package dev.grabline.app.engine.cloud

import dev.grabline.app.engine.Misbehaving
import dev.grabline.app.engine.PhoneAddRequest
import dev.grabline.app.engine.PhoneJobStatus
import dev.grabline.app.engine.content
import dev.grabline.app.engine.ranged
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** WebDAV and S3 through the real engine against OkHttp's MockWebServer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HttpCloudEngineTest {
    private val payload = content(300 * 1024)

    private fun server(handler: Misbehaving): MockWebServer = MockWebServer().apply { dispatcher = handler; start() }

    // ----------------------------------------------------------------- WebDAV

    @Test fun webdavDownloadSendsTheSavedBasicLogin() = cloudRig {
        val log = Misbehaving { ranged(it, payload) }
        val s = server(log)
        try {
            store.save(CloudAccount(service = "webdav", host = "127.0.0.1", username = "qa"), AccountSecrets(password = "qa-password"))
            val job = download("webdav://127.0.0.1:${s.port}/dav/file.bin")
            assertEquals(job.error, PhoneJobStatus.COMPLETED, job.status)
            assertArrayEquals(payload, storage.published["file.bin"])
            assertEquals(Credentials.basic("qa", "qa-password"), log.log.first().getHeader("Authorization"))
            assertEquals("identity", log.log.first().getHeader("Accept-Encoding"))
        } finally { s.shutdown() }
    }

    @Test fun webdavSavedPortAndExplicitUrlPort() = cloudRig {
        val log = Misbehaving { MockResponse().setBody("test").setHeader("Content-Length", "4") }
        val s = server(log)
        try {
            store.save(CloudAccount(service = "webdav", host = "127.0.0.1", username = "qa", port = s.port), AccountSecrets(password = "p"))
            val viaSaved = download("webdav://127.0.0.1/file.bin")
            assertEquals(viaSaved.error, PhoneJobStatus.COMPLETED, viaSaved.status)
            assertEquals(1, log.log.size)
            // An explicit port in the link beats a wrong saved one.
            store.save(CloudAccount(service = "webdav", host = "127.0.0.1", username = "qa", port = 1), AccountSecrets(password = "p"))
            val explicit = download("webdav://127.0.0.1:${s.port}/other.bin")
            assertEquals(explicit.error, PhoneJobStatus.COMPLETED, explicit.status)
            assertEquals(2, log.log.size)
        } finally { s.shutdown() }
    }

    @Test fun webdavResumesWithARange() = cloudRig {
        val log = Misbehaving { ranged(it, payload) }
        val s = server(log)
        try {
            val job = download("webdav://127.0.0.1:${s.port}/f.bin") { added ->
                storage.scratchFile(added.id).writeBytes(payload.copyOf(100_000))
            }
            assertEquals(job.error, PhoneJobStatus.COMPLETED, job.status)
            assertArrayEquals(payload, storage.published["f.bin"])
            assertEquals("bytes=100000-", log.log.first().getHeader("Range"))
        } finally { s.shutdown() }
    }

    @Test fun webdavServerThatIgnoresRangeStartsAgainInsteadOfJoiningCopies() = cloudRig {
        val s = server(Misbehaving { ranged(it, payload, ranges = false) })
        try {
            val job = download("webdav://127.0.0.1:${s.port}/f.bin") { added ->
                storage.scratchFile(added.id).writeBytes(payload.copyOf(100_000))
            }
            assertEquals(job.error, PhoneJobStatus.COMPLETED, job.status)
            assertArrayEquals(payload, storage.published["f.bin"])
        } finally { s.shutdown() }
    }

    @Test fun webdavFullPartialIsFinishedByA416WithTheMatchingTotal() = cloudRig {
        val s = server(Misbehaving { ranged(it, payload) })
        try {
            val job = download("webdav://127.0.0.1:${s.port}/f.bin") { added ->
                storage.scratchFile(added.id).writeBytes(payload)
            }
            assertEquals(job.error, PhoneJobStatus.COMPLETED, job.status)
            assertArrayEquals(payload, storage.published["f.bin"])
        } finally { s.shutdown() }
    }

    @Test fun webdavShrunkFileOn416StartsAgain() = cloudRig {
        val shorter = payload.copyOf(60_000)
        val s = server(Misbehaving { ranged(it, shorter) })
        try {
            val job = download("webdav://127.0.0.1:${s.port}/f.bin") { added ->
                storage.scratchFile(added.id).writeBytes(payload.copyOf(100_000))
            }
            assertEquals(job.error, PhoneJobStatus.COMPLETED, job.status)
            assertArrayEquals(shorter, storage.published["f.bin"])
        } finally { s.shutdown() }
    }

    @Test fun webdavRefusedLoginAndMissingFile() = cloudRig {
        val s = server(Misbehaving { if (it.path!!.contains("nope")) MockResponse().setResponseCode(404) else MockResponse().setResponseCode(401) })
        try {
            assertEquals(CloudText.AUTH, download("webdav://u:p@127.0.0.1:${s.port}/f.bin").error)
            assertEquals(CloudText.NOT_FOUND, download("webdav://u:p@127.0.0.1:${s.port}/nope.bin").error)
        } finally { s.shutdown() }
    }

    // --------------------------------------------------------------------- S3

    private fun listing(vararg keys: Pair<String, Int>, truncated: Boolean = false, next: String? = null) =
        "<ListBucketResult><IsTruncated>$truncated</IsTruncated>" +
            keys.joinToString("") { "<Contents><Key>${it.first}</Key><Size>${it.second}</Size></Contents>" } +
            (next?.let { "<NextContinuationToken>$it</NextContinuationToken>" } ?: "") + "</ListBucketResult>"

    @Test fun s3DownloadIsSignedAndResumesWithARange() = cloudRig {
        val log = Misbehaving { r ->
            if (r.method == "HEAD") MockResponse().setHeader("Content-Length", payload.size).setHeader("ETag", "\"abc\"")
            else ranged(r, payload, etag = "\"abc\"")
        }
        val s = server(log)
        try {
            store.save(
                CloudAccount(service = "s3", host = "qa-bucket", username = "qa-key", endpoint = "http://127.0.0.1:${s.port}", region = "eu-west-1"),
                AccountSecrets(password = "qa-secret"),
            )
            val job = download("s3://qa-bucket/dir/object%20one.bin") { added ->
                storage.scratchFile(added.id).writeBytes(payload.copyOf(70_000))
                dao.update(dao.get(added.id)!!.copy(etag = "s3:${payload.size}:abc::"))
            }
            assertEquals(job.error, PhoneJobStatus.COMPLETED, job.status)
            assertArrayEquals(payload, storage.published["object one.bin"])
            val head = log.log.first()
            assertEquals("/qa-bucket/dir/object%20one.bin", head.path)
            assertTrue(head.getHeader("Authorization")!!.startsWith("AWS4-HMAC-SHA256 Credential=qa-key/"))
            assertTrue(head.getHeader("Authorization")!!.contains("/eu-west-1/s3/aws4_request"))
            assertEquals("bytes=70000-", log.log.last().getHeader("Range"))
            assertFalse(dao.get(job.id)!!.url.contains("qa-secret"))
        } finally { s.shutdown() }
    }

    @Test fun s3ChangedObjectStartsTheCopyAgain() = cloudRig {
        val log = Misbehaving { r ->
            if (r.method == "HEAD") MockResponse().setHeader("Content-Length", payload.size).setHeader("ETag", "\"new\"")
            else ranged(r, payload, etag = "\"new\"")
        }
        val s = server(log)
        try {
            store.save(CloudAccount(service = "s3", host = "b", username = "k", endpoint = "http://127.0.0.1:${s.port}"), AccountSecrets(password = "s"))
            val job = download("s3://b/o.bin") { added ->
                storage.scratchFile(added.id).writeBytes(ByteArray(70_000) { 9 })
                dao.update(dao.get(added.id)!!.copy(etag = "s3:${payload.size}:old::"))
            }
            assertEquals(job.error, PhoneJobStatus.COMPLETED, job.status)
            assertArrayEquals(payload, storage.published["o.bin"])
        } finally { s.shutdown() }
    }

    @Test fun s3FolderCollectsAllPagesAndSkipsDirectoryMarkers() = cloudRig {
        val log = Misbehaving { r ->
            val q = r.requestUrl!!
            assertEquals("2", q.queryParameter("list-type"))
            assertEquals("folder/", q.queryParameter("prefix"))
            if (q.queryParameter("continuation-token") == "second-page") {
                MockResponse().setBody(listing("folder/second.bin" to 2))
            } else {
                MockResponse().setBody(listing("folder/" to 0, "folder/first.bin" to 1, truncated = true, next = "second-page"))
            }
        }
        val s = server(log)
        try {
            store.save(CloudAccount(service = "s3", host = "qa-bucket", username = "k", endpoint = "http://127.0.0.1:${s.port}"), AccountSecrets(password = "s"))
            engine.add(PhoneAddRequest("s3://qa-bucket/folder/"))
            val rows = dao.all()
            assertEquals(listOf("first.bin", "second.bin"), rows.map { it.name })
            assertEquals(listOf(1L, 2L), rows.map { it.total })
            assertEquals(listOf("s3://qa-bucket/folder/first.bin", "s3://qa-bucket/folder/second.bin"), rows.map { it.url })
            assertEquals(2, log.log.size)
        } finally { s.shutdown() }
    }

    @Test fun anonymousS3RequestsAreNotSigned() = cloudRig {
        val log = Misbehaving { r -> if (r.method == "HEAD") MockResponse().setHeader("Content-Length", 4) else MockResponse().setBody("data") }
        val s = server(log)
        try {
            store.save(CloudAccount(service = "s3", host = "public", endpoint = "http://127.0.0.1:${s.port}"), null)
            val job = download("s3://public/o.txt")
            assertEquals(job.error, PhoneJobStatus.COMPLETED, job.status)
            assertEquals(null, log.log.first().getHeader("Authorization"))
        } finally { s.shutdown() }
    }

    @Test fun s3DottedBucketOnAwsUsesPathStyleWithoutAnEndpoint() {
        val seen = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            seen += chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("nf").body("".toResponseBody()).build()
        }.build()
        val dotted = CloudConnection("s3", "qa.bucket.name", 0, "k", "s", "", "", "", "", "/object.bin", "/object.bin", null)
        runCatching { S3Connection(dotted, client).stat() }
        val plain = CloudConnection("s3", "plainbucket", 0, "k", "s", "", "", "", "eu-west-1", "/dir/object.bin", "/dir/object.bin", null)
        runCatching { S3Connection(plain, client).stat() }
        assertEquals("https://s3.us-east-1.amazonaws.com/qa.bucket.name/object.bin", seen[0])
        assertEquals("https://plainbucket.s3.eu-west-1.amazonaws.com/dir/object.bin", seen[1])
        assertNotNull(seen)
    }

    @Test fun listingParserDecodesEntities() {
        val page = S3Connection.parseListing(listing("a&amp;b/c&#38;d.bin" to 5))
        assertEquals("a&b/c&d.bin", page.objects.single().first)
        assertEquals(5L, page.objects.single().second)
    }
}
