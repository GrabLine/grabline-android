package dev.grabline.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifyTest {
    private val english = object : AnnounceFormatter {
        private fun p(n: Int, w: String) = if (n == 1) w else "${w}s"
        override fun percent(percent: Int) = "$percent percent"
        override fun percentHoursLeft(percent: Int, hours: Int) =
            "$percent percent, $hours ${p(hours, "hour")} left"
        override fun percentMinutesLeft(percent: Int, minutes: Int) =
            "$percent percent, $minutes ${p(minutes, "minute")} left"
        override fun percentSecondsLeft(percent: Int, seconds: Int) =
            "$percent percent, $seconds ${p(seconds, "second")} left"
        override fun waiting() = "Waiting"
        override fun downloaded(size: String) = "$size downloaded"
    }
    private val a = ProgressAnnouncer(english)
    private val mb = 1024L * 1024

    @Test fun minutesLeft() = assertEquals("45 percent, 2 minutes left", a.announce(45 * mb, 100 * mb, 55 * mb / 120))
    @Test fun noSpeedGivesPercentOnly() = assertEquals("45 percent", a.announce(45, 100, 0))
    @Test fun unknownTotalNoSpeedWaiting() = assertEquals("Waiting", a.announce(0, -1, 0))
    @Test fun unknownTotalShowsSize() = assertEquals("120 MB downloaded", a.announce(120 * mb, 0, 1000))
    @Test fun singularSecond() = assertEquals("50 percent, 1 second left", a.announce(50, 100, 50))
    @Test fun hoursLeft() = assertEquals("1 percent, 2 hours left", a.announce(1 * mb, 100 * mb, 14_000))

    @Test fun throttleOncePerSecond() {
        var now = 0L
        val t = ProgressThrottle(1000) { now }
        assertTrue(t.shouldUpdate("a"))
        now = 500
        assertFalse(t.shouldUpdate("a"))
        assertTrue(t.shouldUpdate("b"))
        now = 1000
        assertTrue(t.shouldUpdate("a"))
    }

    @Test fun throttleFinalAlwaysPasses() {
        var now = 0L
        val t = ProgressThrottle(1000) { now }
        assertTrue(t.shouldUpdate("a"))
        now = 10
        assertTrue(t.shouldUpdate("a", isFinal = true))
    }

    @Test fun notificationIdStable() {
        assertEquals(PcActivityNotifier.notificationId("pc1", "j1"), PcActivityNotifier.notificationId("pc1", "j1"))
        assertNotEquals(PcActivityNotifier.notificationId("pc1", "j1"), PcActivityNotifier.notificationId("pc1", "j2"))
        assertNotEquals(PcActivityNotifier.notificationId("pc1", "j1"), PcActivityNotifier.notificationId("pc2", "j1"))
    }
}
