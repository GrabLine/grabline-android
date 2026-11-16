package dev.grabline.app.engine

import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Start later" and the SHA-256 check through the real engine and a real (in-memory) database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartLaterEngineTest {
    private val body = content(300 * 1024)

    @Test fun aJobWaitsForItsTimeAndThenRuns() = rig {
        val s = serve(Misbehaving { ranged(it, body) })
        val added = engine.add(PhoneAddRequest(url(), startAt = System.currentTimeMillis() + 3_600_000))
        assertTrue(added.notice.orEmpty(), StartLater.isNotice(added.notice))
        engine.runUntilIdle()
        assertEquals("not started before its time", PhoneJobStatus.QUEUED, job(added.id).status)
        assertEquals(0, s.log.size)

        // The time arrives (the stored time moves into the past, as it would an hour later).
        dao.update(dao.get(added.id)!!.copy(startAt = System.currentTimeMillis() - 1))
        engine.runUntilIdle()
        val done = job(added.id)
        assertEquals(done.error, PhoneJobStatus.COMPLETED, done.status)
        assertEquals(sha256(body), sha256(storage.published[done.name]!!))
        assertNull("the wait and its notice are gone", dao.get(added.id)!!.startAt)
        assertTrue(!StartLater.isNotice(done.notice))
    }

    @Test fun otherJobsRunWhileOneWaits() = rig {
        serve(Misbehaving { ranged(it, body) })
        val later = engine.add(PhoneAddRequest(url("/later.bin"), startAt = System.currentTimeMillis() + 3_600_000))
        val now = engine.add(PhoneAddRequest(url("/now.bin")))
        engine.runUntilIdle()
        assertEquals(PhoneJobStatus.COMPLETED, job(now.id).status)
        assertEquals(PhoneJobStatus.QUEUED, job(later.id).status)
        assertEquals(dao.get(later.id)!!.startAt, engine.nextStartAt().first())
    }

    @Test fun matchingChecksumIsVerified() = rig {
        serve(Misbehaving { ranged(it, body) })
        val added = engine.add(PhoneAddRequest(url(), sha256 = sha256(body)))
        engine.runUntilIdle()
        val done = job(added.id)
        assertEquals(PhoneJobStatus.COMPLETED, done.status)
        assertEquals(Checksum.VERIFIED, done.checksum)
        assertEquals("Verified", done.notice)
    }

    @Test fun wrongChecksumWarnsButKeepsTheFile() = rig {
        serve(Misbehaving { ranged(it, body) })
        val added = engine.add(PhoneAddRequest(url(), sha256 = "0".repeat(64)))
        engine.runUntilIdle()
        val done = job(added.id)
        assertEquals("the file is kept", PhoneJobStatus.COMPLETED, done.status)
        assertEquals(sha256(body), sha256(storage.published[done.name]!!))
        assertEquals(Checksum.MISMATCH, done.checksum)
        assertEquals("Checksum didn't match", done.notice)
    }

    @Test fun noChecksumMeansNoCheck() = rig {
        serve(Misbehaving { ranged(it, body) })
        val done = download()
        assertNull(done.checksum)
    }
}
