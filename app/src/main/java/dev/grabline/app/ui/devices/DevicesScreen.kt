package dev.grabline.app.ui.devices

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.grabline.app.R
import dev.grabline.app.remote.PcRemote
import dev.grabline.app.remote.protocol.ErrorMessages
import dev.grabline.app.ui.common.localizedEngineMessage

@Composable
fun DevicesRoute(
    remote: PcRemote,
    pairing: PairingController,
    onOpenPc: (pcId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: DevicesViewModel = viewModel(
        factory = viewModelFactory {
            initializer { DevicesViewModel(remote, pairing, Build.MODEL.orEmpty().ifBlank { "Android phone" }) }
        },
    )
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val prompt = stringResource(R.string.devices_scan_prompt)

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(vm::onScanned)
    }
    fun scan() = scanner.launch(
        ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setBeepEnabled(false)
            .setOrientationLocked(true)
            .setPrompt(prompt),
    )
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scan() else vm.onCameraDenied()
    }
    val onPair = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scan()
        else permission.launch(Manifest.permission.CAMERA)
    }

    DevicesScreen(
        state = state,
        onPair = onPair,
        onOpenPc = onOpenPc,
        onRefresh = vm::refresh,
        onForm = vm::onForm,
        onSubmitManual = vm::submitManual,
        onCloseManual = vm::closeManual,
        onRetryPairing = vm::retryPairing,
        onDismissPairing = vm::dismissPairing,
        modifier = modifier,
    )
}

@Composable
fun DevicesScreen(
    state: DevicesUiState,
    onPair: () -> Unit,
    onOpenPc: (String) -> Unit,
    onRefresh: () -> Unit,
    onForm: (ManualForm) -> Unit,
    onSubmitManual: () -> Unit,
    onCloseManual: () -> Unit,
    onRetryPairing: () -> Unit,
    onDismissPairing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.manualOpen) {
        ManualEntry(state.form, onForm, onSubmitManual, onCloseManual, modifier)
    } else {
        DeviceList(state, onPair, onOpenPc, onRefresh, modifier)
    }
    state.pairing?.let { PairingDialog(it, onRetryPairing, onDismissPairing) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceList(
    state: DevicesUiState,
    onPair: () -> Unit,
    onOpenPc: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier,
) {
    val rows = state.rows.orEmpty()
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        if (state.rows != null && rows.isEmpty()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.devices_empty_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.devices_empty_body), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(24.dp))
                PairButton(onPair)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Text(
                        stringResource(R.string.devices_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(16.dp).semantics { heading() },
                    )
                }
                items(rows, key = { it.pcId }) { row ->
                    PcRow(row, onClick = { onOpenPc(row.pcId) })
                    HorizontalDivider()
                }
                item { PairButton(onPair, Modifier.padding(16.dp)) }
            }
        }
    }
}

@Composable
private fun PairButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cd = stringResource(R.string.devices_pair_cd)
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = cd },
    ) { Text(stringResource(R.string.devices_pair_button)) }
}

@Composable
private fun PcRow(row: PcRowUi, onClick: () -> Unit) {
    val status = stringResource(row.status.labelRes())
    val cd = stringResource(R.string.devices_row_cd, row.name, status)
    val jobs = if (row.status.isOnline) {
        if (row.activeJobs > 0) pluralStringResource(R.plurals.devices_active_jobs_plural, row.activeJobs, row.activeJobs)
        else stringResource(R.string.devices_no_active)
    } else null
    val version = row.version?.let { stringResource(R.string.devices_version, it) }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = cd },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(row.name, style = MaterialTheme.typography.titleMedium)
        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val extra = listOfNotNull(version, jobs).joinToString(" · ")
        if (extra.isNotEmpty()) {
            Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PairingDialog(progress: PairingProgress, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val title: String
    val body: String?
    when (progress) {
        PairingProgress.Connecting -> { title = stringResource(R.string.pairing_connecting); body = null }
        PairingProgress.ConfirmOnPc -> {
            title = stringResource(R.string.pairing_confirm_on_pc); body = stringResource(R.string.pairing_confirm_on_pc_body)
        }
        is PairingProgress.Paired -> { title = stringResource(R.string.pairing_success, progress.pcName); body = null }
        is PairingProgress.Failed -> { title = stringResource(R.string.pairing_failed_title); body = localizedEngineMessage(progress.message) }
    }
    val busy = progress is PairingProgress.Connecting || progress is PairingProgress.ConfirmOnPc
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = body?.let { { Text(it) } },
        confirmButton = {
            when {
                progress is PairingProgress.Paired ->
                    TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.pairing_done)) }
                progress is PairingProgress.Failed && progress.canRetry ->
                    TextButton(onRetry, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.pairing_retry)) }
                progress is PairingProgress.Failed ->
                    TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.pairing_close)) }
            }
        },
        dismissButton = {
            if (busy || (progress is PairingProgress.Failed && progress.canRetry)) {
                TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.pairing_cancel)) }
            }
        },
    )
}

@Composable
private fun ManualEntry(
    form: ManualForm,
    onForm: (ManualForm) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.manual_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.manual_intro), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = form.address,
            onValueChange = { onForm(form.copy(address = it)) },
            label = { Text(stringResource(R.string.manual_address)) },
            placeholder = { Text(stringResource(R.string.manual_address_hint)) },
            isError = form.addressError,
            supportingText = if (form.addressError) { { Text(stringResource(R.string.error_bad_address)) } } else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.port,
            onValueChange = { onForm(form.copy(port = it.filter(Char::isDigit).take(5))) },
            label = { Text(stringResource(R.string.manual_port)) },
            isError = form.portError,
            supportingText = if (form.portError) { { Text(stringResource(R.string.manual_port_error)) } } else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.code,
            onValueChange = { onForm(form.copy(code = it.take(24))) },
            label = { Text(stringResource(R.string.manual_code)) },
            placeholder = { Text(stringResource(R.string.manual_code_hint)) },
            isError = form.codeError,
            supportingText = if (form.codeError) { { Text(ErrorMessages.CODE_DOES_NOT_LOOK_RIGHT) } } else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onSubmit, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.manual_submit)) }
            TextButton(onBack, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.manual_back)) }
        }
    }
}
