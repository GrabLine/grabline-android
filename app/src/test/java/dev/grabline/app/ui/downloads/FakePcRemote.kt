package dev.grabline.app.ui.downloads

import dev.grabline.app.data.OutboxItem
import dev.grabline.app.data.PairedPc
import dev.grabline.app.data.PcJob
import dev.grabline.app.data.PcJobWithPc
import dev.grabline.app.remote.PcRemote
import dev.grabline.app.remote.connection.PcLinkState
import dev.grabline.app.remote.outbox.EnqueueResult
import dev.grabline.app.remote.protocol.AnalysisReply
import dev.grabline.app.remote.protocol.GlobalState
import dev.grabline.app.remote.protocol.Job
import dev.grabline.app.remote.protocol.QueuesReply
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakePcRemote : PcRemote {
    val jobsFlow = MutableStateFlow<List<PcJobWithPc>>(emptyList())
    val links = MutableStateFlow<Map<String, PcLinkState>>(emptyMap())
    val pending = MutableStateFlow(0)
    val failed = MutableStateFlow<List<OutboxItem>>(emptyList())
    val controlCalls = mutableListOf<Triple<String, Long, String>>()
    var controlResult: Result<Job?> = Result.success(null)
    var refreshes = 0

    override val pcs: Flow<List<PairedPc>> = MutableStateFlow(emptyList())
    override val linkStates: StateFlow<Map<String, PcLinkState>> = links
    override val globalStates: StateFlow<Map<String, GlobalState>> = MutableStateFlow(emptyMap())
    override val jobs: Flow<List<PcJobWithPc>> = jobsFlow
    override val outboxPending: Flow<Int> = pending
    override val outboxFailed: Flow<List<OutboxItem>> = failed

    override fun setForeground(foreground: Boolean) {}
    override fun refresh(pcId: String?) { refreshes++ }
    override suspend fun send(pcId: String, url: String, title: String?, quality: String?, queueId: Int?, folderId: String?): EnqueueResult = TODO()
    override suspend fun control(pcId: String, jobId: Long, action: String): Result<Job?> {
        controlCalls += Triple(pcId, jobId, action)
        return controlResult
    }
    override suspend fun global(pcId: String, action: String, bytesPerSecond: Long?): Result<GlobalState> = TODO()
    override suspend fun queues(pcId: String): Result<QueuesReply> = Result.success(QueuesReply())
    override suspend fun analyze(pcId: String, url: String): Result<AnalysisReply> = TODO()
    override suspend fun addPairing(pc: PairedPc) {}
    override suspend fun unpair(pcId: String) {}
    override suspend fun dismissFailed(itemId: Long) {}
}

fun fakeJob(
    pcId: String,
    jobId: Long,
    status: String,
    pcName: String = "Desk PC",
    downloaded: Long = 0,
    total: Long? = null,
    speed: Long? = null,
    error: String? = null,
) = PcJobWithPc(
    PcJob(pcId, jobId, "https://example.com/f$jobId", "file$jobId.zip", status, downloaded, total, speed, null, null, jobId, null, error, 0),
    pcName,
    1_000L,
)
