package dev.grabline.app.ui.pc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.grabline.app.R
import dev.grabline.app.remote.PcRemote
import dev.grabline.app.ui.common.localizedEngineMessage
import dev.grabline.app.ui.devices.Formatting
import dev.grabline.app.ui.devices.PcStatus
import dev.grabline.app.ui.devices.SpeedUnit
import dev.grabline.app.ui.devices.labelRes

@Composable
fun PcRoute(remote: PcRemote, pcId: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: PcViewModel = viewModel(
        key = "pc-$pcId",
        factory = viewModelFactory { initializer { PcViewModel(remote, pcId) } },
    )
    val state by vm.state.collectAsState()
    PcScreen(
        state = state,
        onBack = onBack,
        onRetry = vm::retry,
        onPauseResume = vm::pauseOrResumeAll,
        onOpenCap = vm::openSpeedCap,
        onCapText = vm::onCapText,
        onCapUnit = vm::onCapUnit,
        onCapOff = vm::onCapOff,
        onSaveCap = vm::saveSpeedCap,
        onOpenQueue = vm::openDefaultQueue,
        onChooseQueue = vm::chooseDefaultQueue,
        onAskUnpair = vm::askUnpair,
        onOpenEditAddress = vm::openEditAddress,
        onAddressText = vm::onAddressText,
        onSaveAddress = vm::saveAddress,
        onConfirmUnpair = { vm.confirmUnpair(onBack) },
        onDismissDialog = vm::dismissDialog,
        modifier = modifier,
    )
}

@Composable
fun PcScreen(
    state: PcUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onPauseResume: () -> Unit,
    onOpenCap: () -> Unit,
    onCapText: (String) -> Unit,
    onCapUnit: (SpeedUnit) -> Unit,
    onCapOff: (Boolean) -> Unit,
    onSaveCap: () -> Unit,
    onOpenQueue: () -> Unit,
    onChooseQueue: (Int?) -> Unit,
    onAskUnpair: () -> Unit,
    onConfirmUnpair: () -> Unit,
    onDismissDialog: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenEditAddress: () -> Unit = {},
    onAddressText: (String) -> Unit = {},
    onSaveAddress: () -> Unit = {},
) {
    val pc = state.pc
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        TextButton(onBack, Modifier.heightIn(min = 48.dp).padding(horizontal = 8.dp)) { Text(stringResource(R.string.pc_back)) }
        if (pc == null) {
            if (state.loaded) {
                Text(stringResource(R.string.pc_unknown), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
            }
            return@Column
        }
        Text(
            pc.name,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
        )
        Text(
            stringResource(state.status.labelRes()),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        state.message?.let {
            Text(localizedEngineMessage(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        if (state.status.isOnline) {
            OnlineSection(state, onPauseResume, onOpenCap, onOpenQueue)
        } else if (state.status == PcStatus.Offline || state.status == PcStatus.Connecting) {
            OfflineTips(onRetry)
        } else {
            OutlinedButton(onRetry, Modifier.padding(16.dp).heightIn(min = 48.dp)) { Text(stringResource(R.string.pc_retry)) }
        }

        TextButton(onOpenEditAddress, Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.pc_edit_address))
        }
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        TextButton(onAskUnpair, Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.pc_remove), color = MaterialTheme.colorScheme.error)
        }
    }

    when (state.dialog) {
        PcDialog.None -> Unit
        PcDialog.ConfirmUnpair -> AlertDialog(
            onDismissRequest = onDismissDialog,
            title = { Text(stringResource(R.string.pc_remove_title)) },
            text = { Text(stringResource(R.string.pc_remove_body, pc?.name.orEmpty())) },
            confirmButton = {
                TextButton(onConfirmUnpair, Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.pc_remove_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onDismissDialog, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.pc_cancel)) }
            },
        )
        PcDialog.SpeedCap -> SpeedCapDialog(state, onCapText, onCapUnit, onCapOff, onSaveCap, onDismissDialog)
        PcDialog.DefaultQueue -> DefaultQueueDialog(state, onChooseQueue, onDismissDialog)
        PcDialog.EditAddress -> EditAddressDialog(state, onAddressText, onSaveAddress, onDismissDialog)
    }
}

