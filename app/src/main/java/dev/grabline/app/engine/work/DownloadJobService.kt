package dev.grabline.app.engine.work

import android.app.Notification
import android.app.NotificationManager
import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import dev.grabline.app.GrabLineApp
import dev.grabline.app.notify.ActiveNotifications
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Runs the engine as a user-initiated data transfer job (API 34+, visible) or a plain persisted job (resume path). */
class DownloadJobService : JobService() {
    private var run: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as GrabLineApp).container
        val active = ActiveNotifications(this)
        val visible = Build.VERSION.SDK_INT >= 34 && params.isUserInitiatedJob
        val manager = getSystemService(NotificationManager::class.java)
        val publish: (Notification) -> Unit = { n ->
            if (visible) {
                setNotification(params, ActiveNotifications.SUMMARY_ID, n, JOB_END_NOTIFICATION_POLICY_REMOVE)
            } else {
                ActiveNotifications.post(this, ActiveNotifications.SUMMARY_ID, n)
            }
        }
        publish(active.summary(emptyList())) // must exist within seconds of a user-initiated start
        run = container.scope.launch { DownloadRun.run(container, active, publish) }.also { job ->
            job.invokeOnCompletion { cause ->
                if (!visible) manager?.cancel(ActiveNotifications.SUMMARY_ID)
                if (cause == null) jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        run?.cancel()
        if (Build.VERSION.SDK_INT >= 31 && RunPolicy.isBatteryPause(params.stopReason)) {
            (application as GrabLineApp).container.batteryTip.onSystemPaused()
        }
        return true // reschedule: the engine reset its jobs to queued
    }
}
