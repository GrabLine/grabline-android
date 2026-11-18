package dev.grabline.app.engine.work

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import dev.grabline.app.engine.StartLater

/** Process-wide flag the foreground service sets, so a second start is a no-op. */
internal object RunState {
    @Volatile var serviceRunning = false

    /** True while an engine loop (inside a job or the service) is running; false once it has finished. */
    @Volatile var engineActive = false
}

/** Starts the engine inside visible background work. Safe to call repeatedly. */
class DownloadScheduler(context: Context, private val appVisible: () -> Boolean) {
    private val app = context.applicationContext
    private val jobs: JobScheduler = app.getSystemService(JobScheduler::class.java)

    fun ensureRunning() = start(RunPolicy.choose(Build.VERSION.SDK_INT, appVisible()))

    private fun start(mode: RunMode) {
        when (mode) {
            RunMode.USER_INITIATED_JOB -> if (!scheduleUserInitiated()) start(RunMode.BACKGROUND_JOB)
            RunMode.FOREGROUND_SERVICE -> if (!startService()) start(RunMode.BACKGROUND_JOB)
            RunMode.BACKGROUND_JOB -> scheduleBackground()
        }
    }

    private fun scheduleUserInitiated(): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        if (RunPolicy.alreadyCovered(jobs.getPendingJob(JOB_USER) != null, RunState.engineActive)) return true
        val info = JobInfo.Builder(JOB_USER, component())
            .setUserInitiated(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .build()
        val ok = runCatching { jobs.schedule(info) == JobScheduler.RESULT_SUCCESS }.getOrDefault(false)
        if (ok) jobs.cancel(JOB_BACKGROUND) // promote: the visible job takes over from a silent one
        return ok
    }

    private fun startService(): Boolean {
        if (RunState.serviceRunning) return true
        val ok = runCatching { ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java)) }.isSuccess
        if (ok) jobs.cancel(JOB_BACKGROUND)
        return ok
    }

    private fun scheduleBackground() {
        if (RunState.serviceRunning) return
        val pending = jobs.getPendingJob(JOB_USER) != null || jobs.getPendingJob(JOB_BACKGROUND) != null
        if (RunPolicy.alreadyCovered(pending, RunState.engineActive)) return
        val info = JobInfo.Builder(JOB_BACKGROUND, component())
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true) // survives a reboot (RECEIVE_BOOT_COMPLETED)
            .build()
        jobs.schedule(info)
    }

    /** Wakes the app at [startAt] for a "Start later" job. Replaces an earlier wake-up; survives a reboot. */
    fun scheduleAt(startAt: Long, now: Long = System.currentTimeMillis()) {
        val info = JobInfo.Builder(JOB_LATER, component())
            .setMinimumLatency(StartLater.delayMs(startAt, now).coerceAtLeast(1))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        runCatching { jobs.schedule(info) }
    }

    private fun component() = ComponentName(app, DownloadJobService::class.java)

    companion object {
        const val JOB_USER = 7101
        const val JOB_BACKGROUND = 7102
        const val JOB_LATER = 7103
    }
}
