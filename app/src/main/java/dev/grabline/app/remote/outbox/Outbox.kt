package dev.grabline.app.remote.outbox

import dev.grabline.app.data.OutboxDao
import dev.grabline.app.data.OutboxItem
import dev.grabline.app.remote.connection.ConnectionManager
import dev.grabline.app.remote.connection.PcLinkState
import dev.grabline.app.remote.connection.RemoteException
import dev.grabline.app.remote.protocol.DownloadRequest
import dev.grabline.app.remote.protocol.ErrorCode
import dev.grabline.app.remote.protocol.QueuedReply
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What [Outbox.enqueue] did with a share. */
sealed interface EnqueueResult {
    data class Queued(val requestId: String) : EnqueueResult
    /** The same link for the same PC was shared moments ago (QA-15): dropped. */
    data object DuplicateShare : EnqueueResult
}

/**
 * Links waiting for a PC ("Will send when your PC is back").
 *
 * Every share gets a fresh requestId when it enters the outbox and keeps it for
 * every retry, so the PC can answer a retried send with the first handoff
 * (QA-14: delivered exactly once). An item is deleted only after a `queued`
 * reply; badRequest, forbidden, notFound and unsupported mark it failed for the
 * user; anything else (offline, rateLimited, internal, a dropped connection)
 * leaves it pending for the next attempt.
 */
class Outbox(
    private val scope: CoroutineScope,
    private val dao: OutboxDao,
    private val connections: ConnectionManager,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sendLock = Mutex()
    private var worker: Job? = null

    /** Requests ids: 16 random bytes, base64url (22 chars, matches the PC's pattern). */
    fun newRequestId(): String {
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Store a share for [pcId]. An identical share within 2 s is dropped (QA-15). */
    suspend fun enqueue(
        pcId: String,
        url: String,
        title: String? = null,
        quality: String? = null,
        queueId: Int? = null,
        folderId: String? = null,
    ): EnqueueResult {
        val now = clock()
        if (dao.recentSame(pcId, url, now - DUPLICATE_WINDOW_MS) != null) return EnqueueResult.DuplicateShare
        val requestId = newRequestId()
        dao.insert(
            OutboxItem(
                requestId = requestId,
                pcId = pcId,
                url = url,
                title = title,
                quality = quality,
                queueId = queueId,
                folderId = folderId,
                createdAt = now,
            ),
        )
        connections.retryNow(pcId)
        scope.launch { flush(pcId) }
        return EnqueueResult.Queued(requestId)
    }

    /** Start delivering whenever a PC with waiting items comes online. */
    fun start() {
        if (worker != null) return
        worker = scope.launch {
            dao.resetSendingToPending() // a send interrupted by process death goes again
            combine(connections.states, dao.observePendingPcs()) { states, waiting ->
                waiting.filter { states[it] is PcLinkState.Online }.toSet()
            }.distinctUntilChanged().collect { ready -> ready.forEach { pcId -> launch { flush(pcId) } } }
        }
    }

    /** Whether any link is waiting (the app keeps connections wanted while true). */
    val pendingCount = dao.observePendingCount(null)

    /** Send every pending item for [pcId], oldest first, one at a time. */
    suspend fun flush(pcId: String) = sendLock.withLock {
        if (!connections.isOnline(pcId)) return@withLock
        for (item in dao.pending(pcId)) {
            var busyRetries = 0
            while (true) {
                dao.markSending(item.id)
                val outcome = send(item)
                if (outcome is Outcome.Retry && outcome.waitMs > 0) {
                    // rateLimited: wait retryAfterMs and retry the same item (up to 3 times, PROTOCOL.md 16)
                    dao.markRetry(item.id, outcome.reason)
                    delay(outcome.waitMs.coerceAtMost(5_000))
                    if (++busyRetries <= MAX_BUSY_RETRIES && connections.isOnline(pcId)) continue
                    return@withLock
                }
                when (outcome) {
                    Outcome.Delivered -> dao.markDelivered(item.id)
                    is Outcome.Refused -> dao.markFailed(item.id, outcome.code.wire)
                    is Outcome.Retry -> {
                        dao.markRetry(item.id, outcome.reason)
                        return@withLock
                    }
                }
                break
            }
        }
    }

    private sealed interface Outcome {
        data object Delivered : Outcome
        data class Refused(val code: ErrorCode) : Outcome
        data class Retry(val reason: String, val waitMs: Long = 0, val stop: Boolean = true) : Outcome
    }

    private suspend fun send(item: OutboxItem): Outcome = try {
        val reply = connections.request(item.pcId) { id ->
            DownloadRequest(
                id = id,
                url = item.url,
                requestId = item.requestId,
                quality = item.quality,
                queueId = item.queueId?.toLong(),
                folderId = item.folderId,
                title = item.title,
            )
        }
        if (reply.message is QueuedReply) Outcome.Delivered else Outcome.Retry("unexpected reply")
    } catch (e: RemoteException) {
        when (e.code) {
            ErrorCode.BAD_REQUEST, ErrorCode.FORBIDDEN, ErrorCode.NOT_FOUND, ErrorCode.UNSUPPORTED -> Outcome.Refused(e.code)
            ErrorCode.RATE_LIMITED -> Outcome.Retry("rateLimited", maxOf(250L, e.reply?.retryAfterMs ?: 250L), stop = false)
            else -> Outcome.Retry(e.code.wire)
        }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        Outcome.Retry("timeout")
    }

    companion object {
        const val DUPLICATE_WINDOW_MS = 2_000L
        private const val MAX_BUSY_RETRIES = 3
        /** Items older than this are confirmed with the user before sending (PROTOCOL.md 12). */
        const val STALE_AFTER_MS = 7L * 24 * 3600 * 1000
        private val random = SecureRandom()
    }
}
