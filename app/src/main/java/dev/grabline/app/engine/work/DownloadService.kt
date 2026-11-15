package dev.grabline.app.engine.work

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import dev.grabline.app.GrabLineApp
import dev.grabline.app.notify.ActiveNotifications
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** API 26-33: the same run as a dataSync foreground service. Stops itself when the engine is idle. */
class DownloadService : Service() {
    private var run: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (run?.isActive == true) return START_STICKY
        val container = (application as GrabLineApp).container
        val active = ActiveNotifications(this)
        try {
            ServiceCompat.startForeground(
                this, ActiveNotifications.SUMMARY_ID, active.summary(emptyList()), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (_: Exception) {
            // Not allowed to run in the foreground right now: hand over to the persisted job.
            stopSelf()
            container.scheduler.ensureRunning()
            return START_NOT_STICKY
        }
        RunState.serviceRunning = true
        run = container.scope.launch {
            try {
                DownloadRun.run(container, active) { ActiveNotifications.post(this@DownloadService, ActiveNotifications.SUMMARY_ID, it) }
            } finally {
                RunState.serviceRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    /** Android 15 caps dataSync: stop and carry on as a plain job (or a user-initiated one when visible). */
    override fun onTimeout(startId: Int, fgsType: Int) {
        run?.cancel()
        RunState.serviceRunning = false
        stopSelf()
        (application as GrabLineApp).container.scheduler.ensureRunning()
    }

    override fun onDestroy() {
        run?.cancel()
        RunState.serviceRunning = false
        super.onDestroy()
    }
}
