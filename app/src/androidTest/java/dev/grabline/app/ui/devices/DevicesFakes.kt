package dev.grabline.app.ui.devices

import dev.grabline.app.data.OutboxItem
import dev.grabline.app.data.PairedPc
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

fun testPc(id: String = "pc1", name: String = "Office PC") = PairedPc(
    pcId = id, name = name, fingerprint = "fp", deviceId = "dev", addresses = listOf("192.168.1.5"), port = 49152,
    protocolVersion = 6, pcAppVersion = "2.4.0", pairedAt = 1L,
)

class FakeRemote : PcRemote {
    val pcList = MutableStateFlow<List<PairedPc>>(emptyList())
    val links = MutableStateFlow<Map<String, PcLinkState>>(emptyMap())
    val unpaired = mutableListOf<String>()

    override val pcs: Flow<List<PairedPc>> = pcList
    override val linkStates: StateFlow<Map<String, PcLinkState>> = links
    override val globalStates: StateFlow<Map<String, GlobalState>> = MutableStateFlow(emptyMap())
    override val jobs: Flow<List<PcJobWithPc>> = emptyFlow()
    override val outboxPending: Flow<Int> = flowOf(0)
    override val outboxFailed: Flow<List<OutboxItem>> = flowOf(emptyList())

    override fun setForeground(foreground: Boolean) {}
    override fun refresh(pcId: String?) {}
    override suspend fun send(
        pcId: String, url: String, title: String?, quality: String?, queueId: Int?, folderId: String?,
    ): EnqueueResult = error("unused")
    override suspend fun control(pcId: String, jobId: Long, action: String): Result<Job?> = error("unused")
    override suspend fun global(pcId: String, action: String, bytesPerSecond: Long?): Result<GlobalState> = error("unused")
    override suspend fun queues(pcId: String): Result<QueuesReply> = Result.success(QueuesReply())
    override suspend fun analyze(pcId: String, url: String): Result<AnalysisReply> = error("unused")
    override suspend fun addPairing(pc: PairedPc) {}
    override suspend fun unpair(pcId: String) { unpaired += pcId; pcList.value = pcList.value.filter { it.pcId != pcId } }
    override suspend fun dismissFailed(itemId: Long) {}
}
