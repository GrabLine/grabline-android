package dev.grabline.app.remote.phone

import dev.grabline.app.engine.PhoneEngine
import dev.grabline.app.engine.PhoneJob
import dev.grabline.app.engine.PhoneJobStatus
import dev.grabline.app.engine.torrent.TorrentRouting
import dev.grabline.app.remote.PcRemote
import dev.grabline.app.remote.connection.RemoteException
import dev.grabline.app.remote.outbox.EnqueueResult
import dev.grabline.app.remote.protocol.FrameTooLargeException
import dev.grabline.app.remote.protocol.Inbound
import dev.grabline.app.remote.protocol.InvalidRequestException
import dev.grabline.app.remote.protocol.PhoneCommandActions
import dev.grabline.app.remote.protocol.PhoneCommandEvent
import dev.grabline.app.remote.protocol.PhoneCommandResultRequest
import dev.grabline.app.remote.protocol.PhoneCommandResults
import dev.grabline.app.remote.protocol.PhoneJobInfo
import dev.grabline.app.remote.protocol.PhoneJobLimits
import dev.grabline.app.remote.protocol.PhoneJobsRequest
import dev.grabline.app.remote.protocol.ProtocolCodec
import dev.grabline.app.remote.protocol.Request
import dev.grabline.app.ui.downloads.RowAction
import dev.grabline.app.ui.downloads.phoneRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Sends one request on a PC connection and waits for its reply. Throws [RemoteException] or a time-out. */
fun interface PhoneLink {
    suspend fun request(build: (String) -> Request): Inbound.Reply
}

/** What the phone answers to one `phone.command`. */
data class CommandOutcome(val result: String, val message: String? = null)

/**
 * The phone's side of PROTOCOL.md section 20 (feature `phoneJobs`): it reports the phone's downloads to a PC and does
 * what the PC asks for. One [run] per PC connection; the connection code starts it only when the PC lists the feature.
 */
