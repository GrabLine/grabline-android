package dev.grabline.app.remote

import dev.grabline.app.data.AppDatabase
import dev.grabline.app.data.OutboxItem
import dev.grabline.app.data.PairedPc
import dev.grabline.app.data.PcJobWithPc
import dev.grabline.app.remote.connection.ConnectionManager
import dev.grabline.app.remote.connection.PcLinkState
import dev.grabline.app.remote.connection.RemoteException
import dev.grabline.app.remote.outbox.EnqueueResult
import dev.grabline.app.remote.outbox.Outbox
import dev.grabline.app.remote.protocol.AnalysisReply
import dev.grabline.app.remote.protocol.AnalyzeRequest
import dev.grabline.app.remote.protocol.ControlRequest
import dev.grabline.app.remote.protocol.ErrorCode
import dev.grabline.app.remote.protocol.ErrorMessages
import dev.grabline.app.remote.protocol.GlobalReply
import dev.grabline.app.remote.protocol.GlobalRequest
import dev.grabline.app.remote.protocol.GlobalState
import dev.grabline.app.remote.protocol.Job
import dev.grabline.app.remote.protocol.OkReply
import dev.grabline.app.remote.protocol.QueuesReply
import dev.grabline.app.remote.protocol.QueuesRequest
import dev.grabline.app.remote.protocol.UnpairRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** A failure the UI can show as is (plain words from the spec's error table). */
class RemoteFailure(val code: ErrorCode, val userMessage: String) : Exception(userMessage)

/** Everything the screens need from paired PCs. Implemented by [DefaultPcRemote]; faked in UI tests. */
interface PcRemote {
    val pcs: Flow<List<PairedPc>>
    val linkStates: StateFlow<Map<String, PcLinkState>>
    val globalStates: StateFlow<Map<String, GlobalState>>
    /** Cached jobs of every PC, with the PC's name and last-seen time, newest first. */
    val jobs: Flow<List<PcJobWithPc>>
    /** Links waiting to be delivered ("Will send when your PC is back" + count). */
    val outboxPending: Flow<Int>
    /** Links a PC refused for good, so the UI can say why. */
    val outboxFailed: Flow<List<OutboxItem>>

    /** The app came to the foreground (true) or left it (false). */
    fun setForeground(foreground: Boolean)
    /** Pull to refresh / Retry. */
    fun refresh(pcId: String? = null)

    /** Hand a link to a PC through the outbox (works offline). */
    suspend fun send(
        pcId: String,
        url: String,
        title: String? = null,
        quality: String? = null,
        queueId: Int? = null,
        folderId: String? = null,
    ): EnqueueResult

    suspend fun control(pcId: String, jobId: Long, action: String): Result<Job?>
    suspend fun global(pcId: String, action: String, bytesPerSecond: Long? = null): Result<GlobalState>
    suspend fun queues(pcId: String): Result<QueuesReply>
    suspend fun analyze(pcId: String, url: String): Result<AnalysisReply>

    /** Store a new pairing (from the pairing flow) and start connecting. */
    suspend fun addPairing(pc: PairedPc)
    /** Remove a pairing: tell the PC if reachable, then forget locally either way. */
    suspend fun unpair(pcId: String)
    suspend fun dismissFailed(itemId: Long)
}

class DefaultPcRemote(
    private val db: AppDatabase,
    private val connections: ConnectionManager,
    private val outbox: Outbox,
    /** Deletes the device key for a PC (remote/security). */
    private val deleteKey: suspend (pcId: String) -> Unit,
) : PcRemote {
    override val pcs: Flow<List<PairedPc>> = db.pcs().observeAll()
    override val linkStates: StateFlow<Map<String, PcLinkState>> = connections.states
    override val globalStates: StateFlow<Map<String, GlobalState>> = connections.globalStates
    override val jobs: Flow<List<PcJobWithPc>> = db.jobs().observeAll()
    override val outboxPending: Flow<Int> = db.outbox().observePendingCount(null)
    override val outboxFailed: Flow<List<OutboxItem>> = db.outbox().observeFailed()

    @Volatile private var foreground = false
    @Volatile private var waiting = 0

    fun onPendingCount(count: Int) {
        waiting = count
        connections.setWanted(foreground || waiting > 0)
    }

    override fun setForeground(foreground: Boolean) {
        this.foreground = foreground
        connections.setWanted(foreground || waiting > 0)
    }

    override fun refresh(pcId: String?) = connections.retryNow(pcId)

    override suspend fun send(
        pcId: String,
        url: String,
        title: String?,
        quality: String?,
        queueId: Int?,
        folderId: String?,
    ): EnqueueResult = outbox.enqueue(pcId, url, title, quality, queueId, folderId)

    override suspend fun control(pcId: String, jobId: Long, action: String): Result<Job?> = call {
        (connections.request(pcId) { id -> ControlRequest(id, jobId, action) }.message as? OkReply)?.job
    }

    override suspend fun global(pcId: String, action: String, bytesPerSecond: Long?): Result<GlobalState> = call {
        val reply = connections.request(pcId) { id -> GlobalRequest(id, action, bytesPerSecond) }.message
        (reply as? GlobalReply)?.state ?: throw RemoteException(ErrorCode.INTERNAL)
    }

    override suspend fun queues(pcId: String): Result<QueuesReply> = call {
        connections.request(pcId) { id -> QueuesRequest(id) }.message as? QueuesReply
            ?: throw RemoteException(ErrorCode.INTERNAL)
    }

    override suspend fun analyze(pcId: String, url: String): Result<AnalysisReply> = call {
        connections.request(pcId) { id -> AnalyzeRequest(id, url) }.message as? AnalysisReply
            ?: throw RemoteException(ErrorCode.INTERNAL)
    }

    override suspend fun addPairing(pc: PairedPc) {
        db.pcs().upsert(pc)
        connections.retryNow(pc.pcId)
    }

    override suspend fun unpair(pcId: String) {
        runCatching { connections.request(pcId) { id -> UnpairRequest(id) } }
        connections.forget(pcId)
        db.jobs().deleteForPc(pcId)
        db.pcs().delete(pcId)
        runCatching { deleteKey(pcId) }
    }

    override suspend fun dismissFailed(itemId: Long) = db.outbox().delete(itemId)

    private suspend fun <T> call(block: suspend () -> T?): Result<T> = try {
        val value = block()
        @Suppress("UNCHECKED_CAST")
        Result.success(value as T)
    } catch (e: RemoteException) {
        val message = if (e.message == "offline") OFFLINE_MESSAGE else e.reply?.userMessage() ?: ErrorMessages.forCode(e.code)
        Result.failure(RemoteFailure(e.code, message))
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        Result.failure(RemoteFailure(ErrorCode.INTERNAL, OFFLINE_MESSAGE))
    }

    companion object {
        const val OFFLINE_MESSAGE = "Your PC is off or on another network"
    }
}
