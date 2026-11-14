package dev.grabline.app.remote.protocol

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Shared objects (PROTOCOL.md sections 12 and 14)
// ---------------------------------------------------------------------------

enum class JobStatus(val wire: String) {
    QUEUED("queued"),
    DOWNLOADING("downloading"),
    PAUSED("paused"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),

    /** Any status this phone does not know: shown as "Unknown" with no actions. */
    UNKNOWN("unknown");

    companion object {
        fun fromWire(wire: String?): JobStatus = entries.firstOrNull { it.wire == wire && it != UNKNOWN } ?: UNKNOWN
    }
}

/** The job object (PROTOCOL.md section 14). */
@Serializable
data class Job(
    val id: Long,
    val url: String,
    val name: String,
    val status: String,
    val kind: String? = null,
    val downloaded: Long = 0,
    val total: Long? = null,
    val speed: Long = 0,
    val eta: Long? = null,
    val queueId: Long? = null,
    val createdAt: Long = 0,
    val finishedAt: Long? = null,
    val error: String? = null,
    val requestId: String? = null,
    val fetchable: Boolean = false,
) {
    val jobStatus: JobStatus get() = JobStatus.fromWire(status)
}

@Serializable
data class GlobalState(
    val paused: Boolean,
    val speedLimit: Long? = null,
    val speed: Long = 0,
    val active: Int = 0,
    val queued: Int = 0,
    val todayBytes: Long? = null,
)

@Serializable
data class QueueInfo(val id: Long, val name: String, val paused: Boolean = false)

@Serializable
data class FolderInfo(val id: String, val name: String, val isDefault: Boolean = false)

@Serializable
data class QualityOption(
    val label: String,
    val kind: String,
    val height: Int? = null,
    val ext: String? = null,
    val sizeBytes: Long? = null,
)

@Serializable
data class StatusEntry(
    val url: String,
    val status: String,
    val id: Long? = null,
    val downloaded: Long? = null,
    val total: Long? = null,
    val name: String? = null,
)

@Serializable
data class FileEntry(
    val jobId: Long,
    val name: String,
    val size: Long,
    val finishedAt: Long = 0,
    val mime: String? = null,
)

@Serializable
data class OutboxItem(
    val itemId: Long,
    val url: String,
    val title: String? = null,
    val quality: String? = null,
    val createdAt: Long = 0,
)

/** Optional message groups listed in `authOk.features` (PROTOCOL.md section 12). */
object Feature {
    const val JOBS = "jobs"
    const val CONTROL = "control"
    const val GLOBAL = "global"
    const val QUEUES = "queues"
    const val ANALYZE = "analyze"
    const val EVENTS = "events"
    const val FILES = "files"
    const val FETCH = "fetch"
    const val OUTBOX = "outbox"
}

object Qualities {
    val all: Set<String> = setOf("best", "2160p", "1440p", "1080p", "720p", "480p", "360p", "mp3", "m4a", "flac")
}

object ControlActions {
    val all: Set<String> = setOf("pause", "resume", "cancel", "retry", "remove")
}

object GlobalActions {
    val all: Set<String> = setOf("get", "pauseAll", "resumeAll", "setSpeedLimit", "clearSpeedLimit")
}

object JobFilters {
    val all: Set<String> = setOf("all", "active", "finished", "failed")
}

object Topics {
    const val JOBS = "jobs"
    const val GLOBAL = "global"
    const val OUTBOX = "outbox"
}

// ---------------------------------------------------------------------------
// Requests (phone to PC). `id` and `type` are added by ProtocolCodec.encode.
// ---------------------------------------------------------------------------

sealed interface Request {
    /** Null only for notifications (`fetchAck`). */
    val id: String?
    val type: String
}

@Serializable
data class PairRequest(
    override val id: String,
    val protocolVersion: Int = PROTOCOL_VERSION,
    val deviceName: String,
    /** P-256 SubjectPublicKeyInfo DER, base64url, exactly 91 bytes. */
    val publicKey: String,
    /** HMAC proof, base64url of 32 bytes. Opaque here; built by the pairing code. */
    val codeProof: String,
    val appVersion: String? = null,
) : Request {
    override val type: String get() = "pair"
    override fun toString(): String = "PairRequest(id=$id, deviceName=$deviceName, <redacted>)"
}

@Serializable
data class AuthRequest(
    override val id: String,
    val protocolVersion: Int = PROTOCOL_VERSION,
    val deviceId: String,
    /** DER ECDSA signature, base64url, at most 72 raw bytes. */
    val signature: String,
    val appVersion: String? = null,
) : Request {
    override val type: String get() = "auth"
    override fun toString(): String = "AuthRequest(id=$id, deviceId=$deviceId, <redacted>)"
}

