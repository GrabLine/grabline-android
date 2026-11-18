package dev.grabline.app.engine.work

import android.app.Notification
import dev.grabline.app.AppContainer
import dev.grabline.app.notify.ActiveNotifications
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What every host (job or service) does: run the engine until idle while keeping the Active notification current. */
internal object DownloadRun {
    suspend fun run(container: AppContainer, active: ActiveNotifications, publish: (Notification) -> Unit) {
        val engine = container.phone
        RunState.engineActive = true
        try {
            coroutineScope {
                val watcher = launch { engine.jobs.collect { active.update(it, publish) } }
                do {
                    engine.runUntilIdle()
                    val more = engine.hasRunnableWork().first() // work that arrived while winding down
                    if (more) delay(1_000)
                } while (more)
                watcher.cancel()
            }
        } finally {
            RunState.engineActive = false
            active.clear()
        }
        // Work that arrived after the last check but before this run ended must not wait for the next app start.
        if (engine.hasRunnableWork().first()) container.scheduler.ensureRunning()
    }
}
