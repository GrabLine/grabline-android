package dev.grabline.app.engine.work

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A boolean that survives restarts. */
interface OneTimeFlag {
    var done: Boolean
}

/**
 * The one-time battery tip: shown once, the first time the system pauses a download for Doze or a battery saver.
 * The Downloads screen collects [visible] and shows a banner with [settingsIntent].
 */
class BatteryTip(private val flag: OneTimeFlag, private val alreadyExempt: () -> Boolean) {
    private val state = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = state

    fun onSystemPaused() {
        if (flag.done || alreadyExempt()) return
        flag.done = true // one-time: never shown again, even if it is not dismissed
        state.value = true
    }

    fun dismiss() {
        state.value = false
    }

    companion object {
        fun create(context: Context): BatteryTip {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences("battery_tip", Context.MODE_PRIVATE)
            val flag = object : OneTimeFlag {
                override var done: Boolean
                    get() = prefs.getBoolean("shown", false)
                    set(v) = prefs.edit().putBoolean("shown", v).apply()
            }
            val power = app.getSystemService(PowerManager::class.java)
            return BatteryTip(flag) { power?.isIgnoringBatteryOptimizations(app.packageName) == true }
        }

        /** The system list where the user lets GrabLine run unrestricted. */
        fun settingsIntent(): Intent =
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
