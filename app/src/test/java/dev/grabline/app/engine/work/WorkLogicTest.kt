package dev.grabline.app.engine.work

import android.app.job.JobParameters
import dev.grabline.app.engine.PhoneJob
import dev.grabline.app.engine.PhoneJobStatus
import dev.grabline.app.engine.PhoneJobStatus.*
import dev.grabline.app.notify.ActiveSummary
import dev.grabline.app.notify.JobEvent
import dev.grabline.app.notify.ProgressThrottle
import dev.grabline.app.notify.SummaryStrings
import dev.grabline.app.notify.TransitionTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkLogicTest {
    private fun job(id: Long, status: PhoneJobStatus, done: Long = 0, total: Long? = null) = PhoneJob(
        id = id, url = "https://x/$id", name = "f$id", status = status, downloaded = done, total = total, speed = 0, etaSeconds = null,
        connections = 0, notice = null, error = null, fileUri = null, createdAt = 0, finishedAt = null,
    )

    private val words = object : SummaryStrings {
        override fun downloading(count: Int) = "Downloading $count"
        override fun waitingNetwork(count: Int) = "$count waiting for network"
        override fun waitingWifi(count: Int) = "$count waiting for Wi-Fi"
        override fun waitingCharging(count: Int) = "$count waiting for charger"
        override fun queued(count: Int) = "$count waiting"
    }

    // Constraint mapping
    @Test fun noInternetIsOffline() = assertEquals(NetworkState.Offline, NetworkState.fromCapabilities(false, true, true))
    @Test fun wifiUnmetered() = assertEquals(NetworkState(true, wifi = true, metered = false), NetworkState.fromCapabilities(true, true, true))
    @Test fun mobileData() = assertEquals(NetworkState(true, wifi = false, metered = true), NetworkState.fromCapabilities(true, false, false))
    @Test fun meteredWifiHotspot() = assertEquals(NetworkState(true, wifi = true, metered = true), NetworkState.fromCapabilities(true, true, false))

    // Hosting
    @Test fun visibleAndroid14UsesUserInitiatedJob() = assertEquals(RunMode.USER_INITIATED_JOB, RunPolicy.choose(34, true))
    @Test fun visibleAndroid13UsesForegroundService() = assertEquals(RunMode.FOREGROUND_SERVICE, RunPolicy.choose(33, true))
    @Test fun visibleAndroid8UsesForegroundService() = assertEquals(RunMode.FOREGROUND_SERVICE, RunPolicy.choose(26, true))
    @Test fun hiddenUsesPlainJob() {
        assertEquals(RunMode.BACKGROUND_JOB, RunPolicy.choose(35, false))
        assertEquals(RunMode.BACKGROUND_JOB, RunPolicy.choose(28, false))
    }
    @Test fun batteryPauseReasons() {
        assertTrue(RunPolicy.isBatteryPause(JobParameters.STOP_REASON_DEVICE_STATE))
        assertTrue(RunPolicy.isBatteryPause(JobParameters.STOP_REASON_APP_STANDBY))
        assertFalse(RunPolicy.isBatteryPause(JobParameters.STOP_REASON_CANCELLED_BY_APP))
    }

    // Battery tip is one-time
    @Test fun batteryTipShowsOnce() {
        val flag = object : OneTimeFlag { override var done = false }
        val tip = BatteryTip(flag) { false }
        tip.onSystemPaused()
        assertTrue(tip.visible.value)
        tip.dismiss()
        tip.onSystemPaused()
        assertFalse(tip.visible.value)
        assertFalse(BatteryTip(object : OneTimeFlag { override var done = true }) { false }.also { it.onSystemPaused() }.visible.value)
        assertFalse(BatteryTip(object : OneTimeFlag { override var done = false }) { true }.also { it.onSystemPaused() }.visible.value)
    }

    // Summary text
    @Test fun summaryLine() {
        val s = ActiveSummary.of(
            listOf(job(1, DOWNLOADING, 50, 100), job(2, DOWNLOADING, 0, 100), job(3, WAITING_WIFI), job(4, COMPLETED, 9, 9)),
        )
        assertEquals("Downloading 2", s.title(words))
        assertEquals("1 waiting for Wi-Fi · 25%", s.text(words))
    }
    @Test fun allWaitingNetwork() {
        val s = ActiveSummary.of(listOf(job(1, WAITING_NETWORK), job(2, WAITING_NETWORK)))
        assertEquals("2 waiting for network", s.title(words))
        assertEquals("", s.text(words))
    }
    @Test fun noJobsIsEmpty() = assertTrue(ActiveSummary.of(listOf(job(1, PAUSED), job(2, FAILED))).isEmpty)

    // Progress at most once a second; changed shape always passes
    @Test fun progressThrottled() {
        var now = 0L
        val t = ProgressThrottle(1000) { now }
        assertTrue(t.shouldUpdate("active"))
        now = 999
        assertFalse(t.shouldUpdate("active"))
        assertTrue(t.shouldUpdate("active", isFinal = true))
        now = 2000
        assertTrue(t.shouldUpdate("active"))
    }

    // Finished and Failed fire once per transition
    @Test fun transitionsNotifyOnce() {
        val t = TransitionTracker()
        assertTrue(t.diff(listOf(job(1, COMPLETED), job(2, DOWNLOADING))).isEmpty()) // baseline
        assertTrue(t.diff(listOf(job(1, COMPLETED), job(2, DOWNLOADING, 5, 10))).isEmpty())
        val done = t.diff(listOf(job(1, COMPLETED), job(2, COMPLETED, 10, 10)))
        assertEquals(listOf<Long>(2), done.map { it.job.id })
        assertTrue(done.single() is JobEvent.Finished)
        assertTrue(t.diff(listOf(job(1, COMPLETED), job(2, COMPLETED, 10, 10))).isEmpty())
        assertTrue(t.diff(listOf(job(1, FAILED), job(2, COMPLETED))).single() is JobEvent.Failed)
    }
}
