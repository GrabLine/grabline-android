package dev.grabline.app.ui.downloads

import android.content.Context
import android.os.SystemClock
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.grabline.app.R
import dev.grabline.app.notify.AndroidAnnounceFormatter
import dev.grabline.app.notify.ProgressAnnouncer
import kotlinx.coroutines.delay

class AndroidStatusStrings(private val context: Context) : StatusStrings {
    private fun s(id: Int) = context.getString(id)
    override fun queued() = s(R.string.status_queued)
    override fun downloading() = s(R.string.status_downloading)
    override fun paused() = s(R.string.status_paused)
    override fun done() = s(R.string.status_done)
    override fun failed(error: String?) =
        if (error == null) s(R.string.status_failed_plain) else context.getString(R.string.status_failed, error)
    override fun cancelled() = s(R.string.status_cancelled)
    override fun unknown() = s(R.string.status_unknown)
}

fun sizeText(context: Context, bytes: Long): String = Formatter.formatFileSize(context, bytes)

fun deviceName(context: Context, device: Device): String = when (device) {
    Device.Phone -> context.getString(R.string.badge_phone)
    is Device.Pc -> device.name
}

fun lastSeenText(context: Context, row: DownloadRow, now: Long = System.currentTimeMillis()): String {
    val seen = row.lastSeenAt ?: return context.getString(R.string.pc_unreachable)
    val rel = DateUtils.getRelativeTimeSpanString(seen, now, DateUtils.MINUTE_IN_MILLIS)
    return context.getString(R.string.last_seen, rel)
}

fun speedText(context: Context, speed: Long): String =
    context.getString(R.string.per_second, Formatter.formatFileSize(context, speed))

fun timeLeftText(context: Context, seconds: Long): String =
    context.getString(R.string.time_left, DateUtils.formatElapsedTime(seconds))

/** The spoken progress ("45 percent, 2 minutes left"), only while a download runs. */
fun progressSpeech(context: Context, row: DownloadRow): String? =
    if (row.status == RowStatus.Downloading) {
        ProgressAnnouncer(AndroidAnnounceFormatter(context)).announce(row.downloaded, row.total ?: 0L, row.speed)
    } else null

/** One merged TalkBack description for a row. */
fun rowDescription(context: Context, row: DownloadRow): String = buildList {
    add(row.name)
    add(context.getString(R.string.badge_on_device, deviceName(context, row.device)))
    add(statusText(row, AndroidStatusStrings(context)))
    progressSpeech(context, row)?.let { add(it) }
    if (row.offline) add(lastSeenText(context, row))
}.joinToString(", ")

@Composable
fun actionLabel(action: RowAction): String = stringResource(
    when (action) {
        RowAction.Pause -> R.string.action_pause
        RowAction.Resume -> R.string.action_resume
        RowAction.Cancel -> R.string.action_cancel
        RowAction.Retry -> R.string.action_retry
        RowAction.Remove -> R.string.action_remove
    },
)

fun actionIcon(action: RowAction): ImageVector = when (action) {
    RowAction.Pause -> DownloadIcons.Pause
    RowAction.Resume -> DownloadIcons.Play
    RowAction.Cancel -> DownloadIcons.Close
    RowAction.Retry -> DownloadIcons.Retry
    RowAction.Remove -> DownloadIcons.Delete
}

/**
 * Follows [text] but changes at most every [intervalMillis], so a live region
 * does not announce every second. A new [immediateKey] (such as the status)
 * goes through at once.
 */
@Composable
fun rememberThrottled(text: String, immediateKey: Any?, intervalMillis: Long = 10_000L): String {
    var shown by remember { mutableStateOf(text) }
    var shownKey by remember { mutableStateOf(immediateKey) }
    var shownAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(text, immediateKey) {
        if (immediateKey != shownKey) {
            shown = text; shownKey = immediateKey; shownAt = SystemClock.elapsedRealtime()
            return@LaunchedEffect
        }
        val wait = intervalMillis - (SystemClock.elapsedRealtime() - shownAt)
        if (wait > 0) delay(wait)
        shown = text
        shownAt = SystemClock.elapsedRealtime()
    }
    return shown
}

/** Phone icon or the PC's name, always with text. */
@Composable
fun DeviceBadge(device: Device, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val text = deviceName(context, device)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            if (device is Device.Phone) DownloadIcons.Phone else DownloadIcons.Pc,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Determinate when the size is known, otherwise indeterminate with the bytes counted next to it. */
@Composable
fun RowProgress(row: DownloadRow, modifier: Modifier = Modifier) {
    val p = row.progress
    val animate = row.status == RowStatus.Downloading || row.status == RowStatus.Queued
    when {
        p != null -> LinearProgressIndicator(progress = { p }, modifier = modifier)
        animate && row.status == RowStatus.Downloading -> LinearProgressIndicator(modifier = modifier)
        else -> LinearProgressIndicator(progress = { 0f }, modifier = modifier)
    }
}

@Composable
fun SpacerW(w: Int) = Spacer(Modifier.width(w.dp))
