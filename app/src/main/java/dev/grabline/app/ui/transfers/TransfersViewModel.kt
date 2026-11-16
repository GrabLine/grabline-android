package dev.grabline.app.ui.transfers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.grabline.app.engine.PhoneEngine
import dev.grabline.app.ui.downloads.DownloadRow
import dev.grabline.app.ui.downloads.RowStatus
import dev.grabline.app.ui.downloads.phoneRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Files being copied from a PC to this phone: the phone jobs of kind "transfer". */
class TransfersViewModel(private val phone: PhoneEngine) : ViewModel() {
    val rows: StateFlow<List<DownloadRow>> = phone.jobs
        .map { jobs -> jobs.filter { it.isTransfer }.map(::phoneRow) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun cancel(jobId: Long) {
        viewModelScope.launch { phone.cancel(jobId) }
    }

    companion object {
        /** Transfers can be cancelled until they finish. */
        fun cancellable(status: RowStatus): Boolean = when (status) {
            RowStatus.Queued, RowStatus.Downloading, RowStatus.Paused,
            RowStatus.WaitingNetwork, RowStatus.WaitingWifi, RowStatus.WaitingCharging -> true
            else -> false
        }
    }
}