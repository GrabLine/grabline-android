package dev.grabline.app.engine

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** The engine against a server that misbehaves on purpose (SPEC "QA strategy"). Every success compares SHA-256. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadEngineTest {
    private val body = content(2 * 1024 * 1024)

    private fun Rig.assertPublished(job: PhoneJob, expected: ByteArray) {
        assertEquals(job.error, PhoneJobStatus.COMPLETED, job.status)
        assertEquals(sha256(expected), sha256(storage.published[job.name]!!))
        assertEquals(expected.size.toLong(), job.total)
        assertEquals("scratch is gone", 0, storage.scratchFiles().size)
        assertNotNull(job.fileUri)
    }

    @Test fun segmentsDownloadInParallelAndMatch() = rig { // QA-18, rules 1-2
        val s = serve(Misbehaving { ranged(it, body) })
        val job = download()
        assertPublished(job, body)
        val fetches = s.fetches()
        assertTrue("several connections were used", fetches.map { it.getHeader("Range") }.distinct().size > 1)
        assertTrue("identity encoding on every request", s.log.all { it.getHeader("Accept-Encoding") == "identity" })
        assertTrue("every fetch is a ranged one", fetches.all { it.getHeader("Range") != null })
        assertTrue("If-Range on every ranged fetch", fetches.all { it.getHeader("If-Range") == "\"v1\"" })
    }

    @Test fun serverWithoutRangesUsesOneConnectionAndSaysSo() = rig { // QA-22
        val s = serve(Misbehaving { ranged(it, body, ranges = false) })
        val job = download()
        assertPublished(job, body)
        assertEquals(EngineText.NO_RESUME, job.notice)
        assertEquals(1, s.fetches().size)
    }

    @Test fun redirectsAreFollowedForProbeAndSegments() = rig { // QA-23
        val s = serve(Misbehaving {
            if (it.path == "/a") MockResponse().setResponseCode(302).setHeader("Location", "/b/f.bin") else ranged(it, body)
        })
        val job = download("/a")
        assertPublished(job, body)
        assertTrue(s.log.any { it.path == "/b/f.bin" && it.getHeader("Range") != null })
    }

    @Test fun midBodyDropResumesFromTheCheckpointWithIfRange() = rig(EngineConfig(connectionsPerFile = 1, autoRetry = 5, onlyWifi = true)) { // QA-24, rules 3 and 6
        val n = AtomicInteger()
        val s = serve(Misbehaving { r ->
            val resp = ranged(r, body)
            if (!(r.getHeader("Range") == "bytes=0-0") && n.getAndIncrement() == 0) resp.dropMidBody() else resp
        })
        val job = download()
        assertPublished(job, body)
        val resumed = s.fetches().drop(1).first()
        val start = Regex("""bytes=(\d+)-""").find(resumed.getHeader("Range")!!)!!.groupValues[1].toLong()
        assertTrue("resumed after the bytes already written, not from zero ($start)", start > 0)
        assertEquals("\"v1\"", resumed.getHeader("If-Range"))
    }

    @Test fun unknownSizeUsesOneConnectionAndRestartsAfterADrop() = rig { // QA-25
        val n = AtomicInteger()
        val s = serve(Misbehaving { r ->
            if (r.getHeader("Range") == "bytes=0-0") {
                MockResponse().setResponseCode(200).setChunkedBody(Buffer().write(body), 8192)
            } else {
                val resp = MockResponse().setResponseCode(200).setChunkedBody(Buffer().write(body), 8192)
                if (n.getAndIncrement() == 0) resp.dropMidBody() else resp
            }
        })
        val job = download()
        assertPublished(job, body)
        assertEquals(EngineText.NO_RESUME, job.notice)
        assertTrue("the drop forced a second full request", s.fetches().size >= 2)
        assertTrue(s.fetches().all { it.getHeader("Range") == null })
    }

    @Test fun aBodyCompressedAnywayIsRefused() = rig { // QA-26, rule 1
        val gz = java.io.ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(body) } }.toByteArray()
        serve(Misbehaving { MockResponse().setHeader("Content-Encoding", "gzip").setBody(Buffer().write(gz)) })
        val job = download()
        assertEquals(PhoneJobStatus.FAILED, job.status)
        assertEquals(EngineText.COMPRESSED, job.error)
        assertTrue(storage.published.isEmpty())
        assertEquals(0, storage.scratchFiles().size)
    }

    @Test fun aWrongContentRangeIsNeverWritten() = rig { // QA-27, rule 2
        serve(Misbehaving { r ->
            if (r.getHeader("Range") == "bytes=0-0") ranged(r, body)
            else MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 7-${body.size - 1}/${body.size}")
                .setBody(Buffer().write(body, 0, 4096))
        })
        val job = download()
        assertEquals(PhoneJobStatus.FAILED, job.status)
        assertEquals(EngineText.WRONG_RANGE, job.error)
        assertTrue(storage.published.isEmpty())
    }

    @Test fun a416OnAKnownSizeMeansTheFileChangedAndStartsAgain() = rig(EngineConfig(connectionsPerFile = 1, autoRetry = 5, onlyWifi = true)) { // rule 4
        val n = AtomicInteger()
        serve(Misbehaving { r ->
            if (r.getHeader("Range") != "bytes=0-0" && n.getAndIncrement() == 0)
                MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */${body.size}")
            else ranged(r, body)
        })
        val job = download()
        assertPublished(job, body)
        assertEquals(EngineText.FILE_CHANGED, job.notice)
    }

    @Test fun pushbackFrom429HalvesTheConnectionsAndIsRemembered() = rig { // rule 5
        val n = AtomicInteger()
        serve(Misbehaving { r ->
            if (r.getHeader("Range") != "bytes=0-0" && n.getAndIncrement() < 2)
                MockResponse().setResponseCode(429).setHeader("Retry-After", "0")
            else ranged(r, body)
        })
        val job = download()
        assertPublished(job, body)
        val cap = dao.get(job.id)!!.connectionCap
        assertNotNull(cap)
        assertTrue("4 connections halved (got $cap)", cap!! in 1..2)
    }

    /**
     * Found on a real phone (proof.ovh.net): a server that rate-limits new requests answered most of the
     * connections with 429 and no Retry-After, and the download failed as "The connection kept dropping".
     * Here only one range request per 150 ms is let through; the rest get 429.
     */
    @Test fun aRateLimitingServerSlowsTheDownloadInsteadOfFailingIt() = rig(
        EngineConfig(connectionsPerFile = 8, downloadsAtOnce = 3, onlyWifi = true, autoRetry = 5),
    ) {
        val last = java.util.concurrent.atomic.AtomicLong(0)
        serve(Misbehaving { r ->
            val now = System.currentTimeMillis()
            val prev = last.get()
            if (now - prev >= 150 && last.compareAndSet(prev, now)) ranged(r, body).throttleBody(256 * 1024, 50, java.util.concurrent.TimeUnit.MILLISECONDS)
            else MockResponse().setResponseCode(429)
        })
        val job = download()
        assertEquals(job.error, PhoneJobStatus.COMPLETED, job.status)
        assertPublished(job, body)
    }

    @Test fun aServerThatNeverRelentsEndsWithThePushbackMessage() = rig {
        serve(Misbehaving { r -> if (r.getHeader("Range") == "bytes=0-0") ranged(r, body) else MockResponse().setResponseCode(429) })
        val job = download()
        assertEquals(PhoneJobStatus.FAILED, job.status)
        assertEquals(EngineText.PUSHBACK, job.error)
    }

    @Test fun a403AfterDataHalvesInsteadOfGivingUp() = rig(EngineConfig(connectionsPerFile = 2, autoRetry = 5, onlyWifi = true)) { // rule 5
        val n = AtomicInteger()
        serve(Misbehaving { r ->
            if (r.getHeader("Range") == "bytes=0-0") return@Misbehaving ranged(r, body)
            when (n.incrementAndGet()) {
                1 -> ranged(r, body).dropMidBody() // data flows, then the link breaks
                3 -> MockResponse().setResponseCode(403)
                else -> ranged(r, body)
            }
        })
        val job = download()
        assertPublished(job, body)
        assertEquals(1, dao.get(job.id)!!.connectionCap)
    }

    @Test fun a403BeforeAnyDataIsAPlainRefusal() = rig {
        serve(Misbehaving { MockResponse().setResponseCode(403) })
        val job = download()
        assertEquals(PhoneJobStatus.FAILED, job.status)
        assertEquals(EngineText.REFUSED, job.error)
    }

    @Test fun anEtagChangeMidDownloadRestartsAndTheFinalBytesAreTheNewFile() = rig { // rule 3
        val v1 = content(1_500_000, seed = 1)
        val v2 = content(1_200_000, seed = 2)
        val current = AtomicReference(v1 to "\"v1\"")
        val n = AtomicInteger()
        serve(Misbehaving { r ->
            if (r.getHeader("Range") != "bytes=0-0" && n.incrementAndGet() == 3) current.set(v2 to "\"v2\"")
            ranged(r, current.get().first, current.get().second)
        })
        val job = download()
        assertPublished(job, v2)
        assertEquals(EngineText.FILE_CHANGED, job.notice)
        assertNotEquals(sha256(v1), sha256(storage.published[job.name]!!))
    }

    @Test fun sameFilenameTwiceAtOnceGivesTwoFiles() = rig { // QA-31, rule 7
        val a = content(300_000, seed = 3)
        val b = content(400_000, seed = 4)
        serve(Misbehaving { r -> ranged(r, if (r.path!!.startsWith("/a/")) a else b, etag = "\"${r.path!!.take(2)}\"") })
        val j1 = engine.add(PhoneAddRequest(url("/a/file.bin")))
        val j2 = engine.add(PhoneAddRequest(url("/b/file.bin")))
        assertEquals("file.bin", j1.name)
        assertEquals("file (1).bin", j2.name)
        engine.runUntilIdle()
        assertPublished(job(j1.id), a)
        assertPublished(job(j2.id), b)
    }

    @Test fun anExistingNameBecomesNameParenOne() = rig { // rule 7
        storage.preexisting += "movie.mp4"
        val m = content(100_000)
        serve(Misbehaving { ranged(it, m) })
        val job = download("/movie.mp4")
        assertEquals("movie (1).mp4", job.name)
        assertPublished(job, m)
    }

    @Test fun notEnoughSpaceStopsBeforeAnyDataIsRequested() = rig { // rule 8
        storage.free = 1024L * 1024
        val big = content(3 * 1024 * 1024)
        val s = serve(Misbehaving { ranged(it, big) })
        val job = download()
        assertEquals(PhoneJobStatus.FAILED, job.status)
        assertEquals("Needs 3 MB, 1 MB free", job.error)
        assertTrue("only the probe ran", s.fetches().isEmpty())
        assertEquals(0, storage.scratchFiles().size)
    }

    @Test fun repeatedDropsWithNoProgressGiveUp() = rig(EngineConfig(connectionsPerFile = 1, autoRetry = 2, onlyWifi = true)) { // rule 6
        serve(Misbehaving { r ->
            if (r.getHeader("Range") == "bytes=0-0") ranged(r, body)
            else MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
        })
        val job = download()
        assertEquals(PhoneJobStatus.FAILED, job.status)
        assertEquals(EngineText.CONNECTION, job.error)
    }

    @Test fun losingTheNetworkMidDownloadWaitsInsteadOfFailing() = rig(EngineConfig(connectionsPerFile = 4, autoRetry = 0)) { // QA-19
        // On a real phone every connection drops at once, before Android reports the network gone;
        // none of those drops may count against the retries.
        val offline = java.util.concurrent.atomic.AtomicBoolean(false)
        val tripped = java.util.concurrent.atomic.AtomicBoolean(false)
        val big = content(3 * 1024 * 1024)
        serve(Misbehaving { r ->
            if (r.getHeader("Range") != "bytes=0-0" && !tripped.getAndSet(true)) {
                offline.set(true)
                // Android reports the loss a little after the connections have dropped.
                Thread { Thread.sleep(300); engine.setNetworkState(wifi = true, metered = false, connected = false) }.start()
            }
            if (offline.get()) MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST)
            else ranged(r, big)
        })
        val id = engine.add(PhoneAddRequest(url())).id
        engine.runUntilIdle()
        awaitUntil("waiting for network") { job(id).status == PhoneJobStatus.WAITING_NETWORK }
        assertNull(job(id).error)
        offline.set(false)
        engine.setNetworkState(wifi = true, metered = false, connected = true)
        awaitUntil("queued") { job(id).status == PhoneJobStatus.QUEUED }
        engine.runUntilIdle()
        assertPublished(job(id), big)
    }

    @Test fun pauseKeepsProgressAndResumeFinishesWithIfRange() = rig { // QA-24
        val big = content(3 * 1024 * 1024)
        val slow = java.util.concurrent.atomic.AtomicBoolean(true)
        val s = serve(Misbehaving { r ->
            val resp = ranged(r, big)
            if (slow.get() && resp.status.contains("206") && r.getHeader("Range") != "bytes=0-0") resp.throttleBody(64 * 1024, 100, TimeUnit.MILLISECONDS) else resp
        })
        val id = engine.add(PhoneAddRequest(url())).id
        val run = scope.async { engine.runUntilIdle() }
        awaitUntil("some bytes") { (dao.get(id)?.downloaded ?: 0) > 0 }
        engine.pause(id)
        run.await()
        val paused = job(id)
        assertEquals(PhoneJobStatus.PAUSED, paused.status)
        assertTrue(paused.downloaded in 1 until big.size)
        assertTrue(dao.segments(id).isNotEmpty())
        assertEquals(1, storage.scratchFiles().size)
        slow.set(false)
        val before = s.fetches().size
        engine.resume(id)
        engine.runUntilIdle()
        assertPublished(job(id), big)
        assertTrue(s.fetches().drop(before).all { it.getHeader("If-Range") == "\"v1\"" })
    }

    @Test fun cancellingAPausedJobRemovesItsScratchFiles() = rig { // rule 10
        val big = content(3 * 1024 * 1024)
        serve(Misbehaving { r ->
            val resp = ranged(r, big)
            if (resp.status.contains("206") && r.getHeader("Range") != "bytes=0-0") resp.throttleBody(64 * 1024, 100, TimeUnit.MILLISECONDS) else resp
        })
        val id = engine.add(PhoneAddRequest(url())).id
        val run = scope.async { engine.runUntilIdle() }
        awaitUntil("some bytes") { (dao.get(id)?.downloaded ?: 0) > 0 }
        engine.pause(id)
        run.await()
        assertEquals(1, storage.scratchFiles().size)
        engine.cancel(id)
        assertEquals(0, storage.scratchFiles().size)
        assertEquals(PhoneJobStatus.CANCELLED, job(id).status)
        assertTrue(dao.segments(id).isEmpty())
        assertTrue(storage.published.isEmpty())
    }

    @Test fun wifiOnlyHoldsTheJobUntilMobileDataIsAllowed() = rig { // QA-28
        val s = serve(Misbehaving { ranged(it, body) })
        engine.setNetworkState(wifi = false, metered = true, connected = true)
        val id = engine.add(PhoneAddRequest(url())).id
        assertEquals(PhoneJobStatus.WAITING_WIFI, job(id).status)
        engine.runUntilIdle()
        assertTrue("nothing was requested", s.log.isEmpty())
        engine.allowMobileData(id)
        engine.runUntilIdle()
        assertPublished(job(id), body)
    }

    @Test fun noNetworkAndNotChargingHoldTheJobThenReleaseIt() = rig { // QA-29
        settings.config = settings.config.copy(onlyCharging = true)
        serve(Misbehaving { ranged(it, body) })
        engine.setNetworkState(wifi = true, metered = false, connected = false)
        val id = engine.add(PhoneAddRequest(url())).id
        assertEquals(PhoneJobStatus.WAITING_NETWORK, job(id).status)
        engine.setNetworkState(wifi = true, metered = false, connected = true)
        awaitUntil("waiting for charger") { job(id).status == PhoneJobStatus.WAITING_CHARGING }
        engine.setCharging(true)
        awaitUntil("queued") { job(id).status == PhoneJobStatus.QUEUED }
        engine.runUntilIdle()
        assertPublished(job(id), body)
    }

    @Test fun aBadLinkFailsPlainlyWithoutNetwork() = rig {
        val job = engine.add(PhoneAddRequest("not a link"))
        assertEquals(PhoneJobStatus.FAILED, job.status)
        assertEquals(EngineText.BAD_LINK, job.error)
        assertNull(job.fileUri)
    }

    @Test fun retryAfterAFailureDownloadsAgain() = rig {
        val bad = AtomicInteger(1)
        serve(Misbehaving { r -> if (bad.get() == 1) MockResponse().setResponseCode(404) else ranged(r, body) })
        val failed = download()
        assertEquals(EngineText.NOT_FOUND, failed.error)
        bad.set(0)
        engine.retry(failed.id)
        engine.runUntilIdle()
        assertPublished(job(failed.id), body)
        assertNull(job(failed.id).error)
        delay(1)
    }
}
