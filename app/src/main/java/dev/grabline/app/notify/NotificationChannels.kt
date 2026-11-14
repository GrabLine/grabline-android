package dev.grabline.app.notify

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import dev.grabline.app.R

object NotificationChannels {
    const val ACTIVE_DOWNLOADS = "active_downloads"
    const val FINISHED = "finished"
    const val FAILED = "failed"
    const val PC_ACTIVITY = "pc_activity"
    const val TRANSFERS = "transfers"

    const val GROUP_PHONE = "group_phone"
    const val GROUP_PC = "group_pc"

    /** Creates the five channels. Safe to call on every launch. */
    fun createAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP_PHONE, context.getString(R.string.notify_group_phone)),
        )
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP_PC, context.getString(R.string.notify_group_pc)),
        )
        manager.createNotificationChannels(
            listOf(
                channel(
                    context, ACTIVE_DOWNLOADS, R.string.notify_channel_active_name,
                    R.string.notify_channel_active_desc, NotificationManager.IMPORTANCE_LOW, GROUP_PHONE,
                ),
                channel(
                    context, FINISHED, R.string.notify_channel_finished_name,
                    R.string.notify_channel_finished_desc, NotificationManager.IMPORTANCE_DEFAULT, GROUP_PHONE,
                ),
                channel(
                    context, FAILED, R.string.notify_channel_failed_name,
                    R.string.notify_channel_failed_desc, NotificationManager.IMPORTANCE_DEFAULT, GROUP_PHONE,
                ),
                channel(
                    context, PC_ACTIVITY, R.string.notify_channel_pc_name,
                    R.string.notify_channel_pc_desc, NotificationManager.IMPORTANCE_LOW, GROUP_PC,
                ),
                channel(
                    context, TRANSFERS, R.string.notify_channel_transfers_name,
                    R.string.notify_channel_transfers_desc, NotificationManager.IMPORTANCE_LOW, GROUP_PC,
                ),
            ),
        )
    }

    private fun channel(
        context: Context,
        id: String,
        name: Int,
        desc: Int,
        importance: Int,
        group: String,
    ) = NotificationChannel(id, context.getString(name), importance).apply {
        description = context.getString(desc)
        this.group = group
        if (importance < NotificationManager.IMPORTANCE_DEFAULT) {
            setSound(null, null)
            enableVibration(false)
        }
    }
}
