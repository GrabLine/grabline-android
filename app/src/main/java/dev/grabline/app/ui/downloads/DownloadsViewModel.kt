package dev.grabline.app.ui.downloads

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.grabline.app.data.OutboxItem
import dev.grabline.app.engine.PhoneEngine
import dev.grabline.app.remote.PcRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PcChip(val pcId: String, val name: String)

data class DownloadsUiState(
    /** Rows after the filters. */
    val rows: List<DownloadRow> = emptyList(),
    /** Number of rows before the filters (0 means the empty state). */
    val totalRows: Int = 0,
    val pcChips: List<PcChip> = emptyList(),
    val devices: Set<DeviceFilter> = emptySet(),
    val states: Set<StateFilter> = emptySet(),
    val outboxPending: Int = 0,
    val outboxFailed: List<OutboxItem> = emptyList(),
    val selection: Set<String> = emptySet(),
    val refreshing: Boolean = false,
) {
    val selecting: Boolean get() = selection.isNotEmpty()
    val noFilters: Boolean get() = devices.isEmpty() && states.isEmpty()
    val selectedRows: List<DownloadRow> get() = rows.filter { it.key in selection }
}

sealed interface DownloadMessage {
    data class Error(val text: String) : DownloadMessage
    /** A short confirmation chosen by the screen (translated there). */
    data class Note(val note: DetailsNote) : DownloadMessage
    /** Show "Cancelled X" with Undo; [id] goes to [DownloadsViewModel.undo]. */
    data class UndoOffer(val id: Long, val action: RowAction, val count: Int, val name: String) : DownloadMessage
}

class DownloadsViewModel(
    private val remote: PcRemote,
    private val saved: SavedStateHandle = SavedStateHandle(),
    undoMillis: Long = UNDO_MILLIS,
    /** Other rows (tests, previews). */
    extraRows: Flow<List<DownloadRow>> = flowOf(emptyList()),
    private val flushScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** The phone's own downloads; its jobs join the list as Device.Phone rows. */
    private val phone: PhoneEngine? = null,
) : ViewModel() {

    private val messageChannel = Channel<DownloadMessage>(Channel.UNLIMITED)
    val messages: Flow<DownloadMessage> = messageChannel.receiveAsFlow()

    private val controls = PendingControls(remote, viewModelScope, undoMillis, phone) {
        messageChannel.trySend(DownloadMessage.Error(it))
    }

    private val devices = MutableStateFlow(decodeDevices(saved.get<ArrayList<String>>(KEY_DEVICES)))
    private val states = MutableStateFlow(decodeStates(saved.get<ArrayList<String>>(KEY_STATES)))
    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private val refreshing = MutableStateFlow(false)

    private val extraAll: Flow<List<DownloadRow>> =
        if (phone == null) extraRows else combine(extraRows, phone.jobs) { extra, jobs -> jobs.map(::phoneRow) + extra }

    private val allRows: Flow<List<DownloadRow>> = combine(
        remote.jobs, remote.linkStates, extraAll, controls.pending,
    ) { jobs, links, extra, pending ->
        val removed = pending.filter { it.action == RowAction.Remove }.flatMap { it.targets }.map { it.key }.toSet()
        val cancelled = pending.filter { it.action == RowAction.Cancel }.flatMap { it.targets }.map { it.key }.toSet()
        (extra + jobs.map { toRow(it, links[it.job.pcId]) })
            .filter { it.key !in removed }
            .map { if (it.key in cancelled) it.copy(status = RowStatus.Cancelled, speed = 0, etaSeconds = null) else it }
    }

    private val pcChips: Flow<List<PcChip>> = combine(remote.pcs, allRows) { pcs, rows ->
        val fromPcs = pcs.map { PcChip(it.pcId, it.name) }
        val known = fromPcs.map { it.pcId }.toSet()
        fromPcs + rows.mapNotNull { (it.device as? Device.Pc) }.distinctBy { it.pcId }
            .filter { it.pcId !in known }.map { PcChip(it.pcId, it.name) }
    }

    private data class Outbox(val pending: Int, val failed: List<OutboxItem>)

    private val outbox = combine(remote.outboxPending, remote.outboxFailed) { p, f -> Outbox(p, f) }
    private val filters = combine(devices, states, selection, refreshing) { d, s, sel, r -> listOf(d, s, sel, r) }

    val state: StateFlow<DownloadsUiState> = combine(allRows, pcChips, outbox, filters) { rows, chips, box, f ->
        @Suppress("UNCHECKED_CAST")
        val d = f[0] as Set<DeviceFilter>
        @Suppress("UNCHECKED_CAST")
        val s = f[1] as Set<StateFilter>
        @Suppress("UNCHECKED_CAST")
        val sel = f[2] as Set<String>
        val visible = rows.filter { it.matches(d, s) }
        DownloadsUiState(
            rows = visible,
            totalRows = rows.size,
            pcChips = chips,
            devices = d,
            states = s,
            outboxPending = box.pending,
            outboxFailed = box.failed,
            selection = sel.intersect(visible.map { it.key }.toSet()),
            refreshing = f[3] as Boolean,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    // Filters ------------------------------------------------------------

    fun clearFilters() {
        devices.value = emptySet(); states.value = emptySet(); persist()
    }

    fun toggleDevice(f: DeviceFilter) {
        devices.update { if (f in it) it - f else it + f }; persist()
    }

    fun toggleState(f: StateFilter) {
        states.update { if (f in it) it - f else it + f }; persist()
    }

    private fun persist() {
        saved[KEY_DEVICES] = ArrayList(devices.value.map { it.encode() })
        saved[KEY_STATES] = ArrayList(states.value.map { it.name })
    }

    // Selection ----------------------------------------------------------

    fun toggleSelected(key: String) = selection.update { if (key in it) it - key else it + key }
    fun clearSelection() { selection.value = emptySet() }

    /** Apply [action] to every selected row that allows it, then leave selection. */
    fun actOnSelection(action: RowAction) {
        val rows = state.value.selectedRows.filter { action in it.actions }
        clearSelection()
        dispatch(action, rows)
    }

    // Actions ------------------------------------------------------------

    fun act(row: DownloadRow, action: RowAction) {
        if (action in row.actions) dispatch(action, listOf(row))
    }

    private fun dispatch(action: RowAction, rows: List<DownloadRow>) {
        val targets = rows.map { r -> Target(r.pcId, r.jobId, r.name) }
        val id = controls.request(action, targets) ?: return
        messageChannel.trySend(DownloadMessage.UndoOffer(id, action, targets.size, targets.first().name))
    }

    fun undo(id: Long) = controls.undo(id)

    fun refresh() {
        remote.refresh()
        viewModelScope.launch {
            refreshing.value = true
            delay(1_200)
            refreshing.value = false
        }
    }

    fun dismissFailed(id: Long) {
        viewModelScope.launch { remote.dismissFailed(id) }
    }

    override fun onCleared() {
        controls.flush(flushScope)
    }

    companion object {
        private const val KEY_DEVICES = "devices"
        private const val KEY_STATES = "states"

        private fun decodeDevices(l: List<String>?) = l.orEmpty().mapNotNull { DeviceFilter.decode(it) }.toSet()
        private fun decodeStates(l: List<String>?) = l.orEmpty().mapNotNull { n -> StateFilter.entries.firstOrNull { it.name == n } }.toSet()
    }
}
