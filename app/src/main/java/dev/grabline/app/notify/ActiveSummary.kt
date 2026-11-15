package dev.grabline.app.notify

import dev.grabline.app.engine.PhoneJob
import dev.grabline.app.engine.PhoneJobStatus

/** Words for the summary line; the Android side backs this with string resources. */
interface SummaryStrings {
    fun downloading(count: Int): String
    fun waitingNetwork(count: Int): String
    fun waitingWifi(count: Int): String
    fun waitingCharging(count: Int): String
    fun queued(count: Int): String
}

/** What the grouped "Active downloads" notification says about the jobs the engine still wants to run. */
data class ActiveSummary(
    val downloading: Int,
    val waitingNetwork: Int,
    val waitingWifi: Int,
    val waitingCharging: Int,
    val queued: Int,
    /** Overall progress over jobs with a known size, or null when nothing has one. */
    val percent: Int?,
) {
    val isEmpty get() = downloading + waitingNetwork + waitingWifi + waitingCharging + queued == 0

    private fun parts(s: SummaryStrings): List<String> = listOfNotNull(
        s.downloading(downloading).takeIf { downloading > 0 },
        s.waitingNetwork(waitingNetwork).takeIf { waitingNetwork > 0 },
        s.waitingWifi(waitingWifi).takeIf { waitingWifi > 0 },
        s.waitingCharging(waitingCharging).takeIf { waitingCharging > 0 },
        s.queued(queued).takeIf { queued > 0 },
    )

    /** First thing that matters: what is downloading, else what is holding the jobs. */
    fun title(s: SummaryStrings): String = parts(s).firstOrNull().orEmpty()

    /** The rest: other groups and the overall percentage. */
    fun text(s: SummaryStrings): String =
        (parts(s).drop(1) + listOfNotNull(percent?.let { "$it%" })).joinToString(" · ")

    companion object {
        fun of(jobs: List<PhoneJob>): ActiveSummary {
            fun n(vararg st: PhoneJobStatus) = jobs.count { it.status in st }
            val sized = jobs.filter { it.isLive() && (it.total ?: 0) > 0 }
            val total = sized.sumOf { it.total!! }
            val done = sized.sumOf { it.downloaded.coerceAtMost(it.total!!) }
            return ActiveSummary(
                downloading = n(PhoneJobStatus.DOWNLOADING),
                waitingNetwork = n(PhoneJobStatus.WAITING_NETWORK),
                waitingWifi = n(PhoneJobStatus.WAITING_WIFI),
                waitingCharging = n(PhoneJobStatus.WAITING_CHARGING),
                queued = n(PhoneJobStatus.QUEUED),
                percent = if (total > 0) (done * 100 / total).toInt() else null,
            )
        }

        private fun PhoneJob.isLive() = status in LIVE
        val LIVE = setOf(
            PhoneJobStatus.QUEUED, PhoneJobStatus.DOWNLOADING, PhoneJobStatus.WAITING_NETWORK,
            PhoneJobStatus.WAITING_WIFI, PhoneJobStatus.WAITING_CHARGING,
        )
    }
}

sealed interface JobEvent {
    val job: PhoneJob
    data class Finished(override val job: PhoneJob) : JobEvent
    data class Failed(override val job: PhoneJob) : JobEvent
}

/** Turns the engine's job list into "finished" and "failed" events, once per transition (never for jobs already done at start). */
class TransitionTracker {
    private var last: Map<Long, PhoneJobStatus>? = null

    @Synchronized
    fun diff(jobs: List<PhoneJob>): List<JobEvent> {
        val before = last
        last = jobs.associate { it.id to it.status }
        if (before == null) return emptyList()
        return jobs.mapNotNull { j ->
            val was = before[j.id] ?: return@mapNotNull null
            when {
                was == j.status -> null
                j.status == PhoneJobStatus.COMPLETED -> JobEvent.Finished(j)
                j.status == PhoneJobStatus.FAILED -> JobEvent.Failed(j)
                else -> null
            }
        }
    }
}
