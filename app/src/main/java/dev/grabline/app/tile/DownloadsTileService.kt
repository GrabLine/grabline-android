package dev.grabline.app.tile

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.grabline.app.GrabLineApp
import dev.grabline.app.R
import dev.grabline.app.ui.add.QuickAddActivity
import dev.grabline.app.widget.ActiveDownloads
import dev.grabline.app.widget.TileLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The Quick Settings tile. Its label is the number of active downloads. A tap opens the small Add sheet with the copied link
 * (Android 10 and later only let an app on screen read the clipboard, so the tile cannot send it silently); a long press opens the app.
 */
class DownloadsTileService : TileService() {
    private var scope: CoroutineScope? = null
    private var watching: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val container = (application as GrabLineApp).container
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
        watching = s.launch {
            container.activeDownloads.map { ActiveDownloads.count(it) }.collect { render(it) }
        }
    }

    override fun onStopListening() {
        watching?.cancel()
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    private fun render(count: Int) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notify_download)
        tile.label = TileLabel.text(count, { resources.getQuantityString(R.plurals.tile_active, it, it) }, getString(R.string.tile_label))
        tile.state = if (count > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (count > 0) null else getString(R.string.tile_idle)
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = QuickAddActivity.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