class PhoneShare(
    private val engine: PhoneEngine,
    /** The "Let paired PCs see and control downloads on this phone" setting. */
    private val enabled: Flow<Boolean>,
    /** For `moveToPc`: the same "Send to PC" path the Downloads details screen uses. */
    private val remote: () -> PcRemote,
    /** At most one `phoneJobs` per this long, per connection. */
    private val intervalMs: Long = REPORT_INTERVAL_MS,
) {
    /**
     * Reports the phone's downloads over [link] and answers every command from [commands] until cancelled.
     * The report goes out at once, then whenever something changed, at most once per [intervalMs] (the newest list wins).
     */
    suspend fun run(link: PhoneLink, commands: ReceiveChannel<PhoneCommandEvent>, pcId: String) = coroutineScope {
        val kicks = MutableStateFlow(0)
        launch { report(link, kicks) }
        for (command in commands) {
            // A command without a usable id or job cannot be answered, so it is not executed either.
            if (command.commandId !in 1..PhoneJobLimits.MAX_COMMAND_ID || command.jobId < 1) continue
            val outcome = try {
                handle(pcId, command)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                CommandOutcome(PhoneCommandResults.FAILED, FAILED_TEXT)
            }
            if (!send(link) { id -> PhoneCommandResultRequest(id, command.commandId, outcome.result, outcome.message?.take(PhoneJobLimits.MAX_MESSAGE)) }) continue
            // A command that did (or tried to do) something: send a fresh list.
            if (outcome.result == PhoneCommandResults.OK || outcome.result == PhoneCommandResults.FAILED) kicks.update { it + 1 }
        }
    }

    private data class Snapshot(val shared: Boolean, val jobs: List<PhoneJobInfo>, val kick: Int)

    private suspend fun report(link: PhoneLink, kicks: Flow<Int>) {
        combine(enabled, engine.jobs, kicks) { on, jobs, kick ->
            // Off: one `shared: false` with no jobs; the job list is not even looked at.
            if (on) Snapshot(true, PhoneJobMapper.map(jobs), kick) else Snapshot(false, emptyList(), kick)
        }
            .distinctUntilChanged()
            .conflate()
            .collect { snapshot ->
                push(link, snapshot)
                delay(intervalMs)
            }
    }

    private suspend fun push(link: PhoneLink, snapshot: Snapshot) {
        var jobs = snapshot.jobs
        while (true) {
            try {
                send(link) { id -> PhoneJobsRequest(id, snapshot.shared, jobs) }
                return
            } catch (_: FrameTooLargeException) {
                // Many long names and links: send fewer of the newest jobs rather than nothing.
                if (jobs.isEmpty()) return
                jobs = jobs.take(jobs.size / 2)
            }
        }
    }

    /** True when the PC answered. A dropped connection or a time-out is not an error here: the session ends on its own. */
    private suspend fun send(link: PhoneLink, build: (String) -> Request): Boolean = try {
        link.request(build)
        true
    } catch (e: FrameTooLargeException) {
        throw e
    } catch (_: InvalidRequestException) {
        false
    } catch (_: RemoteException) {
        false
    } catch (_: TimeoutCancellationException) {
        false
    }

    // ------------------------------------------------------------------ commands

    /** Checks the command against the phone's own database and state, then does what the Downloads screen does. */
    suspend fun handle(pcId: String, command: PhoneCommandEvent): CommandOutcome {
        if (!enabled.first()) return CommandOutcome(PhoneCommandResults.REFUSED, NOT_SHARED_TEXT)
        val job = engine.jobs.first().firstOrNull { it.id == command.jobId }
            ?: return CommandOutcome(PhoneCommandResults.NOT_FOUND, NOT_FOUND_TEXT)
        if (command.action !in PhoneCommandActions.all) return CommandOutcome(PhoneCommandResults.REFUSED, UNKNOWN_ACTION_TEXT)
        if (command.action == PhoneCommandActions.MOVE_TO_PC) return moveToPc(pcId, job)
        // The same rule the Downloads screen uses to decide which buttons a row has.
        val action = when (command.action) {
            PhoneCommandActions.PAUSE -> RowAction.Pause
            PhoneCommandActions.RESUME -> RowAction.Resume
            PhoneCommandActions.CANCEL -> RowAction.Cancel
            PhoneCommandActions.RETRY -> RowAction.Retry
            else -> RowAction.Remove
        }
        if (action !in phoneRow(job).actions) return CommandOutcome(PhoneCommandResults.REFUSED, WRONG_STATE_TEXT)
        when (action) {
            RowAction.Pause -> engine.pause(job.id)
            RowAction.Resume -> engine.resume(job.id)
            RowAction.Cancel -> engine.cancel(job.id)
            RowAction.Retry -> engine.retry(job.id)
            else -> engine.remove(job.id)
        }
        return CommandOutcome(PhoneCommandResults.OK)
    }

    /**
     * "Send to PC" with the commanding PC as the target. The link goes to the PC with `download` through the outbox;
     * the phone job is cancelled (partial file deleted) only once the PC has replied `queued`, which is the existing
     * [dev.grabline.app.remote.transfer.SendToPcLink] rule. A finished file is kept.
     */
    private suspend fun moveToPc(pcId: String, job: PhoneJob): CommandOutcome {
        if (job.isTransfer) return CommandOutcome(PhoneCommandResults.REFUSED, CANT_MOVE_TEXT)
        val movable = job.status != PhoneJobStatus.CANCELLED && !job.url.startsWith(TorrentRouting.FILE_PREFIX) &&
            ProtocolCodec.isValidUrl(job.url)
        if (!movable) return CommandOutcome(PhoneCommandResults.REFUSED, CANT_MOVE_TEXT)
        val title = job.name.takeIf { it.isNotBlank() }?.let(PhoneJobMapper::cut512)
        val result = if (job.status == PhoneJobStatus.COMPLETED) {
            remote().send(pcId, job.url, title, null)
        } else {
            remote().sendFromPhone(pcId, job.id, job.url, title, null)
        }
        return when (result) {
            is EnqueueResult.Queued -> CommandOutcome(PhoneCommandResults.OK)
            EnqueueResult.DuplicateShare -> CommandOutcome(PhoneCommandResults.OK, ALREADY_SENDING_TEXT)
            EnqueueResult.PcRemoved -> CommandOutcome(PhoneCommandResults.FAILED, PC_REMOVED_TEXT)
        }
    }

    companion object {
        const val REPORT_INTERVAL_MS = 1_000L

        /** Commands waiting for the phone. The PC sends at most 10 a second, so more than this is a misbehaving PC: the oldest are dropped. */
        const val MAX_PENDING_COMMANDS = 32

        fun newCommandChannel(): Channel<PhoneCommandEvent> = Channel(MAX_PENDING_COMMANDS, BufferOverflow.DROP_OLDEST)

        const val NOT_SHARED_TEXT = "This phone doesn't let PCs control its downloads"
        const val NOT_FOUND_TEXT = "That download is no longer on the phone"
        const val WRONG_STATE_TEXT = "That can't be done to this download right now"
        const val UNKNOWN_ACTION_TEXT = "The phone doesn't know that action"
        const val CANT_MOVE_TEXT = "This download can't be moved to a PC"
        const val FAILED_TEXT = "The phone couldn't do that. Try again"
        const val ALREADY_SENDING_TEXT = "Already on its way to this PC"
        const val PC_REMOVED_TEXT = "This PC no longer accepts this phone"
    }
}

