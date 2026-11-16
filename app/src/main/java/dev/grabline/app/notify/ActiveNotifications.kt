package dev.grabline.app.notify

import android.Manifest
import android.app.Notification
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
import dev.grabline.app.engine.PhoneJob
import dev.grabline.app.engine.PhoneJobStatus

/**
 * The "Active downloads" notification: one ongoing summary (the one the job or service shows) plus one child per
 * running job with its own progress and Cancel. Progress is updated at most once a second.
 */
class ActiveNotifications(context: Context, private val throttle: ProgressThrottle = ProgressThrottle()) {
    private val context = context.applicationContext
    private val strings = ResourceSummaryStrings(this.context)
    private var lastShape: String? = null
    private val children = HashSet<Int>()

    /** The summary notification for [jobs] (the empty list gives the "starting" one). */
    fun summary(jobs: List<PhoneJob>): Notification {
        val s = ActiveSummary.of(jobs)
        val title = if (s.isEmpty) context.getString(R.string.notify_active_starting) else s.title(strings)
        val b = NotificationCompat.Builder(context, NotificationChannels.ACTIVE_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notify_download)
            .setContentTitle(title)
            .setContentText(s.text(strings).ifEmpty { null })
            .setContentIntent(openApp(null))
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.notify_action_pause_all), ActionReceiverIntents.pauseAll(context))
        s.percent?.let { b.setProgress(100, it, false) } ?: b.setProgress(0, 0, !s.isEmpty)
        return b.build()
    }

    /** Pushes the current state, at most once a second unless the set of jobs or their states changed. */
    fun update(jobs: List<PhoneJob>, publishSummary: (Notification) -> Unit) {
        val live = jobs.filter { it.status in ActiveSummary.LIVE }
        val shape = live.joinToString(",") { "${it.id}:${it.status}" }
        val changed = shape != lastShape
        lastShape = shape
        if (!throttle.shouldUpdate("active", isFinal = changed)) return
        publishSummary(summary(live))
        val running = live.filter { it.status == PhoneJobStatus.DOWNLOADING }.take(MAX_CHILDREN)
        val ids = running.map { childId(it.id) }.toSet()
        (children - ids).forEach { cancel(context, it) }
        children.clear()
        children.addAll(ids)
        running.forEach { post(context, childId(it.id), child(it)) }
    }

    /** Removes the per-job notifications (the host removes the summary). */
    fun clear() {
        children.forEach { cancel(context, it) }
        children.clear()
        throttle.forget("active")
        lastShape = null
    }

    private fun child(job: PhoneJob): Notification {
        val total = job.total ?: 0L
        // A copy from a PC uses its own channel ("Transfers": silent, with Cancel).
        val channel = if (job.isTransfer) NotificationChannels.TRANSFERS else NotificationChannels.ACTIVE_DOWNLOADS
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notify_download)
            .setContentTitle(job.name)
            .setGroup(GROUP)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(job.id))
            .addAction(0, context.getString(R.string.notify_action_cancel), ActionReceiverIntents.cancel(context, job.id))
        if (total > 0) {
            val pct = (job.downloaded * 100 / total).toInt().coerceIn(0, 100)
            b.setProgress(100, pct, false).setContentText("$pct%")
        } else {
            b.setProgress(0, 0, true)
        }
        return b.build()
    }

    private fun openApp(jobId: Long?) = openAppIntent(context, jobId)

    companion object {
        const val SUMMARY_ID = 4001
        const val MAX_CHILDREN = 5
        private const val GROUP = "grabline_active"
        private const val CHILD_BASE = 5_000_000

        fun childId(jobId: Long) = CHILD_BASE + (jobId % 1_000_000).toInt()

        fun canPost(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return false
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

        /** Posts when notifications are allowed; denied notifications never stop a download (QA-32). */
        fun post(context: Context, id: Int, notification: Notification) {
            if (!canPost(context)) return
            try {
                @Suppress("MissingPermission")
                NotificationManagerCompat.from(context).notify(id, notification)
            } catch (_: SecurityException) {
                // Permission revoked between the check and the call.
            }
        }

        fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

        /** Opens the app, at a phone job's details when [jobId] is set (see [EXTRA_OPEN_PHONE_JOB]). */
        fun openAppIntent(context: Context, jobId: Long?): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (jobId != null) putExtra(EXTRA_OPEN_PHONE_JOB, jobId)
            }
            val code = (jobId ?: -1L).hashCode() + 31
            return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        /** Intent extra (Long) set when a phone notification should open that job's details. */
        const val EXTRA_OPEN_PHONE_JOB = "openPhoneJob"
    }
}

private class ResourceSummaryStrings(private val context: Context) : SummaryStrings {
    private val r = context.resources
    override fun downloading(count: Int) = r.getQuantityString(R.plurals.notify_active_downloading, count, count)
    override fun waitingNetwork(count: Int) = context.getString(R.string.notify_active_waiting_network, count)
    override fun waitingWifi(count: Int) = context.getString(R.string.notify_active_waiting_wifi, count)
    override fun waitingCharging(count: Int) = context.getString(R.string.notify_active_waiting_charging, count)
    override fun queued(count: Int) = context.getString(R.string.notify_active_queued, count)
}
