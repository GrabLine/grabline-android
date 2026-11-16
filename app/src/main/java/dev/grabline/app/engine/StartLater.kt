package dev.grabline.app.engine

import java.io.File
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/** "Start later": pure time maths, so the engine, the scheduler and the Add sheet agree and tests need no clock. */
object StartLater {
    /** The next moment (strictly after [now]) the wall clock reads [hour]:[minute]; today if still ahead, else tomorrow. */
    fun nextOccurrence(hour: Int, minute: Int, now: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    /** A job with no start time, or one whose time has come, may run. */
    fun isDue(startAt: Long?, now: Long): Boolean = startAt == null || startAt <= now

    /** Minimum latency to give the system scheduler so it wakes the app at [startAt]. */
    fun delayMs(startAt: Long, now: Long): Long = (startAt - now).coerceAtLeast(0)

    /** The earliest start time that is still in the future, or null when nothing is waiting. */
    fun earliestPending(startTimes: Collection<Long?>, now: Long): Long? = startTimes.filterNotNull().filter { it > now }.minOrNull()

    /** Plain-words notice for a waiting job ("Starts at 18:30"). */
    fun notice(startAt: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val format = DateFormat.getTimeInstance(DateFormat.SHORT).apply { timeZone = zone }
        val day = Calendar.getInstance(zone).apply { timeInMillis = now }.get(Calendar.DAY_OF_YEAR) !=
            Calendar.getInstance(zone).apply { timeInMillis = startAt }.get(Calendar.DAY_OF_YEAR)
        return if (day) "Starts tomorrow at ${format.format(Date(startAt))}" else "Starts at ${format.format(Date(startAt))}"
    }

    /** True for the notice [notice] writes, so the engine can clear it when the job starts. */
    fun isNotice(text: String?): Boolean = text != null && text.startsWith("Starts ")
}

/** SHA-256 check of a finished file against a checksum the user pasted. Advisory: a mismatch warns, never deletes. */
object Checksum {
    const val VERIFIED = "verified"
    const val MISMATCH = "mismatch"

    const val NOTICE_VERIFIED = "Verified"
    const val NOTICE_MISMATCH = "Checksum didn't match"
    const val INVALID = "That isn't a SHA-256 checksum. It has 64 characters, 0-9 and a-f"

    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /**
     * The lower-case 64-digit hex of a pasted checksum, or null when it is not one. Accepts
     * surrounding spaces, a "sha256:" prefix and the "hash  filename" form that sha256sum prints.
     */
    fun normalize(text: String?): String? {
        val t = text?.trim()?.lowercase().orEmpty()
        if (t.isEmpty()) return null
        val token = t.removePrefix("sha256:").trim().split(Regex("\\s+")).firstOrNull().orEmpty().removePrefix("sha256:")
        return token.takeIf { HEX64.matches(it) }
    }

    /** Nothing pasted is fine; something pasted must be a checksum. */
    fun isAcceptable(text: String?): Boolean = text.isNullOrBlank() || normalize(text) != null

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** [VERIFIED] or [MISMATCH] for a file against an already-normalised checksum. */
    fun verify(file: File, expected: String): String = if (sha256Hex(file).equals(expected, ignoreCase = true)) VERIFIED else MISMATCH

    fun noticeFor(state: String?): String? = when (state) {
        VERIFIED -> NOTICE_VERIFIED
        MISMATCH -> NOTICE_MISMATCH
        else -> null
    }
}
