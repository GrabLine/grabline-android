package dev.grabline.app.engine.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.grabline.app.GrabLineApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** After a reboot or an app update, picks up unfinished jobs ("nothing restarts from zero"). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val container = (context.applicationContext as GrabLineApp).container
        val pending = goAsync()
        container.scope.launch {
            try {
                if (container.phone.hasRunnableWork().first()) container.scheduler.ensureRunning()
            } finally {
                pending.finish()
            }
        }
    }
}
