package dev.grabline.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import dev.grabline.app.GrabLineApp
import dev.grabline.app.R
import dev.grabline.app.remote.connection.PcLinkState
import dev.grabline.app.ui.add.QuickAddActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The last list the app pushed, so a widget that is added or resized shows something at once. */
internal object WidgetState {
    @Volatile var items: List<ActiveItem> = emptyList()
}

/** The home-screen widget: the 3 newest active downloads, Paste link and Pause all. */
class DownloadsWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = WidgetRenderer.render(context, WidgetState.items)
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
    }
}

internal object WidgetRenderer {
    private val ROWS = listOf(
        Triple(R.id.widget_row1, R.id.widget_name1, R.id.widget_bar1),
        Triple(R.id.widget_row2, R.id.widget_name2, R.id.widget_bar2),
        Triple(R.id.widget_row3, R.id.widget_name3, R.id.widget_bar3),
    )

    fun render(context: Context, all: List<ActiveItem>): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_downloads)
        val shown = ActiveDownloads.newest(all)
        views.setViewVisibility(R.id.widget_empty, if (shown.isEmpty()) View.VISIBLE else View.GONE)
        ROWS.forEachIndexed { i, (row, name, bar) ->
            val item = shown.getOrNull(i)
            views.setViewVisibility(row, if (item == null) View.GONE else View.VISIBLE)
            if (item != null) {
                val label = item.pcName?.let { "${item.name} ($it)" } ?: item.name
                views.setTextViewText(
                    name,
                    if (item.percent != null) context.getString(R.string.widget_row_percent, label, item.percent)
                    else context.getString(R.string.widget_row_unknown, label),
                )
                views.setProgressBar(bar, 100, item.percent ?: 0, item.percent == null)
            }
        }
        val paste = PendingIntent.getActivity(
            context, 0, QuickAddActivity.intent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pause = PendingIntent.getBroadcast(
            context, 1, Intent(context, WidgetActionReceiver::class.java).setAction(WidgetActionReceiver.PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.widget_paste, paste)
        views.setOnClickPendingIntent(R.id.widget_pause_all, pause)
        return views
    }

    /** Redraws every placed widget. A no-op when none is on a home screen. */
    fun push(context: Context, items: List<ActiveItem>) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, DownloadsWidgetProvider::class.java))
        if (ids.isEmpty()) return
        manager.updateAppWidget(ids, render(context, items))
    }
}

/** Pause all from the widget: this phone and every PC that is reachable now. Not exported: only the widget's own button reaches it. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PAUSE_ALL) return
        val container = (context.applicationContext as GrabLineApp).container
        val pending = goAsync()
        container.scope.launch {
            try {
                container.phone.pauseAll()
                val links = container.remote.linkStates.value
                container.remote.pcs.first().filter { it.removedAt == null && links[it.pcId] is PcLinkState.Online }
                    .forEach { runCatching { container.remote.global(it.pcId, "pauseAll") } }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val PAUSE_ALL = "dev.grabline.app.widget.PAUSE_ALL"
    }
}
