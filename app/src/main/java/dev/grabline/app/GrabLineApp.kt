package dev.grabline.app

import android.app.Application
import android.content.Context
import dev.grabline.app.notify.NotificationChannels
import dev.grabline.app.ui.settings.AppLocale

class GrabLineApp : Application() {
    lateinit var container: AppContainer
        private set

    /** Below Android 13 the chosen app language is applied here (see [AppLocale]). */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createAll(this)
        container = AppContainer(this)
        container.start()
    }
}
