package dev.grabline.app.ui.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.grabline.app.remote.PcRemote
import dev.grabline.app.remote.protocol.QueueInfo
import dev.grabline.app.ui.downloads.DownloadMessage
import dev.grabline.app.ui.downloads.DownloadRow
import dev.grabline.app.ui.downloads.PendingControls
import dev.grabline.app.ui.downloads.RowAction
import dev.grabline.app.ui.downloads.RowStatus
import dev.grabline.app.ui.downloads.Target
import dev.grabline.app.ui.downloads.UNDO_MILLIS
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DetailsUiState(
    val loaded: Boolean = false,
    /** Null once the job is gone from the PC. */
    val row: DownloadRow? = null,
    /** Queue name from the PC, when known. */
    val queueName: String? = null,
    /** Remove was asked for and is waiting out its Undo. */
    val removing: Boolean = false,
    /** The user removed this download and it is now gone: leave the screen. */
    val leave: Boolean = false,
) {
    val gone: Boolean get() = loaded && row == null
}

class DownloadDetailsViewModel(
    private val remote: PcRemote,
    private val pcId: String,
    private val jobId: Long,
    saved: SavedStateHandle = SavedStateHandle(),
    undoMillis: Long = UNDO_MILLIS,
    private val flushScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ViewModel() {

    private val messageChannel = Channel<DownloadMessage>(Channel.UNLIMITED)
    val messages: Flow<DownloadMessage> = messageChannel.receiveAsFlow()

    private val controls = PendingControls(remote, viewModelScope, undoMillis) {
        messageChannel.trySend(DownloadMessage.Error(it))
    }

    private val queues = MutableStateFlow<List<QueueInfo>>(emptyList())
    private var lastRow: DownloadRow? = null
    private var removeAsked = false

    val state: StateFlow<DetailsUiState> = combine(
        remote.jobs, remote.linkStates, controls.pending, queues,
    ) { jobs, links, pending, queueList ->
        val found = jobs.firstOrNull { it.job.pcId == pcId && it.job.jobId == jobId }?.let { toRow(it, links[pcId]) }
        val mine = pending.filter { p -> p.targets.any { it.pcId == pcId && it.jobId == jobId } }
        val removing = mine.any { it.action == RowAction.Remove }
        var row = found ?: if (removing) lastRow else null
        if (row != null && mine.any { it.action == RowAction.Cancel }) {
            row = row.copy(status = RowStatus.Cancelled, speed = 0, etaSeconds = null)
        }
        if (found != null) lastRow = found
        DetailsUiState(
            loaded = true,
            row = row,
            queueName = row?.queueId?.let { q -> queueList.firstOrNull { it.id == q }?.name },
            removing = removing,
            leave = found == null && removeAsked && !removing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailsUiState())

    init {
        viewModelScope.launch {
            remote.queues(pcId).getOrNull()?.let { reply ->
                queues.value = reply.queues
            }
        }
    }

    fun act(action: RowAction) {
        val row = state.value.row ?: return
        if (action !in row.actions) return
        if (action == RowAction.Remove) removeAsked = true
        val id = controls.request(action, listOf(Target(pcId, jobId, row.name))) ?: return
        messageChannel.trySend(DownloadMessage.UndoOffer(id, action, 1, row.name))
    }

    fun undo(id: Long) {
        controls.undo(id)
        if (controls.pending.value.none { it.action == RowAction.Remove }) removeAsked = false
    }

    override fun onCleared() {
        controls.flush(flushScope)
    }
}
