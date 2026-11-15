package dev.grabline.app.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.grabline.app.GrabLineApp
import kotlinx.coroutines.launch

/** Builds the PendingIntents for notification buttons that talk to the engine. */
internal object ActionReceiverIntents {
    fun pauseAll(context: Context) = broadcast(context, NotificationActionReceiver.PAUSE_ALL, -1L)
    fun cancel(context: Context, jobId: Long) = broadcast(context, NotificationActionReceiver.CANCEL, jobId)
    fun retry(context: Context, jobId: Long) = broadcast(context, NotificationActionReceiver.RETRY, jobId)

    /** Opens or shares: an activity intent that carries its own URI grant. */
    fun activity(context: Context, jobId: Long, slot: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, (jobId.hashCode() * 8) + slot, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun broadcast(context: Context, action: String, jobId: Long): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).setAction(action).putExtra(NotificationActionReceiver.EXTRA_JOB, jobId)
        return PendingIntent.getBroadcast(context, action.hashCode() xor jobId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/** Pause all, Cancel and Retry from the notifications. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as GrabLineApp).container
        val id = intent.getLongExtra(EXTRA_JOB, -1L)
        val pending = goAsync()
        container.scope.launch {
            try {
                when (intent.action) {
                    PAUSE_ALL -> container.phone.pauseAll()
                    CANCEL -> container.phone.cancel(id)
                    RETRY -> {
                        container.phone.retry(id)
                        ActiveNotifications.cancel(context, PhoneJobNotifier.resultId(id))
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val PAUSE_ALL = "dev.grabline.app.action.PAUSE_ALL"
        const val CANCEL = "dev.grabline.app.action.CANCEL"
        const val RETRY = "dev.grabline.app.action.RETRY"
        const val EXTRA_JOB = "jobId"
    }
}
