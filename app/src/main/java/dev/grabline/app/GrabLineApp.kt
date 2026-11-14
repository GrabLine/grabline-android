package dev.grabline.app

import android.app.Application
import dev.grabline.app.notify.NotificationChannels

class GrabLineApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createAll(this)
    }
}
