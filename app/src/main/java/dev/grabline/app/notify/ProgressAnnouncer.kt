package dev.grabline.app.notify

import android.content.Context
import android.text.format.Formatter
import dev.grabline.app.R
import java.util.Locale

/** Text templates; the Android implementation reads the plurals in strings_notify.xml. */
interface AnnounceFormatter {
    fun percent(percent: Int): String
    fun percentHoursLeft(percent: Int, hours: Int): String
    fun percentMinutesLeft(percent: Int, minutes: Int): String
    fun percentSecondsLeft(percent: Int, seconds: Int): String
    fun waiting(): String
    fun downloaded(size: String): String

    /** A byte count in the user's locale; JVM tests rely on this English fallback. */
    fun size(bytes: Long): String = ProgressAnnouncer.formatSize(bytes)
}

class AndroidAnnounceFormatter(context: Context) : AnnounceFormatter {
    private val appContext = context.applicationContext
    private val res = appContext.resources
    override fun percent(percent: Int): String = res.getString(R.string.announce_percent, percent)
    override fun percentHoursLeft(percent: Int, hours: Int): String =
        res.getQuantityString(R.plurals.announce_percent_hours_left, hours, percent, hours)
    override fun percentMinutesLeft(percent: Int, minutes: Int): String =
        res.getQuantityString(R.plurals.announce_percent_minutes_left, minutes, percent, minutes)
    override fun percentSecondsLeft(percent: Int, seconds: Int): String =
        res.getQuantityString(R.plurals.announce_percent_seconds_left, seconds, percent, seconds)
    override fun waiting(): String = res.getString(R.string.announce_waiting)
    override fun downloaded(size: String): String = res.getString(R.string.announce_downloaded, size)
    override fun size(bytes: Long): String = Formatter.formatShortFileSize(appContext, bytes)
}

/** Builds the progress text used by notifications and TalkBack. */
class ProgressAnnouncer(private val f: AnnounceFormatter) {

    /** [totalBytes] <= 0 means unknown; [bytesPerSecond] <= 0 means no current speed. */
    fun announce(downloadedBytes: Long, totalBytes: Long, bytesPerSecond: Long): String {
        if (totalBytes <= 0L) {
            return if (bytesPerSecond <= 0L && downloadedBytes <= 0L) {
                f.waiting()
            } else {
                f.downloaded(f.size(downloadedBytes))
            }
        }
        val done = downloadedBytes.coerceIn(0L, totalBytes)
        val pct = (done * 100 / totalBytes).toInt()
        if (bytesPerSecond <= 0L) return f.percent(pct)
        val seconds = (totalBytes - done + bytesPerSecond - 1) / bytesPerSecond
        return when {
            seconds >= 3600 -> f.percentHoursLeft(pct, (seconds / 3600).toInt())
            seconds >= 60 -> f.percentMinutesLeft(pct, (seconds / 60).toInt())
            else -> f.percentSecondsLeft(pct, seconds.toInt().coerceAtLeast(1))
        }
    }

    companion object {
        fun formatSize(bytes: Long): String {
            val kb = 1024.0
            val mb = kb * 1024
            val gb = mb * 1024
            return when {
                bytes >= gb -> String.format(Locale.ROOT, "%.1f GB", bytes / gb)
                bytes >= mb -> "${(bytes / mb).toLong()} MB"
                bytes >= kb -> "${(bytes / kb).toLong()} KB"
                else -> "$bytes B"
            }
        }
    }
}
