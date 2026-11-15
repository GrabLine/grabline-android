package dev.grabline.app.engine.work

import android.app.job.JobParameters

/** The connection as the engine needs it: see DownloadEngine.setNetworkState. */
data class NetworkState(val connected: Boolean, val wifi: Boolean, val metered: Boolean) {
    companion object {
        val Offline = NetworkState(connected = false, wifi = false, metered = false)

        /** Maps the facts of the default network. Ethernet counts as Wi-Fi: both are "not the phone's data plan". */
        fun fromCapabilities(hasInternet: Boolean, wifiOrEthernet: Boolean, notMetered: Boolean): NetworkState =
            if (!hasInternet) Offline else NetworkState(connected = true, wifi = wifiOrEthernet, metered = !notMetered)
    }
}

/** How a run of the engine is hosted (SPEC "Android limits to design around"). */
enum class RunMode { USER_INITIATED_JOB, FOREGROUND_SERVICE, BACKGROUND_JOB }

object RunPolicy {
    /**
     * Android 14+ gets a user-initiated data transfer job (no 6 hour dataSync cap on 15); 8 to 13 a dataSync
     * foreground service. Both may only start while the app is visible, so otherwise (boot, a system retry,
     * a woken process) a plain persisted job resumes the work and is upgraded when the app comes back.
     */
    fun choose(sdk: Int, appVisible: Boolean): RunMode = when {
        !appVisible -> RunMode.BACKGROUND_JOB
        sdk >= 34 -> RunMode.USER_INITIATED_JOB
        else -> RunMode.FOREGROUND_SERVICE
    }

    /** Stop reasons that mean the system paused us for Doze, standby or a battery saver. */
    fun isBatteryPause(stopReason: Int): Boolean = when (stopReason) {
        JobParameters.STOP_REASON_DEVICE_STATE,
        JobParameters.STOP_REASON_QUOTA,
        JobParameters.STOP_REASON_BACKGROUND_RESTRICTION,
        JobParameters.STOP_REASON_APP_STANDBY,
        -> true
        else -> false
    }
}
