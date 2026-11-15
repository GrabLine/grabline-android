package dev.grabline.app.ui.downloads

import dev.grabline.app.data.PcJobWithPc
import dev.grabline.app.engine.PhoneJob
import dev.grabline.app.engine.PhoneJobStatus
import dev.grabline.app.remote.connection.PcLinkState
import dev.grabline.app.remote.protocol.JobStatus

/** Where a download runs. Phone rows arrive in Phase 2. */
sealed interface Device {
    data object Phone : Device
    data class Pc(val pcId: String, val name: String) : Device
}

enum class RowStatus { Queued, Downloading, Paused, WaitingNetwork, WaitingWifi, WaitingCharging, Done, Failed, Cancelled, Unknown }

/** What a user can ask for; [wire] is the `control` action. */
enum class RowAction(val wire: String) {
    Pause("pause"), Resume("resume"), Cancel("cancel"), Retry("retry"), Remove("remove"),

    /** Phone only: "Download now anyway" for a job held back by Wi-Fi only. */
    AllowMobileData("allowMobileData");

    /** Cancel and remove act at once with a 5-second Undo. */
    val undoable: Boolean get() = this == Cancel || this == Remove
}

data class DownloadRow(
    val key: String,
    val device: Device,
    val jobId: Long,
    val name: String,
    val url: String,
    val status: RowStatus,
    val downloaded: Long = 0,
    val total: Long? = null,
    val speed: Long = 0,
    val etaSeconds: Long? = null,
    val error: String? = null,
    val queueId: Long? = null,
    /** The PC this row lives on cannot be reached right now: row greyed. */
    val offline: Boolean = false,
    val lastSeenAt: Long? = null,
    /** Plain-words notice shown under a phone row. */
    val notice: String? = null,
    /** Connections in use (phone jobs). */
    val connections: Int = 0,
    /** content:// URI of a finished phone file, for Open and Share. */
    val fileUri: String? = null,
) {
    val isPhone: Boolean get() = device is Device.Phone
    val waiting: Boolean
        get() = status == RowStatus.WaitingNetwork || status == RowStatus.WaitingWifi || status == RowStatus.WaitingCharging

    /** Null when the size is unknown (indeterminate bar). */
    val progress: Float?
        get() = if (status == RowStatus.Done) 1f
        else total?.takeIf { it > 0 }?.let { (downloaded.toFloat() / it).coerceIn(0f, 1f) }

    val timeLeftSeconds: Long?
        get() {
            if (status != RowStatus.Downloading) return null
            etaSeconds?.takeIf { it >= 0 }?.let { return it }
            val t = total ?: return null
            return if (speed > 0 && t > downloaded) (t - downloaded + speed - 1) / speed else null
        }

    val pcId: String? get() = (device as? Device.Pc)?.pcId

    /** Actions the PC accepts from this status (PROTOCOL.md `control`); none while the PC is unreachable. */
    val actions: List<RowAction>
        get() = if (offline) emptyList() else when (status) {
            RowStatus.WaitingWifi -> listOf(RowAction.AllowMobileData, RowAction.Pause, RowAction.Cancel, RowAction.Remove)
            RowStatus.Queued, RowStatus.Downloading, RowStatus.WaitingNetwork, RowStatus.WaitingCharging ->
                listOf(RowAction.Pause, RowAction.Cancel, RowAction.Remove)
            RowStatus.Paused -> listOf(RowAction.Resume, RowAction.Cancel, RowAction.Remove)
            RowStatus.Failed, RowStatus.Cancelled -> listOf(RowAction.Retry, RowAction.Remove)
            RowStatus.Done -> listOf(RowAction.Remove)
            RowStatus.Unknown -> emptyList()
        }

    /** Swipe start-to-end: pause, resume or retry. */
    val swipeStartAction: RowAction? get() = actions.firstOrNull { it == RowAction.Pause || it == RowAction.Resume || it == RowAction.Retry }

    /** Swipe end-to-start: cancel, or remove once there is nothing to cancel. */
    val swipeEndAction: RowAction?
        get() = if (RowAction.Cancel in actions) RowAction.Cancel else if (RowAction.Remove in actions) RowAction.Remove else null
}

fun rowKey(pcId: String, jobId: Long) = "pc:$pcId:$jobId"

/** Stand-in "PC id" that routes and saved selections use for the phone itself. */
const val PHONE_ID = "phone"

fun phoneRowKey(jobId: Long) = "phone:$jobId"

/** The list row key for a (device id, job id) pair as used by details routes and the two-pane selection. */
fun detailsRowKey(deviceId: String, jobId: Long) = if (deviceId == PHONE_ID) phoneRowKey(jobId) else rowKey(deviceId, jobId)