@Composable
private fun OnlineSection(state: PcUiState, onPauseResume: () -> Unit, onOpenCap: () -> Unit, onOpenQueue: () -> Unit) {
    val global = state.global
    val paused = global?.paused ?: false
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        global?.let {
            StatLine(stringResource(R.string.pc_speed_now), speedText(it.speed))
            state.todayBytes?.let { today -> StatLine(stringResource(R.string.pc_today), sizeText(today)) }
        }
        Button(onPauseResume, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !state.busy && global != null) {
            Text(stringResource(if (paused) R.string.pc_resume_all else R.string.pc_pause_all))
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    SettingRow(
        stringResource(R.string.pc_speed_cap),
        global?.speedLimit?.let { speedText(it) } ?: stringResource(R.string.pc_speed_cap_off),
        onOpenCap,
    )
    val defaultName = state.pc?.defaultQueueId?.let { id -> state.queues.firstOrNull { it.id == id.toLong() }?.name }
    SettingRow(stringResource(R.string.pc_default_queue), defaultName ?: stringResource(R.string.pc_default_queue_none), onOpenQueue)
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    Text(
        stringResource(R.string.pc_queues),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
    )
    if (state.queues.isEmpty()) {
        Text(stringResource(R.string.pc_queues_none), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        state.queues.forEach { q ->
            val s = stringResource(if (q.paused) R.string.pc_queue_paused else R.string.pc_queue_running)
            val cd = stringResource(R.string.pc_queue_row, q.name, s)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp).semantics(mergeDescendants = true) { contentDescription = cd },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(q.name, Modifier.weight(1f))
                Text(s, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OfflineTips(onRetry: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.pc_offline_tips_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text("• " + stringResource(R.string.pc_tip_wifi))
        Text("• " + stringResource(R.string.pc_tip_awake))
        Text("• " + stringResource(R.string.pc_tip_running))
        Text("• " + stringResource(R.string.pc_tip_listener))
        OutlinedButton(onRetry, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.pc_retry)) }
    }
}

@Composable
private fun SpeedCapDialog(
    state: PcUiState,
    onText: (String) -> Unit,
    onUnit: (SpeedUnit) -> Unit,
    onOff: (Boolean) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pc_speed_cap_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pc_speed_cap_off_switch), Modifier.weight(1f))
                    Switch(checked = state.capOff, onCheckedChange = onOff)
                }
                if (!state.capOff) {
                    OutlinedTextField(
                        value = state.capText,
                        onValueChange = onText,
                        label = { Text(stringResource(R.string.pc_speed_cap_value)) },
                        isError = state.capError,
                        supportingText = if (state.capError) { { Text(stringResource(R.string.pc_speed_cap_error)) } } else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        UnitChoice(stringResource(R.string.pc_speed_cap_unit_kb), state.capUnit == SpeedUnit.KB) { onUnit(SpeedUnit.KB) }
                        UnitChoice(stringResource(R.string.pc_speed_cap_unit_mb), state.capUnit == SpeedUnit.MB) { onUnit(SpeedUnit.MB) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onSave, Modifier.heightIn(min = 48.dp), enabled = !state.busy) { Text(stringResource(R.string.pc_speed_cap_save)) }
        },
        dismissButton = { TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.pc_cancel)) } },
    )
}

@Composable
private fun EditAddressDialog(state: PcUiState, onText: (String) -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pc_edit_address_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pc_edit_address_body))
                OutlinedTextField(
                    value = state.addressText,
                    onValueChange = onText,
                    label = { Text(stringResource(R.string.pc_edit_address_label)) },
                    isError = state.addressError,
                    supportingText = if (state.addressError) { { Text(stringResource(R.string.pc_edit_address_error)) } } else null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onSave, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.pc_edit_address_save)) } },
        dismissButton = { TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.pc_cancel)) } },
    )
}

@Composable
private fun UnitChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp, end = 8.dp))
    }
}

@Composable
private fun DefaultQueueDialog(state: PcUiState, onChoose: (Int?) -> Unit, onDismiss: () -> Unit) {
    val current = state.pc?.defaultQueueId
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pc_default_queue_dialog_title)) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                QueueChoice(stringResource(R.string.pc_default_queue_none), current == null) { onChoose(null) }
                state.queues.forEach { q ->
                    QueueChoice(q.name, current == q.id.toInt()) { onChoose(q.id.toInt()) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.pc_cancel)) } },
    )
}

@Composable
private fun QueueChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun sizeText(bytes: Long): String {
    val s = Formatting.size(bytes)
    return when (s.unit) {
        Formatting.Unit.GB -> stringResource(R.string.pc_size_gb, s.text)
        Formatting.Unit.MB -> stringResource(R.string.pc_size_mb, s.text)
        Formatting.Unit.KB -> stringResource(R.string.pc_size_kb, s.text)
        Formatting.Unit.B -> stringResource(R.string.pc_size_b, s.whole)
    }
}

@Composable
private fun speedText(bytesPerSecond: Long): String {
    val s = Formatting.size(bytesPerSecond)
    return when (s.unit) {
        Formatting.Unit.GB -> stringResource(R.string.pc_speed_unit_mbs, Formatting.size(bytesPerSecond).let { String.format(java.util.Locale.getDefault(), "%.0f", bytesPerSecond / 1048576.0) })
        Formatting.Unit.MB -> stringResource(R.string.pc_speed_unit_mbs, s.text)
        Formatting.Unit.KB -> stringResource(R.string.pc_speed_unit_kbs, s.text)
        Formatting.Unit.B -> stringResource(R.string.pc_speed_unit_bs, s.whole)
    }
}