@Serializable
data class PingRequest(override val id: String) : Request {
    override val type: String get() = "ping"
}

@Serializable
data class DownloadRequest(
    override val id: String,
    val url: String,
    val requestId: String,
    val quality: String? = null,
    val queueId: Long? = null,
    val folderId: String? = null,
    val title: String? = null,
    val pageUrl: String? = null,
) : Request {
    override val type: String get() = "download"
}

@Serializable
data class StatusRequest(override val id: String, val urls: List<String>) : Request {
    override val type: String get() = "status"
}

@Serializable
data class RecentRequest(override val id: String, val limit: Int? = null) : Request {
    override val type: String get() = "recent"
}

@Serializable
data class FocusRequest(override val id: String, val target: String? = null) : Request {
    override val type: String get() = "focus"
}

@Serializable
data class LinksRequest(
    override val id: String,
    val urls: List<String>,
    val requestId: String,
    val quality: String? = null,
    val queueId: Long? = null,
    val folderId: String? = null,
    val pageUrl: String? = null,
    val pageTitle: String? = null,
) : Request {
    override val type: String get() = "links"
}

@Serializable
data class JobsRequest(
    override val id: String,
    val limit: Int? = null,
    val cursor: String? = null,
    val filter: String? = null,
) : Request {
    override val type: String get() = "jobs"
}

@Serializable
data class ControlRequest(override val id: String, val jobId: Long, val action: String) : Request {
    override val type: String get() = "control"
}

@Serializable
data class GlobalRequest(override val id: String, val action: String, val bytesPerSecond: Long? = null) : Request {
    override val type: String get() = "global"
}

@Serializable
data class QueuesRequest(override val id: String) : Request {
    override val type: String get() = "queues"
}

@Serializable
data class AnalyzeRequest(override val id: String, val url: String) : Request {
    override val type: String get() = "analyze"
}

@Serializable
data class SubscribeRequest(override val id: String, val topics: List<String>? = null) : Request {
    override val type: String get() = "subscribe"
}

@Serializable
data class UnsubscribeRequest(override val id: String) : Request {
    override val type: String get() = "unsubscribe"
}

@Serializable
data class UnpairRequest(override val id: String) : Request {
    override val type: String get() = "unpair"
}

@Serializable
data class FilesRequest(override val id: String, val limit: Int? = null, val cursor: String? = null) : Request {
    override val type: String get() = "files"
}

@Serializable
data class FetchRequest(
    override val id: String,
    val jobId: Long,
    val offset: Long? = null,
    val length: Long? = null,
    val etag: String? = null,
) : Request {
    override val type: String get() = "fetch"
}

@Serializable
data class FetchCancelRequest(override val id: String, val streamId: Long) : Request {
    override val type: String get() = "fetchCancel"
}

/** Notification: no `id`, no reply. */
@Serializable
data class FetchAck(val streamId: Long, val received: Long) : Request {
    override val id: String? get() = null
    override val type: String get() = "fetchAck"
}

@Serializable
data class FileHashRequest(override val id: String, val jobId: Long) : Request {
    override val type: String get() = "fileHash"
}

@Serializable
data class OutboxRequest(override val id: String) : Request {
    override val type: String get() = "outbox"
}

@Serializable
data class OutboxAckRequest(override val id: String, val itemIds: List<Long>) : Request {
    override val type: String get() = "outboxAck"
}

// ---------------------------------------------------------------------------
// Replies (PC to phone, carry `id`)
// ---------------------------------------------------------------------------

sealed interface ReplyMessage

@Serializable
data class PairedReply(
    val deviceId: String,
    val pcId: String,
    val pcName: String,
    val serverProof: String,
) : ReplyMessage {
    override fun toString(): String = "PairedReply(deviceId=$deviceId, pcId=$pcId, pcName=$pcName, <redacted>)"
}

@Serializable
data class AuthOkReply(
    val pcId: String,
    val pcName: String,
    val appVersion: String? = null,
    val appRunning: Boolean = true,
    val serverTime: Long = 0,
    val addresses: List<String> = emptyList(),
    val port: Int = DEFAULT_PORT,
    val features: List<String> = emptyList(),
) : ReplyMessage {
    fun supports(feature: String): Boolean = feature in features
}

/** `pong`. Every field but `appRunning` is absent in a protocol-5 pong. */
@Serializable
data class PongReply(
    val appRunning: Boolean = false,
    val pcId: String? = null,
    val pcName: String? = null,
    val appVersion: String? = null,
    val serverTime: Long? = null,
) : ReplyMessage

/** `queued`: from `download` (handoffId.. jobId) or `links` (count). */
@Serializable
data class QueuedReply(
    val handoffId: Long,
    val handoffUrl: String? = null,
    val previousJobId: Long? = null,
    val appRunning: Boolean = true,
    val requestId: String? = null,
    val duplicate: Boolean = false,
    val jobId: Long? = null,
    val count: Int? = null,
) : ReplyMessage

