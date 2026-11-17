package dev.grabline.app.notify

import android.content.Context
import androidx.core.app.NotificationCompat
import dev.grabline.app.R
import dev.grabline.app.engine.PhoneJob
import dev.grabline.app.ui.common.localizeEngineMessage
import dev.grabline.app.ui.details.FileActions

/** Raises "Finished" (Open, Share) and "Failed" (Retry, Details) notifications as phone jobs change state. */
class PhoneJobNotifier(context: Context) {
    private val context = context.applicationContext
    private val tracker = TransitionTracker()

    fun onJobs(jobs: List<PhoneJob>) {
        for (event in tracker.diff(jobs)) when (event) {
            is JobEvent.Finished -> finished(event.job)
            is JobEvent.Failed -> failed(event.job)
        }
    }

    private fun finished(job: PhoneJob) {
        val b = NotificationCompat.Builder(context, NotificationChannels.FINISHED)
            .setSmallIcon(R.drawable.ic_notify_finished)
            .setContentTitle(context.getString(R.string.notify_finished_title))
            .setContentText(job.name)
            .setContentIntent(ActiveNotifications.openAppIntent(context, job.id))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
        FileActions.openIntent(context, job.fileUri)?.let {
            b.addAction(0, context.getString(R.string.notify_action_open), ActionReceiverIntents.activity(context, job.id, 1, it))
        }
        FileActions.shareIntent(context, job.fileUri, job.name)?.let {
            b.addAction(0, context.getString(R.string.notify_action_share), ActionReceiverIntents.activity(context, job.id, 2, it))
        }
        ActiveNotifications.post(context, resultId(job.id), b.build())
    }

    private fun failed(job: PhoneJob) {
        val reason = job.error?.let { localizeEngineMessage(context, it) } ?: context.getString(R.string.notify_failed_default)
        val b = NotificationCompat.Builder(context, NotificationChannels.FAILED)
            .setSmallIcon(R.drawable.ic_notify_failed)
            .setContentTitle(context.getString(R.string.notify_failed_title))
            .setContentText(context.getString(R.string.notify_failed_text, job.name, reason))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notify_failed_text, job.name, reason)))
            .setContentIntent(ActiveNotifications.openAppIntent(context, job.id))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .addAction(0, context.getString(R.string.notify_action_retry), ActionReceiverIntents.retry(context, job.id))
            .addAction(0, context.getString(R.string.notify_action_details), ActiveNotifications.openAppIntent(context, job.id))
        ActiveNotifications.post(context, resultId(job.id), b.build())
    }

    companion object {
        private const val RESULT_BASE = 6_000_000
        fun resultId(jobId: Long) = RESULT_BASE + (jobId % 1_000_000).toInt()
    }
}