/** Turns the phone's jobs into the section 20 phone-job objects. */
object PhoneJobMapper {
    /** The newest [PhoneJobLimits.MAX_JOBS] jobs ([jobs] is newest first), as the PC wants them. */
    fun map(jobs: List<PhoneJob>): List<PhoneJobInfo> =
        jobs.asSequence().filter { it.id >= 1 }.distinctBy { it.id }.take(PhoneJobLimits.MAX_JOBS).map(::toInfo).toList()

    fun toInfo(job: PhoneJob): PhoneJobInfo {
        val downloading = job.status == PhoneJobStatus.DOWNLOADING
        return PhoneJobInfo(
            id = job.id,
            name = safeName(job.name),
            url = reportedUrl(job),
            status = statusOf(job.status),
            kind = kindOf(job),
            downloaded = job.downloaded.coerceAtLeast(0),
            total = job.total?.takeIf { it >= 0 },
            speed = if (downloading) job.speed.coerceAtLeast(0) else 0,
            eta = if (downloading) job.etaSeconds?.takeIf { it >= 0 } else null,
            createdAt = (job.createdAt / 1000).coerceAtLeast(0), // ms on the phone, Unix seconds on the wire; never negative (the PC would refuse the whole list)
            error = if (job.status == PhoneJobStatus.FAILED) job.error?.let { cut(plain(it), PhoneJobLimits.MAX_ERROR) } else null,
        )
    }

    /** The link for the PC; empty for a copy from a PC and for anything the PC could not download (local files, ftp, s3 ...). */
    private fun reportedUrl(job: PhoneJob): String {
        if (job.isTransfer || job.url.startsWith(TorrentRouting.FILE_PREFIX, ignoreCase = true)) return ""
        val stripped = stripUserInfo(job.url)
        return if (ProtocolCodec.isValidUrl(stripped)) cut(stripped, PhoneJobLimits.MAX_URL) else ""
    }

    /** Text for a PC to show: no C0/C1 control characters (NUL included) and no bidi override or isolate characters. */
    fun plain(text: String): String = buildString(text.length) {
        for (c in text) {
            val code = c.code
            val bad = code < 0x20 || code in 0x7F..0x9F || code in 0x202A..0x202E || code in 0x2066..0x2069
            append(if (bad) ' ' else c)
        }
    }.let { if (it.length == text.length) it else it.replace(Regex(" {2,}"), " ") }

    fun statusOf(status: PhoneJobStatus): String = when (status) {
        PhoneJobStatus.QUEUED -> "queued"
        PhoneJobStatus.DOWNLOADING -> "downloading"
        PhoneJobStatus.PAUSED -> "paused"
        PhoneJobStatus.WAITING_NETWORK, PhoneJobStatus.WAITING_WIFI, PhoneJobStatus.WAITING_CHARGING -> "waiting"
        PhoneJobStatus.COMPLETED -> "completed"
        PhoneJobStatus.FAILED -> "failed"
        PhoneJobStatus.CANCELLED -> "cancelled"
    }

    fun kindOf(job: PhoneJob): String = when {
        job.isTransfer -> "transfer"
        job.torrent != null -> "torrent"
        job.isVideo -> "video"
        job.isCloud -> "cloud"
        else -> "direct"
    }

    private val userInfo = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*://)[^/?#]*@")

    /** `scheme://user:password@host/...` becomes `scheme://host/...`. */
    fun stripUserInfo(url: String): String = userInfo.replaceFirst(url, "$1")

    /** A file or video name, never a path, 1 to 256 characters. */
    fun safeName(name: String): String {
        val last = plain(name).trim().trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').trim()
        return cut(last.ifBlank { "Download" }, PhoneJobLimits.MAX_NAME)
    }

    fun cut512(text: String): String = cut(text, 512)

    /** At most [max] chars without leaving half a surrogate pair at the end. */
    private fun cut(text: String, max: Int): String {
        if (text.length <= max) return text
        val end = if (Character.isHighSurrogate(text[max - 1])) max - 1 else max
        return text.substring(0, end)
    }
}
