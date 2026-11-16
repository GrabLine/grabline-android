package dev.grabline.app.ui.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.grabline.app.engine.PhoneAddRequest
import dev.grabline.app.engine.PhoneEngine
import dev.grabline.app.engine.TransferAddRequest
import dev.grabline.app.engine.torrent.TorrentRouting
import dev.grabline.app.remote.outbox.EnqueueResult
import dev.grabline.app.ui.downloads.DetailsNote
import dev.grabline.app.remote.PcRemote
import dev.grabline.app.remote.protocol.QueueInfo
import dev.grabline.app.ui.downloads.DownloadMessage
import dev.grabline.app.ui.downloads.DownloadRow
import dev.grabline.app.ui.downloads.PendingControls
import dev.grabline.app.ui.downloads.RowAction
import dev.grabline.app.ui.downloads.RowStatus
import dev.grabline.app.ui.downloads.Target
import dev.grabline.app.ui.downloads.PHONE_ID
import dev.grabline.app.ui.downloads.UNDO_MILLIS
import dev.grabline.app.ui.downloads.phoneRow
import dev.grabline.app.ui.downloads.toRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DetailsUiState(
    val loaded: Boolean = false,
    /** Null once the job is gone from the PC. */
    val row: DownloadRow? = null,
    /** Queue name from the PC, when known. */
    val queueName: String? = null,
    /** "Send to PC" is offered here (a phone download, with a PC paired): the PCs to choose from. */
    val sendTargets: List<PcChoice> = emptyList(),
    /** "Copy to this phone": a finished PC file the PC will let this phone fetch. */
    val canCopyToPhone: Boolean = false,
    /** "Download on this phone instead": a PC download that has not finished. */
    val canDownloadHere: Boolean = false,
    /** Remove was asked for and is waiting out its Undo. */
    val removing: Boolean = false,
    /** The user removed this download and it is now gone: leave the screen. */
    val leave: Boolean = false,
) {
    val gone: Boolean get() = loaded && row == null
}

data class PcChoice(val pcId: String, val name: String)


