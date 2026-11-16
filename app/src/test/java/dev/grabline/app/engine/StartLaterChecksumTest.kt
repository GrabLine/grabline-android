package dev.grabline.app.engine

import java.io.File
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartLaterChecksumTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(day: Int, hour: Int, minute: Int, second: Int = 0): Long =
        Calendar.getInstance(utc).apply { clear(); set(2026, Calendar.MARCH, day, hour, minute, second) }.timeInMillis

    @Test fun laterTodayStaysToday() {
        assertEquals(at(10, 18, 30), StartLater.nextOccurrence(18, 30, at(10, 9, 0), utc))
    }

    @Test fun aTimeThatAlreadyPassedMeansTomorrow() {
        assertEquals(at(11, 7, 15), StartLater.nextOccurrence(7, 15, at(10, 9, 0), utc))
    }

    @Test fun theExactMinuteNowMeansTomorrowNotNow() {
        assertEquals(at(11, 9, 0), StartLater.nextOccurrence(9, 0, at(10, 9, 0), utc))
        assertEquals(at(10, 9, 0), StartLater.nextOccurrence(9, 0, at(10, 8, 59, 59), utc))
    }

    @Test fun monthEndRollsOver() {
        val now = Calendar.getInstance(utc).apply { clear(); set(2026, Calendar.MARCH, 31, 23, 0) }.timeInMillis
        val expected = Calendar.getInstance(utc).apply { clear(); set(2026, Calendar.APRIL, 1, 6, 0) }.timeInMillis
        assertEquals(expected, StartLater.nextOccurrence(6, 0, now, utc))
    }

    @Test fun dueMeansNoWaitOrTimeReached() {
        assertTrue(StartLater.isDue(null, 100))
        assertTrue(StartLater.isDue(100, 100))
        assertFalse(StartLater.isDue(101, 100))
    }

    @Test fun delayNeverGoesNegative() {
        assertEquals(5_000, StartLater.delayMs(15_000, 10_000))
        assertEquals(0, StartLater.delayMs(10_000, 15_000))
    }

    @Test fun earliestPendingIgnoresThePastAndNothing() {
        assertEquals(300L, StartLater.earliestPending(listOf(500L, null, 100L, 300L), now = 200L))
        assertNull(StartLater.earliestPending(listOf(null, 100L), now = 200L))
        assertNull(StartLater.earliestPending(emptyList(), now = 0L))
    }

    @Test fun noticeIsRecognisedSoTheEngineCanClearIt() {
        val text = StartLater.notice(at(10, 18, 30), at(10, 9, 0), utc)
        assertTrue(text, StartLater.isNotice(text))
        assertTrue(StartLater.notice(at(11, 7, 0), at(10, 9, 0), utc).startsWith("Starts tomorrow"))
        assertFalse(StartLater.isNotice(EngineText.NO_RESUME))
    }

    private val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" // sha256("abc")

    @Test fun checksumTextIsNormalised() {
        assertEquals(hash, Checksum.normalize(hash))
        assertEquals(hash, Checksum.normalize("  ${hash.uppercase()}  "))
        assertEquals(hash, Checksum.normalize("sha256:$hash"))
        assertEquals(hash, Checksum.normalize("$hash  file.iso"))
        assertNull(Checksum.normalize("abc"))
        assertNull(Checksum.normalize(hash.dropLast(1)))
        assertNull(Checksum.normalize(hash.replaceRange(0, 1, "z")))
        assertNull(Checksum.normalize("  "))
    }

    @Test fun blankIsAcceptableButJunkIsNot() {
        assertTrue(Checksum.isAcceptable(""))
        assertTrue(Checksum.isAcceptable(null))
        assertTrue(Checksum.isAcceptable(hash))
        assertFalse(Checksum.isAcceptable("not a checksum"))
    }

    @Test fun verifyComparesTheFileContent() {
        val file = File.createTempFile("checksum", ".bin").apply { writeText("abc"); deleteOnExit() }
        assertEquals(hash, Checksum.sha256Hex(file))
        assertEquals(Checksum.VERIFIED, Checksum.verify(file, hash))
        assertEquals(Checksum.MISMATCH, Checksum.verify(file, "0".repeat(64)))
    }

    @Test fun noticesAreThePlainWords() {
        assertEquals("Verified", Checksum.noticeFor(Checksum.VERIFIED))
        assertEquals("Checksum didn't match", Checksum.noticeFor(Checksum.MISMATCH))
        assertNull(Checksum.noticeFor(null))
    }
}
