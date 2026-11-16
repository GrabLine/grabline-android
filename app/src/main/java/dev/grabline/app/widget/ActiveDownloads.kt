package dev.grabline.app.widget

import dev.grabline.app.data.PcJobWithPc
import dev.grabline.app.engine.PhoneEngine
import dev.grabline.app.engine.PhoneJob
import dev.grabline.app.remote.PcRemote
import dev.grabline.app.remote.connection.PcLinkState
import dev.grabline.app.ui.downloads.RowStatus
import dev.grabline.app.ui.downloads.phoneRow
import dev.grabline.app.ui.downloads.toRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/** One active download as the widget and the tile see it. [percent] is null while the size is unknown. */
data class ActiveItem(
    val key: String,
    val name: String,
    val percent: Int?,
    val createdAt: Long,
    /** The PC it runs on, or null for this phone. */
    val pcName: String? = null,
)

/** Which downloads count as active, which three the widget shows, and the Quick Settings tile label. Pure, so it is unit tested. */
object ActiveDownloads {
    const val WIDGET_ROWS = 3

    /** The widget updates at most this often while the app is closed (SPEC: every 30 seconds). */
    const val CLOSED_INTERVAL_MS = 30_000L
    /** While the app is open the widget may follow progress closely. */
    const val OPEN_INTERVAL_MS = 1_000L

    fun updateIntervalMs(appOpen: Boolean): Long = if (appOpen) OPEN_INTERVAL_MS else CLOSED_INTERVAL_MS

    /** Running, queued or held by a rule; a paused download is not active. */
    fun isActive(status: RowStatus): Boolean = when (status) {
        RowStatus.Queued, RowStatus.Downloading, RowStatus.WaitingNetwork, RowStatus.WaitingWifi, RowStatus.WaitingCharging -> true
        else -> false
    }

    fun fromPhone(job: PhoneJob): ActiveItem? {
        val row = phoneRow(job)
        if (!isActive(row.status)) return null
        return ActiveItem(row.key, row.name, row.progress?.let { (it * 100).toInt() }, job.createdAt)
    }

    /** A PC's cached job; an unreachable PC's rows are stale, so they do not count. */
    fun fromPc(item: PcJobWithPc, link: PcLinkState?): ActiveItem? {
        val row = toRow(item, link)
        if (row.offline || !isActive(row.status)) return null
        return ActiveItem(row.key, row.name, row.progress?.let { (it * 100).toInt() }, item.job.createdAt ?: item.job.updatedAt, item.pcName)
    }

    /** The newest [max] active downloads across every device. */
    fun newest(items: List<ActiveItem>, max: Int = WIDGET_ROWS): List<ActiveItem> =
        items.sortedWith(compareByDescending<ActiveItem> { it.createdAt }.thenBy { it.key }).take(max)

    fun count(items: List<ActiveItem>): Int = items.size

    /** Every active download on the phone and on each reachable PC. */
    fun flow(phone: PhoneEngine?, remote: PcRemote): Flow<List<ActiveItem>> =
        combine(phone?.jobs ?: flowOf(emptyList()), remote.jobs, remote.linkStates) { own, pcJobs, links ->
            own.mapNotNull(::fromPhone) + pcJobs.mapNotNull { fromPc(it, links[it.job.pcId]) }
        }
}

/** The Quick Settings tile's label: the number of active downloads, or an invitation to add one. */
object TileLabel {
    fun text(count: Int, active: (Int) -> String, idle: String): String = if (count > 0) active(count) else idle
}
