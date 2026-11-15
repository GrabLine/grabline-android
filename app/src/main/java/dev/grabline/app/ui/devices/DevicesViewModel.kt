package dev.grabline.app.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.grabline.app.remote.PcRemote
import dev.grabline.app.remote.protocol.PairingUri
import dev.grabline.app.remote.protocol.PairingUriResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PcRowUi(
    val pcId: String,
    val name: String,
    val status: PcStatus,
    val version: String?,
    val activeJobs: Int,
)

data class DevicesUiState(
    /** null until the first list arrives. */
    val rows: List<PcRowUi>? = null,
    val refreshing: Boolean = false,
    val manualOpen: Boolean = false,
    val form: ManualForm = ManualForm(),
    val pairing: PairingProgress? = null,
)

class DevicesViewModel(
    private val remote: PcRemote,
    private val pairing: PairingController,
    private val deviceName: String,
) : ViewModel() {
    private val local = MutableStateFlow(DevicesUiState())
    private var pairJob: Job? = null
    private var lastEntry: PairingEntry? = null

    val state: StateFlow<DevicesUiState> = combine(remote.pcs, remote.linkStates, remote.globalStates, local) { pcs, links, globals, ui ->
        ui.copy(
            rows = pcs.map { pc ->
                val link = links[pc.pcId]
                val status = pcStatusOf(link)
                PcRowUi(
                    pcId = pc.pcId,
                    name = pc.name,
                    status = status,
                    version = (link as? dev.grabline.app.remote.connection.PcLinkState.Online)?.appVersion?.takeIf { it.isNotBlank() }
                        ?: pc.pcAppVersion?.takeIf { it.isNotBlank() },
                    activeJobs = if (status.isOnline) globals[pc.pcId]?.active ?: 0 else 0,
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DevicesUiState())

    fun refresh() {
        local.update { it.copy(refreshing = true) }
        remote.refresh()
        viewModelScope.launch {
            kotlinx.coroutines.delay(800)
            local.update { it.copy(refreshing = false) }
        }
    }

    /** A QR code was scanned. */
    fun onScanned(text: String) {
        when (val parsed = PairingUri.parse(text)) {
            is PairingUriResult.Invalid ->
                local.update { it.copy(pairing = PairingProgress.Failed(parsed.error.userMessage, canRetry = false)) }
            is PairingUriResult.Valid -> start(PairingEntry.Scanned(text))
        }
    }

    fun onCameraDenied() = local.update { it.copy(manualOpen = true) }
    fun openManual() = local.update { it.copy(manualOpen = true) }
    fun closeManual() = local.update { it.copy(manualOpen = false, form = ManualForm()) }

    fun onForm(form: ManualForm) = local.update { it.copy(form = form.copy(addressError = false, portError = false, codeError = false)) }

    fun submitManual() {
        val checked = local.value.form.validated()
        val text = if (checked.hasError) null else checked.entryText()
        if (text == null) {
            local.update { it.copy(form = checked) }
            return
        }
        start(PairingEntry.Manual(text))
    }

    private fun start(entry: PairingEntry) {
        lastEntry = entry
        pairJob?.cancel()
        local.update { it.copy(pairing = PairingProgress.Connecting) }
        pairJob = viewModelScope.launch {
            try {
                pairing.pair(entry, deviceName).collect { p ->
                    local.update { it.copy(pairing = p) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                local.update {
                    it.copy(pairing = PairingProgress.Failed("Your PC didn't answer. Check it is on the same Wi-Fi and try again", true))
                }
            }
        }
    }

    fun retryPairing() {
        val entry = lastEntry ?: return dismissPairing()
        start(entry)
    }

    /** Close the dialog; cancels a pairing still in progress. After success the manual screen closes too. */
    fun dismissPairing() {
        pairJob?.cancel()
        val done = local.value.pairing is PairingProgress.Paired
        lastEntry = null
        local.update {
            it.copy(pairing = null, manualOpen = if (done) false else it.manualOpen, form = if (done) ManualForm() else it.form)
        }
    }
}
