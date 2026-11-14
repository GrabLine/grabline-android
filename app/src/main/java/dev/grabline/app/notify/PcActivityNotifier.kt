package dev.grabline.app.notify

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.grabline.app.MainActivity
import dev.grabline.app.R

/** Raises "finished" / "failed" notifications for jobs running on a connected PC. */
class PcActivityNotifier(
    context: Context,
    /** The "Notify me about PC downloads" setting, read at post time. */
    private val enabled: () -> Boolean,
) {
    private val context = context.applicationContext

    fun jobFinished(pcId: String, pcName: String, jobId: String, jobName: String) {
        post(
            pcId, jobId, R.drawable.ic_notify_finished,
            context.getString(R.string.notify_pc_finished_title, pcName),
            context.getString(R.string.notify_pc_finished_text, jobName),
        )
    }

    fun jobFailed(pcId: String, pcName: String, jobId: String, jobName: String, reasonText: String) {
        post(
            pcId, jobId, R.drawable.ic_notify_failed,
            context.getString(R.string.notify_pc_failed_title, pcName),
            context.getString(R.string.notify_pc_failed_text, jobName, reasonText),
        )
    }

    private fun post(pcId: String, jobId: String, icon: Int, title: String, text: String) {
        if (!enabled() || !canPost()) return
        val id = notificationId(pcId, jobId)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_JOB, "$pcId:$jobId")
        }
        val pending = PendingIntent.getActivity(
            context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.PC_ACTIVITY)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .addAction(0, context.getString(R.string.notify_action_open_app), pending)
            .setAutoCancel(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call; nothing to do.
        }
    }

    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    companion object {
        const val EXTRA_OPEN_JOB = "openJob"

        /** Stable per (pcId, jobId); the same job replaces its own notification. */
        fun notificationId(pcId: String, jobId: String): Int = "pc:$pcId:$jobId".hashCode()
    }
}
