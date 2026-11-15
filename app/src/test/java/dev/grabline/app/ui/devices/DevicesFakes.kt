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
import dev.grabline.app.remote.protocol.QueueInfo
import dev.grabline.app.remote.protocol.QueuesReply
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

fun testPc(id: String = "pc1", name: String = "Office PC", defaultQueue: Int? = null) = PairedPc(
    pcId = id, name = name, fingerprint = "fp", deviceId = "dev", addresses = listOf("192.168.1.5"), port = 49152,
    protocolVersion = 6, pcAppVersion = "2.4.0", pairedAt = 1L, defaultQueueId = defaultQueue,
)

class FakeRemote : PcRemote {
    val pcList = MutableStateFlow<List<PairedPc>>(emptyList())
    val links = MutableStateFlow<Map<String, PcLinkState>>(emptyMap())
    val globals = MutableStateFlow<Map<String, GlobalState>>(emptyMap())
    val globalCalls = mutableListOf<Pair<String, Long?>>()
    val unpaired = mutableListOf<String>()
    var refreshed = 0
    var queueReply = QueuesReply(listOf(QueueInfo(1, "Main"), QueueInfo(2, "Night", paused = true)))

    override val pcs: Flow<List<PairedPc>> = pcList
    override val linkStates: StateFlow<Map<String, PcLinkState>> = links
    override val globalStates: StateFlow<Map<String, GlobalState>> = globals
    override val jobs: Flow<List<PcJobWithPc>> = emptyFlow()
    override val outboxPending: Flow<Int> = flowOf(0)
    override val outboxFailed: Flow<List<OutboxItem>> = flowOf(emptyList())

    override fun setForeground(foreground: Boolean) {}
    override fun refresh(pcId: String?) { refreshed++ }
    override suspend fun send(
        pcId: String, url: String, title: String?, quality: String?, queueId: Int?, folderId: String?,
    ): EnqueueResult = error("unused")
    override suspend fun control(pcId: String, jobId: Long, action: String): Result<Job?> = error("unused")
    override suspend fun global(pcId: String, action: String, bytesPerSecond: Long?): Result<GlobalState> {
        globalCalls += action to bytesPerSecond
        return Result.success(GlobalState(paused = action == "pauseAll"))
    }
    override suspend fun queues(pcId: String): Result<QueuesReply> = Result.success(queueReply)
    override suspend fun analyze(pcId: String, url: String): Result<AnalysisReply> = error("unused")
    override suspend fun addPairing(pc: PairedPc) { pcList.value = pcList.value.filter { it.pcId != pc.pcId } + pc }
    override suspend fun unpair(pcId: String) { unpaired += pcId; pcList.value = pcList.value.filter { it.pcId != pcId } }
    override suspend fun dismissFailed(itemId: Long) {}
}

class FakePairing(var script: (PairingEntry) -> Flow<PairingProgress>) : PairingController {
    val entries = mutableListOf<PairingEntry>()
    override fun pair(entry: PairingEntry, deviceName: String): Flow<PairingProgress> {
        entries += entry
        return script(entry)
    }
}
