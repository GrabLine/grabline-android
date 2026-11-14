package dev.grabline.app.notify

/** Allows at most one update per interval per key; final states always pass. */
class ProgressThrottle(
    private val intervalMs: Long = 1_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val last = HashMap<String, Long>()

    @Synchronized
    fun shouldUpdate(key: String, isFinal: Boolean = false): Boolean {
        val now = clock()
        if (isFinal) {
            last.remove(key)
            return true
        }
        val prev = last[key]
        if (prev != null && now - prev < intervalMs) return false
        last[key] = now
        return true
    }

    @Synchronized
    fun forget(key: String) {
        last.remove(key)
    }
}