class DownloadDetailsViewModel(
    private val remote: PcRemote,
    private val pcId: String,
    private val jobId: Long,
    saved: SavedStateHandle = SavedStateHandle(),
    undoMillis: Long = UNDO_MILLIS,
    private val flushScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val phone: PhoneEngine? = null,
    /** Where new phone files go: a picked tree URI, or null for Downloads/GrabLine. */
    private val saveFolder: suspend () -> String? = { null },
) : ViewModel() {

    /** [pcId] is [PHONE_ID] for a job on this phone. */
    private val onPhone = phone != null && pcId == PHONE_ID

    private val messageChannel = Channel<DownloadMessage>(Channel.UNLIMITED)
    val messages: Flow<DownloadMessage> = messageChannel.receiveAsFlow()

    private val controls = PendingControls(remote, viewModelScope, undoMillis, phone) {
        messageChannel.trySend(DownloadMessage.Error(it))
    }

    private val queues = MutableStateFlow<List<QueueInfo>>(emptyList())
    private var lastRow: DownloadRow? = null
    private var removeAsked = false

    private val source: Flow<DownloadRow?> =
        if (onPhone) {
            phone!!.jobs.map { list -> list.firstOrNull { it.id == jobId }?.let(::phoneRow) }
        } else {
            combine(remote.jobs, remote.linkStates) { jobs, links ->
                jobs.firstOrNull { it.job.pcId == pcId && it.job.jobId == jobId }?.let { toRow(it, links[pcId]) }
            }
        }

    private val targetPcId: String? = if (onPhone) null else pcId

    val state: StateFlow<DetailsUiState> = combine(
        source, controls.pending, queues, remote.pcs,
    ) { found, pending, queueList, pcList ->
        val mine = pending.filter { p -> p.targets.any { it.pcId == targetPcId && it.jobId == jobId } }
        val removing = mine.any { it.action == RowAction.Remove }
        var row = found ?: if (removing) lastRow else null
        if (row != null && mine.any { it.action == RowAction.Cancel }) {
            row = row.copy(status = RowStatus.Cancelled, speed = 0, etaSeconds = null)
        }
        if (found != null) lastRow = found
        val phoneLive = row != null && row.isPhone && !row.isTransfer && !row.url.startsWith(TorrentRouting.FILE_PREFIX) &&
            row.status in setOf(RowStatus.Queued, RowStatus.Downloading, RowStatus.Paused, RowStatus.WaitingNetwork, RowStatus.WaitingWifi, RowStatus.WaitingCharging, RowStatus.Failed)
        val pcRow = row != null && !row.isPhone && !row.offline && phone != null
        DetailsUiState(
            loaded = true,
            row = row,
            sendTargets = if (phoneLive) pcList.filter { it.removedAt == null }.map { PcChoice(it.pcId, it.name) } else emptyList(),
            canCopyToPhone = pcRow && row!!.status == RowStatus.Done && row.fetchable,
            canDownloadHere = pcRow && row!!.status in setOf(RowStatus.Queued, RowStatus.Downloading, RowStatus.Paused, RowStatus.Failed),
            queueName = row?.queueId?.let { q -> queueList.firstOrNull { it.id == q }?.name },
            removing = removing,
            leave = found == null && removeAsked && !removing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailsUiState())

    init {
        if (!onPhone) viewModelScope.launch {
            remote.queues(pcId).getOrNull()?.let { reply ->
                queues.value = reply.queues
            }
        }
    }

    fun act(action: RowAction) {
        val row = state.value.row ?: return
        if (action !in row.actions) return
        if (action == RowAction.Remove) removeAsked = true
        val id = controls.request(action, listOf(Target(targetPcId, jobId, row.name))) ?: return
        messageChannel.trySend(DownloadMessage.UndoOffer(id, action, 1, row.name))
    }

    fun setSequential(on: Boolean) {
        val engine = phone ?: return
        if (onPhone) viewModelScope.launch { engine.setTorrentSequential(jobId, on) }
    }

    fun setFileSelected(index: Int, selected: Boolean) {
        val engine = phone ?: return
        if (onPhone) viewModelScope.launch { engine.setTorrentFileSelected(jobId, index, selected) }
    }

    fun stopSeeding() {
        val engine = phone ?: return
        if (onPhone) viewModelScope.launch { engine.stopSeeding(jobId) }
    }

    /** "Copy to this phone": a background transfer; it shows on the Transfers screen. */
    fun copyToPhone() {
        val row = state.value.row ?: return
        val engine = phone ?: return
        if (row.isPhone || !state.value.canCopyToPhone) return
        viewModelScope.launch {
            engine.addTransfer(TransferAddRequest(pcId, jobId, row.name, row.url, row.total, saveFolder()))
            messageChannel.trySend(DownloadMessage.Note(DetailsNote.CopyStarted))
        }
    }

    /** "Send to PC": queue the link for [targetPc]; the phone job stops once the PC confirms (QA-38). */
    fun sendToPc(targetPc: String) {
        val row = state.value.row ?: return
        if (!onPhone || state.value.sendTargets.none { it.pcId == targetPc }) return
        viewModelScope.launch {
            when (val r = remote.sendFromPhone(targetPc, jobId, row.url, row.name, null)) {
                is EnqueueResult.Queued -> messageChannel.trySend(DownloadMessage.Note(DetailsNote.SendingToPc))
                EnqueueResult.DuplicateShare -> messageChannel.trySend(DownloadMessage.Note(DetailsNote.AlreadySending))
                EnqueueResult.PcRemoved -> messageChannel.trySend(DownloadMessage.Error(dev.grabline.app.remote.protocol.ErrorMessages.forCode(dev.grabline.app.remote.protocol.ErrorCode.UNAUTHORIZED)))
            }
        }
    }

    /** "Download on this phone instead": add a phone job for the link, then cancel and remove the PC job. */
    fun downloadHere() {
        val row = state.value.row ?: return
        val engine = phone ?: return
        if (row.isPhone || !state.value.canDownloadHere) return
        viewModelScope.launch {
            engine.add(PhoneAddRequest(url = row.url, title = row.name, folderUri = saveFolder()))
            // The phone job exists now; take the job off the PC (cancel, then remove).
            remote.control(pcId, jobId, "cancel")
            remote.control(pcId, jobId, "remove").onFailure { messageChannel.trySend(DownloadMessage.Error(it.message.orEmpty())) }
            messageChannel.trySend(DownloadMessage.Note(DetailsNote.MovedToPhone))
        }
    }

    fun undo(id: Long) {
        controls.undo(id)
        if (controls.pending.value.none { it.action == RowAction.Remove }) removeAsked = false
    }

    override fun onCleared() {
        controls.flush(flushScope)
    }
}