@Serializable
data class StatusReply(val jobs: List<StatusEntry>, val appRunning: Boolean = true) : ReplyMessage

@Serializable
data class RecentReply(val jobs: List<Job>, val appRunning: Boolean = true) : ReplyMessage

/** `ok`: `job` after `control` (null after `remove`), `removed` after `outboxAck`, `appRunning` after `focus`. */
@Serializable
data class OkReply(
    val appRunning: Boolean? = null,
    val job: Job? = null,
    val removed: Int? = null,
) : ReplyMessage

@Serializable
data class JobsReply(
    val jobs: List<Job>,
    val nextCursor: String? = null,
    val total: Long = 0,
) : ReplyMessage

@Serializable
data class GlobalReply(val state: GlobalState) : ReplyMessage

@Serializable
data class QueuesReply(
    val queues: List<QueueInfo> = emptyList(),
    val folders: List<FolderInfo> = emptyList(),
) : ReplyMessage

@Serializable
data class AnalysisReply(
    val url: String,
    val title: String? = null,
    val duration: Long? = null,
    val isPlaylist: Boolean = false,
    val count: Int = 1,
    val qualities: List<QualityOption> = emptyList(),
) : ReplyMessage

@Serializable
data class SubscribedReply(val topics: List<String> = emptyList(), val state: GlobalState? = null) : ReplyMessage

@Serializable
data class FilesReply(val files: List<FileEntry> = emptyList(), val nextCursor: String? = null) : ReplyMessage

@Serializable
data class FetchStartReply(
    val streamId: Long,
    val jobId: Long,
    val name: String,
    val size: Long,
    val offset: Long = 0,
    val length: Long = 0,
    val etag: String? = null,
    val chunkSize: Int = 262_144,
    val windowBytes: Long = 8_388_608,
) : ReplyMessage

@Serializable
data class FileHashReply(
    val jobId: Long,
    val size: Long,
    val etag: String? = null,
    val sha256: String,
) : ReplyMessage

@Serializable
data class OutboxReply(val items: List<OutboxItem> = emptyList()) : ReplyMessage

/** Error reply (section 16). The phone never shows [message]; it shows [ErrorMessages] text chosen by code. */
@Serializable
data class ErrorReply(
    val code: String,
    val message: String = "",
    val retryAfterMs: Long? = null,
    val minClientVersion: Int? = null,
    val attemptsLeft: Int? = null,
    val reason: String? = null,
) : ReplyMessage {
    val errorCode: ErrorCode get() = ErrorCode.fromWire(code)
    fun userMessage(siteName: String? = null): String = ErrorMessages.forReply(errorCode, reason, siteName)
}

/** A reply type this phone does not know. */
data class UnknownReply(val type: String) : ReplyMessage

// ---------------------------------------------------------------------------
// Events (PC to phone, no `id`)
// ---------------------------------------------------------------------------

sealed interface ServerEvent

@Serializable
data class PairPendingEvent(val timeoutSeconds: Int = 60) : ServerEvent

@Serializable
data class FetchEndEvent(
    val streamId: Long,
    val status: String,
    val nextOffset: Long? = null,
    val code: String? = null,
) : ServerEvent

@Serializable
data class JobAddedEvent(val seq: Long, val ts: Long = 0, val job: Job) : ServerEvent

@Serializable
data class JobUpdatedEvent(val seq: Long, val ts: Long = 0, val job: Job) : ServerEvent

@Serializable
data class JobProgressEvent(
    val seq: Long,
    val ts: Long = 0,
    val jobId: Long,
    val status: String,
    val downloaded: Long = 0,
    val total: Long? = null,
    val speed: Long = 0,
    val eta: Long? = null,
) : ServerEvent

@Serializable
data class JobFinishedEvent(val seq: Long, val ts: Long = 0, val job: Job) : ServerEvent

@Serializable
data class JobFailedEvent(val seq: Long, val ts: Long = 0, val job: Job) : ServerEvent

@Serializable
data class JobRemovedEvent(val seq: Long, val ts: Long = 0, val jobId: Long) : ServerEvent

@Serializable
data class GlobalUpdatedEvent(val seq: Long, val ts: Long = 0, val state: GlobalState) : ServerEvent

@Serializable
data class OutboxAddedEvent(val seq: Long, val ts: Long = 0, val count: Int = 0) : ServerEvent

/** An event name this phone does not know (or a known one for a later version): ignore, never crash. */
data class UnknownEvent(val name: String) : ServerEvent

/** `fetchEnd.status` values. */
object FetchEndStatus {
    const val COMPLETE = "complete"
    const val CANCELLED = "cancelled"
    const val ERROR = "error"
}
