package dev.grabline.app.remote.transfer

import dev.grabline.app.data.OutboxItem as HeldItem
import dev.grabline.app.data.PhoneJobDao
import dev.grabline.app.remote.connection.ConnectionManager
import dev.grabline.app.remote.connection.PcLinkState
import dev.grabline.app.remote.connection.RemoteException
import dev.grabline.app.remote.protocol.Feature
import dev.grabline.app.remote.protocol.OutboxAckRequest
import dev.grabline.app.remote.protocol.OutboxAddedEvent
import dev.grabline.app.remote.protocol.OutboxItem
import dev.grabline.app.remote.protocol.OutboxReply
import dev.grabline.app.remote.protocol.OutboxRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Links the PC user queued "for my phone" (PROTOCOL.md 13 `outbox`): on connect and on `outbox.added` they are saved
 * as queued phone jobs, then acknowledged. The PC deletes an item only on ack, so an item offered again (an ack that
 * did not arrive) is recognised by its itemId per PC and not added twice, but still acknowledged.
 */
class PcOutboxPuller(
    private val scope: CoroutineScope,
    private val connections: ConnectionManager,
    /** Whether a phone job already exists for this PC's outbox item. */
    private val held: suspend (pcId: String, itemId: Long) -> Boolean,
    /** Save the item as a queued phone job (must record pcId and itemId so [held] sees it). */
    private val save: suspend (pcId: String, item: OutboxItem) -> Unit,
) {
    private val lock = Mutex()

    fun start() {
        scope.launch {
            connections.states
                .map { states -> states.filterValues { it is PcLinkState.Online && Feature.OUTBOX in it.features }.keys }
                .distinctUntilChanged()
                .collect { online -> online.forEach { pcId -> launch { pull(pcId) } } }
        }
        scope.launch {
            connections.events.collect { (pcId, event) -> if (event is OutboxAddedEvent) launch { pull(pcId) } }
        }
    }

    /** Fetch, save, acknowledge. Returns how many new phone jobs were made (0 when offline or empty). */
    suspend fun pull(pcId: String): Int = lock.withLock {
        val items = try {
            (connections.request(pcId) { id -> OutboxRequest(id) }.message as? OutboxReply)?.items.orEmpty()
        } catch (e: RemoteException) {
            return 0
        } catch (e: TimeoutCancellationException) {
            return 0
        }
        if (items.isEmpty()) return 0
        var added = 0
        val saved = mutableListOf<Long>()
        for (item in items) {
            if (!held(pcId, item.itemId)) {
                try {
                    save(pcId, item)
                    added++
                } catch (e: Exception) {
                    continue // not saved: no ack, so the PC offers it again
                }
            }
            saved += item.itemId
        }
        // Ack only what is in our database.
        saved.chunked(100).forEach { ids ->
            try {
                connections.request(pcId) { id -> OutboxAckRequest(id, ids) }
            } catch (_: RemoteException) {
            } catch (_: TimeoutCancellationException) {
            }
        }
        added
    }
}

/** Cancels the phone job behind a "Send to PC" once the PC has the link (QA-38): the partial file goes with it. */
class SendToPcLink(private val jobs: PhoneJobDao, private val cancel: suspend (phoneJobId: Long) -> Unit) {
    suspend fun onDelivered(item: HeldItem) {
        for (row in jobs.bySendRequest(item.requestId)) {
            jobs.setSendRequest(row.id, null)
            cancel(row.id)
        }
    }
}