package dev.grabline.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineUnitTest {
    @Test fun uniqueNamesFollowTheNameParenExtPattern() {
        assertEquals("a.zip", uniqueName("a.zip", emptySet()))
        assertEquals("a (1).zip", uniqueName("a.zip", setOf("a.zip")))
        assertEquals("a (2).zip", uniqueName("a.zip", setOf("A.ZIP", "a (1).zip")))
        assertEquals("README (1)", uniqueName("README", setOf("README")))
    }

    @Test fun fileNamesAreSafe() {
        assertEquals("file name.pdf", fileNameFor(null, "https://h/p/file%20name.pdf?x=1"))
        assertEquals("download", fileNameFor(null, "https://h/"))
        assertEquals("a_b.txt", fileNameFor("a/b.txt", "https://h/x"))
        assertEquals("evil", fileNameFor("..evil", "https://h/x"))
        assertTrue(fileNameFor("x".repeat(300) + ".mp4", "https://h/").length <= 120)
    }

    @Test fun contentRangeParsing() {
        assertEquals(ContentRange(0, 99, 200), parseContentRange("bytes 0-99/200"))
        assertEquals(ContentRange(5, 9, null), parseContentRange("bytes 5-9/*"))
        assertNull(parseContentRange("bytes */200"))
        assertNull(parseContentRange("bytes 9-5/20"))
        assertNull(parseContentRange(null))
    }

    @Test fun sizesReadLikeTheSpecExample() {
        assertEquals("2.1 GB", formatSize((2.1 * 1024 * 1024 * 1024).toLong()))
        assertEquals("900 MB", formatSize(900L * 1024 * 1024))
        assertEquals("Needs 2.1 GB, 900 MB free", EngineText.needsSpace((2.1 * 1024 * 1024 * 1024).toLong(), 900L * 1024 * 1024))
    }

    @Test fun idleConnectionsStealTheTailOfTheBiggestRange() {
        val min = 1000L
        val plan = SegmentPlan(listOf(Seg(0, 0, 10_000, 0, 0), Seg(1, 10_000, 12_000, 10_000, 10_000)), min)
        val a = plan.acquire(true)!!
        val b = plan.acquire(true)!!
        assertTrue(a.owned && b.owned)
        // Everything is owned: the third worker steals half of what the biggest range has left.
        plan.claim(a, 2000)
        val stolen = plan.acquire(true)!!
        assertEquals(6000L, stolen.start)
        assertEquals(10_000L, stolen.end)
        assertEquals(6000L, a.end)
        assertNotNull(plan.acquire(true)) // the next steal splits another big range
    }

    @Test fun neverStealsWithoutRoomForTwoMinimumSegments() {
        val plan = SegmentPlan(listOf(Seg(0, 0, 1500, 0, 0)), 1000)
        plan.acquire(true)
        assertNull(plan.acquire(true))
    }

    @Test fun claimStopsAtTheRangeEndAndReleaseDropsUnwrittenBytes() {
        val plan = SegmentPlan(listOf(Seg(0, 0, 100, 0, 0)), 10)
        val s = plan.acquire(false)!!
        assertEquals(60, plan.claim(s, 60))
        plan.wrote(s, 40)
        assertEquals(40, plan.claim(s, 60))
        assertEquals(0, plan.claim(s, 10))
        plan.release(s)
        assertEquals(40L, s.pos) // 60 claimed, 40 written: the rest is fetched again
        assertEquals(40L, plan.totalDone())
    }

    @Test fun speedCapPacesBytes() = kotlinx.coroutines.runBlocking {
        var now = 1_000L
        var waited = 0L
        val bucket = TokenBucket({ now }) { ms -> waited += ms; now += ms }
        bucket.bytesPerSecond = 100
        repeat(3) { bucket.acquire(100) }
        assertTrue("waited $waited ms", waited in 1800..2200)
        bucket.bytesPerSecond = 0
        val before = waited
        bucket.acquire(1_000_000)
        assertEquals(before, waited)
    }
}