fun phoneStatusOf(s: PhoneJobStatus): RowStatus = when (s) {
    PhoneJobStatus.QUEUED -> RowStatus.Queued
    PhoneJobStatus.DOWNLOADING -> RowStatus.Downloading
    PhoneJobStatus.PAUSED -> RowStatus.Paused
    PhoneJobStatus.WAITING_NETWORK -> RowStatus.WaitingNetwork
    PhoneJobStatus.WAITING_WIFI -> RowStatus.WaitingWifi
    PhoneJobStatus.WAITING_CHARGING -> RowStatus.WaitingCharging
    PhoneJobStatus.COMPLETED -> RowStatus.Done
    PhoneJobStatus.FAILED -> RowStatus.Failed
    PhoneJobStatus.CANCELLED -> RowStatus.Cancelled
}

fun phoneRow(j: PhoneJob) = DownloadRow(
    key = phoneRowKey(j.id),
    device = Device.Phone,
    jobId = j.id,
    name = j.name.ifBlank { j.url },
    url = j.url,
    status = phoneStatusOf(j.status),
    downloaded = j.downloaded,
    total = j.total,
    speed = j.speed,
    etaSeconds = j.etaSeconds,
    error = j.error,
    notice = j.notice?.takeIf { it.isNotBlank() },
    connections = j.connections,
    fileUri = j.fileUri,
)

fun statusOf(wire: String): RowStatus = when (JobStatus.fromWire(wire)) {
    JobStatus.QUEUED -> RowStatus.Queued
    JobStatus.DOWNLOADING -> RowStatus.Downloading
    JobStatus.PAUSED -> RowStatus.Paused
    JobStatus.COMPLETED -> RowStatus.Done
    JobStatus.FAILED -> RowStatus.Failed
    JobStatus.CANCELLED -> RowStatus.Cancelled
    JobStatus.UNKNOWN -> RowStatus.Unknown
}

private fun unreachable(state: PcLinkState?) =
    state is PcLinkState.Offline || state is PcLinkState.IdentityChanged || state is PcLinkState.PairingRemoved

fun toRow(item: PcJobWithPc, link: PcLinkState?): DownloadRow {
    val j = item.job
    val offline = unreachable(link)
    val seen = (link as? PcLinkState.Offline)?.lastSeenAt ?: item.pcLastSeenAt
    return DownloadRow(
        key = rowKey(j.pcId, j.jobId),
        device = Device.Pc(j.pcId, item.pcName),
        jobId = j.jobId,
        name = j.name.ifBlank { j.url },
        url = j.url,
        status = statusOf(j.status),
        downloaded = j.downloaded,
        total = j.total,
        speed = j.speed ?: 0,
        etaSeconds = j.etaSeconds,
        error = j.error,
        queueId = j.queueId?.toLong(),
        offline = offline,
        lastSeenAt = if (offline) seen else null,
    )
}

sealed interface DeviceFilter {
    data object Phone : DeviceFilter
    data class Pc(val pcId: String) : DeviceFilter

    fun encode(): String = when (this) { Phone -> "phone"; is Pc -> "pc:$pcId" }

    companion object {
        fun decode(s: String): DeviceFilter? = when {
            s == "phone" -> Phone
            s.startsWith("pc:") -> Pc(s.removePrefix("pc:"))
            else -> null
        }
    }
}

enum class StateFilter {
    Active, Done, Failed;

    fun matches(status: RowStatus): Boolean = when (this) {
        Active -> status == RowStatus.Queued || status == RowStatus.Downloading || status == RowStatus.Paused ||
            status == RowStatus.WaitingNetwork || status == RowStatus.WaitingWifi || status == RowStatus.WaitingCharging
        Done -> status == RowStatus.Done
        Failed -> status == RowStatus.Failed
    }
}

/** Device chips combine with OR, state chips with OR, and the two axes with AND. */
fun DownloadRow.matches(devices: Set<DeviceFilter>, states: Set<StateFilter>): Boolean {
    val deviceOk = devices.isEmpty() || devices.any {
        when (it) {
            DeviceFilter.Phone -> device is Device.Phone
            is DeviceFilter.Pc -> (device as? Device.Pc)?.pcId == it.pcId
        }
    }
    return deviceOk && (states.isEmpty() || states.any { it.matches(status) })
}

/** Plain-words status; the Android version reads strings_downloads.xml. */
interface StatusStrings {
    fun queued(): String
    fun downloading(): String
    fun paused(): String
    fun waitingNetwork(): String = "Waiting for network"
    fun waitingWifi(): String = "Waiting for Wi-Fi"
    fun waitingCharging(): String = "Waiting to charge"
    fun done(): String
    fun failed(error: String?): String
    fun cancelled(): String
    fun unknown(): String
}

fun statusText(row: DownloadRow, s: StatusStrings): String = when (row.status) {
    RowStatus.Queued -> s.queued()
    RowStatus.Downloading -> s.downloading()
    RowStatus.Paused -> s.paused()
    RowStatus.WaitingNetwork -> s.waitingNetwork()
    RowStatus.WaitingWifi -> s.waitingWifi()
    RowStatus.WaitingCharging -> s.waitingCharging()
    RowStatus.Done -> s.done()
    RowStatus.Failed -> s.failed(row.error?.takeIf { it.isNotBlank() })
    RowStatus.Cancelled -> s.cancelled()
    RowStatus.Unknown -> s.unknown()
}
