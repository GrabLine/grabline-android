package dev.grabline.app

import android.app.Application
import dev.grabline.app.notify.NotificationChannels

class GrabLineApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createAll(this)
        container = AppContainer(this)
        container.start()
    }
}
