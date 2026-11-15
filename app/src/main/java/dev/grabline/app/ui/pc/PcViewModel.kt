package dev.grabline.app.ui.pc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.grabline.app.data.PairedPc
import dev.grabline.app.remote.PcRemote
import dev.grabline.app.remote.protocol.ManualPairing
import dev.grabline.app.remote.protocol.GlobalState
import dev.grabline.app.remote.protocol.QueueInfo
import dev.grabline.app.ui.devices.PcStatus
import dev.grabline.app.ui.devices.SpeedCap
import dev.grabline.app.ui.devices.SpeedUnit
import dev.grabline.app.ui.devices.pcStatusOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PcDialog { None, SpeedCap, DefaultQueue, ConfirmUnpair, EditAddress }

data class PcUiState(
    /** False until the first PC list arrives; [pc] null after that means the pairing is gone. */
    val loaded: Boolean = false,
    val pc: PairedPc? = null,
    val status: PcStatus = PcStatus.Connecting,
    val global: GlobalState? = null,
    val queues: List<QueueInfo> = emptyList(),
    val busy: Boolean = false,
    /** A plain-words failure to show once. */
    val message: String? = null,
    val dialog: PcDialog = PcDialog.None,
    val capText: String = "",
    val capUnit: SpeedUnit = SpeedUnit.MB,
    val capOff: Boolean = false,
    val capError: Boolean = false,
    val addressText: String = "",
    val addressError: Boolean = false,
) {
    /** Today's total is only shown when the PC reports it. */
    val todayBytes: Long? get() = global?.todayBytes
}

class PcViewModel(private val remote: PcRemote, private val pcId: String) : ViewModel() {
    private val local = MutableStateFlow(PcUiState())

    val state: StateFlow<PcUiState> = combine(
        remote.pcs, remote.linkStates, remote.globalStates, local,
    ) { pcs, links, globals, ui ->
        val pc = pcs.firstOrNull { it.pcId == pcId }
        ui.copy(
            loaded = true,
            pc = pc,
            status = pcStatusOf(links[pcId]),
            global = globals[pcId],
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PcUiState())

    init {
        // Load the queue list whenever the PC comes online.
        viewModelScope.launch {
            remote.linkStates.map { pcStatusOf(it[pcId]).isOnline }.distinctUntilChanged().collect { online ->
                if (online) loadQueues()
            }
        }
    }

    private suspend fun loadQueues() {
        remote.queues(pcId).onSuccess { r -> local.update { it.copy(queues = r.queues) } }
    }

    fun retry() = remote.refresh(pcId)

    fun consumeMessage() = local.update { it.copy(message = null) }

    private fun fail(e: Throwable) = local.update { it.copy(busy = false, message = e.message ?: GENERIC_FAILURE) }

    fun pauseOrResumeAll() {
        val paused = state.value.global?.paused ?: false
        runGlobal(if (paused) "resumeAll" else "pauseAll")
    }

    private fun runGlobal(action: String, bytes: Long? = null) {
        viewModelScope.launch {
            local.update { it.copy(busy = true) }
            remote.global(pcId, action, bytes).fold(
                onSuccess = { local.update { s -> s.copy(busy = false, dialog = PcDialog.None) } },
                onFailure = ::fail,
            )
        }
    }

    // Speed cap

    fun openSpeedCap() {
        val limit = state.value.global?.speedLimit
        local.update {
            if (limit == null) it.copy(dialog = PcDialog.SpeedCap, capOff = true, capText = "", capUnit = SpeedUnit.MB, capError = false)
            else SpeedCap.fromBytesPerSecond(limit).let { (text, unit) ->
                it.copy(dialog = PcDialog.SpeedCap, capOff = false, capText = text, capUnit = unit, capError = false)
            }
        }
    }

    fun onCapText(text: String) = local.update { it.copy(capText = text, capError = false) }
    fun onCapUnit(unit: SpeedUnit) = local.update { it.copy(capUnit = unit, capError = false) }
    fun onCapOff(off: Boolean) = local.update { it.copy(capOff = off, capError = false) }

    fun saveSpeedCap() {
        val s = state.value
        if (s.capOff) return runGlobal("clearSpeedLimit")
        val bytes = SpeedCap.toBytesPerSecond(s.capText, s.capUnit)
        if (bytes == null) {
            local.update { it.copy(capError = true) }
            return
        }
        runGlobal("setSpeedLimit", bytes)
    }

    // Default queue

    fun openDefaultQueue() {
        local.update { it.copy(dialog = PcDialog.DefaultQueue) }
        if (state.value.queues.isEmpty()) viewModelScope.launch { loadQueues() }
    }

    /** null: let the PC decide. */
    fun chooseDefaultQueue(queueId: Int?) {
        val pc = state.value.pc ?: return
        viewModelScope.launch {
            remote.addPairing(pc.copy(defaultQueueId = queueId))
            local.update { it.copy(dialog = PcDialog.None) }
        }
    }

    // Edit address

    fun openEditAddress() {
        val pc = state.value.pc ?: return
        val current = pc.lastGoodAddress ?: pc.addresses.firstOrNull().orEmpty()
        local.update { it.copy(dialog = PcDialog.EditAddress, addressText = if (current.isEmpty()) "" else "$current:${pc.port}", addressError = false) }
    }

    fun onAddressText(text: String) = local.update { it.copy(addressText = text, addressError = false) }

    /** Validates `host`, `[v6]` or either with `:port`; the new address goes first and becomes the last good one. */
    fun saveAddress() {
        val pc = state.value.pc ?: return
        val text = state.value.addressText.trim()
        val parsed = ManualPairing.parseAddress(text)
        if (parsed == null) {
            local.update { it.copy(addressError = true) }
            return
        }
        val (address, parsedPort) = parsed
        val explicitPort = text.substringAfterLast(']', text).contains(':')
        val port = if (explicitPort) parsedPort else pc.port
        viewModelScope.launch {
            remote.addPairing(pc.copy(addresses = (listOf(address) + pc.addresses).distinct(), lastGoodAddress = address, port = port))
            local.update { it.copy(dialog = PcDialog.None, addressError = false) }
        }
    }

    // Unpair

    fun askUnpair() = local.update { it.copy(dialog = PcDialog.ConfirmUnpair) }
    fun dismissDialog() = local.update { it.copy(dialog = PcDialog.None) }

    /** Only runs from the confirm dialog's button. */
    fun confirmUnpair(onDone: () -> Unit) {
        if (state.value.dialog != PcDialog.ConfirmUnpair) return
        viewModelScope.launch {
            local.update { it.copy(busy = true, dialog = PcDialog.None) }
            remote.unpair(pcId)
            local.update { it.copy(busy = false) }
            onDone()
        }
    }

    companion object {
        const val GENERIC_FAILURE = "Your PC didn't answer. Try again"
    }
}
