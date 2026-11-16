@file:OptIn(ExperimentalLayoutApi::class)

package dev.grabline.app.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.grabline.app.ui.downloads.DetailsNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.grabline.app.GrabLineApp
import dev.grabline.app.R
import dev.grabline.app.engine.video.EngineUpdateState
import dev.grabline.app.engine.video.VideoText
import dev.grabline.app.engine.PhoneEngine
import dev.grabline.app.remote.PcRemote
import dev.grabline.app.ui.downloads.AndroidStatusStrings
import dev.grabline.app.ui.downloads.DeviceBadge
import dev.grabline.app.ui.downloads.DownloadIcons
import dev.grabline.app.ui.downloads.DownloadMessage
import dev.grabline.app.ui.downloads.DownloadRow
import dev.grabline.app.ui.downloads.RowAction
import dev.grabline.app.ui.downloads.RowProgress
import dev.grabline.app.ui.downloads.RowStatus
import dev.grabline.app.ui.downloads.UNDO_MILLIS
import dev.grabline.app.ui.downloads.actionIcon
import dev.grabline.app.ui.downloads.actionLabel
import dev.grabline.app.ui.downloads.lastSeenText
import dev.grabline.app.ui.downloads.progressSpeech
import dev.grabline.app.ui.downloads.rememberThrottled
import dev.grabline.app.ui.downloads.sizeText
import dev.grabline.app.ui.downloads.speedText
import dev.grabline.app.ui.downloads.statusText
import dev.grabline.app.ui.downloads.timeLeftText
import dev.grabline.app.ui.theme.MonoNumbers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.NumberFormat

/** Works at any width; the lead may show it beside the list above 600 dp. */
@Composable
fun DownloadDetailsRoute(
    remote: PcRemote,
    pcId: String,
    jobId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    phone: PhoneEngine? = null,
) {
    val context = LocalContext.current
    val saveFolder: suspend () -> String? = remember { { (context.applicationContext as? GrabLineApp)?.container?.saveFolderUri() } }
    val vm: DownloadDetailsViewModel = viewModel(
        key = "details:$pcId:$jobId",
        factory = viewModelFactory {
            initializer { DownloadDetailsViewModel(remote, pcId, jobId, createSavedStateHandle(), phone = phone, saveFolder = saveFolder) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val back by rememberUpdatedState(onBack)
    LaunchedEffect(state.leave) { if (state.leave) back() }
    DownloadDetailsScreen(
        state = state,
        messages = vm.messages,
        onAction = vm::act,
        onUndo = vm::undo,
        onBack = onBack,
        modifier = modifier,
        onCopyToPhone = vm::copyToPhone,
        onDownloadHere = vm::downloadHere,
        onSendToPc = vm::sendToPc,
        onSequential = vm::setSequential,
        onFileSelected = vm::setFileSelected,
        onStopSeeding = vm::stopSeeding,
    )
}

/** "This site changed. Update the video engine?" with an Update button; on success the job is retried. */
@Composable
private fun UpdateEnginePrompt(onRetry: () -> Unit) {
    val context = LocalContext.current
    val video = remember { (context.applicationContext as? GrabLineApp)?.container?.video } ?: return
    val scope = rememberCoroutineScope()
    val update by video.updateState.collectAsState()
    val running = update == EngineUpdateState.Running
    Button(
        onClick = {
            scope.launch {
                video.update()
                if (video.updateState.value is EngineUpdateState.Done) onRetry()
            }
        },
        enabled = !running,
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Text(stringResource(if (running) R.string.detail_update_engine_updating else R.string.detail_update_engine))
    }
    (update as? EngineUpdateState.Failed)?.let { Text(it.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
}

@Composable
fun DownloadDetailsScreen(
    state: DetailsUiState,
    messages: Flow<DownloadMessage>,
    onAction: (RowAction) -> Unit,
    onUndo: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onCopyToPhone: () -> Unit = {},
    onDownloadHere: () -> Unit = {},
    onSendToPc: (pcId: String) -> Unit = {},
    onSequential: (Boolean) -> Unit = {},
    onFileSelected: (Int, Boolean) -> Unit = { _, _ -> },
    onStopSeeding: () -> Unit = {},
) {
    val context = LocalContext.current
    val host = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.action_undo)
    val copied = stringResource(R.string.link_copied)
    val clipboard = LocalClipboardManager.current
    val currentUndo by rememberUpdatedState(onUndo)

    LaunchedEffect(messages) {
        messages.collect { m ->
            scope.launch {
                host.currentSnackbarData?.dismiss()
                when (m) {
                    is DownloadMessage.Error -> host.showSnackbar(m.text)
                    is DownloadMessage.Note -> host.showSnackbar(
                        context.getString(
                            when (m.note) {
                                DetailsNote.CopyStarted -> R.string.note_copy_started
                                DetailsNote.SendingToPc -> R.string.note_sending_to_pc
                                DetailsNote.AlreadySending -> R.string.note_already_sending
                                DetailsNote.MovedToPhone -> R.string.note_moved_to_phone
                            },
                        ),
                    )
                    is DownloadMessage.UndoOffer -> {
                        val text = context.getString(
                            if (m.action == RowAction.Cancel) R.string.undo_cancelled_one else R.string.undo_removed_one,
                            m.name,
                        )
                        val r = withTimeoutOrNull(UNDO_MILLIS) {
                            host.showSnackbar(text, undoLabel, duration = SnackbarDuration.Indefinite)
                        }
                        if (r == SnackbarResult.ActionPerformed) currentUndo(m.id)
                    }
                }
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(DownloadIcons.Back, contentDescription = stringResource(R.string.details_back))
                }
                Text(
                    stringResource(R.string.details_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
            }
            val row = state.row
            if (row == null) {
                if (state.gone) {
                    Text(
                        stringResource(R.string.detail_gone),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                val noApp = stringResource(R.string.file_no_app)
                DetailsBody(
                    row, state, onAction,
                    onCopyToPhone = onCopyToPhone, onDownloadHere = onDownloadHere, onSendToPc = onSendToPc,
                    onSequential = onSequential, onFileSelected = onFileSelected, onStopSeeding = onStopSeeding,
                    onCopy = { clipboard.setText(AnnotatedString(row.url)); scope.launch { host.showSnackbar(copied) } },
                    onOpen = { if (!FileActions.open(context, row.fileUri)) scope.launch { host.showSnackbar(noApp) } },
                    onShare = { if (!FileActions.share(context, row.fileUri, row.name)) scope.launch { host.showSnackbar(noApp) } },
                )
            }
        }
        SnackbarHost(host, Modifier.align(Alignment.BottomCenter).padding(8.dp))
    }
}

@Composable
private fun DetailsBody(
    row: DownloadRow,
    state: DetailsUiState,
    onAction: (RowAction) -> Unit,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onCopyToPhone: () -> Unit = {},
    onDownloadHere: () -> Unit = {},
    onSendToPc: (pcId: String) -> Unit = {},
    onSequential: (Boolean) -> Unit = {},
    onFileSelected: (Int, Boolean) -> Unit = { _, _ -> },
    onStopSeeding: () -> Unit = {},
) {
    val context = LocalContext.current
    var choosingPc by remember { mutableStateOf(false) }
    val statuses = AndroidStatusStrings(context)
    if (choosingPc) {
        ChoosePcDialog(state.sendTargets, onPick = { choosingPc = false; onSendToPc(it) }, onDismiss = { choosingPc = false })
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(row.name, style = MaterialTheme.typography.titleMedium)
        Field(stringResource(R.string.detail_status)) {
            Text(if (state.removing) stringResource(R.string.detail_removed) else statusText(row, statuses))
        }
        if (row.status == RowStatus.Failed) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.detail_failed_what_to_try), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(if (row.isPhone) R.string.detail_phone_failed_advice else R.string.detail_failed_advice))
                if (row.isPhone && row.error == VideoText.SITE_CHANGED) UpdateEnginePrompt(onRetry = { onAction(RowAction.Retry) })
            }
        }
        row.notice?.let { notice ->
            Field(stringResource(R.string.detail_notice)) { Text(notice) }
        }
        if (row.offline) {
            Text(lastSeenText(context, row), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.detail_unreachable), style = MaterialTheme.typography.bodySmall)
        }
        // One polite, throttled announcement ("45 percent, 2 minutes left"), never every tick.
        val spoken = rememberThrottled(progressSpeech(context, row).orEmpty(), row.status)
        val progressLabel = stringResource(R.string.detail_progress)
        Field(
            progressLabel,
            Modifier.semantics(mergeDescendants = true) {
                if (spoken.isNotEmpty()) {
                    contentDescription = "$progressLabel, $spoken"
                    liveRegion = LiveRegionMode.Polite
                }
            },
        ) {
            RowProgress(row, Modifier.fillMaxWidth())
            val p = row.progress
            if (p != null) Text(NumberFormat.getPercentInstance().format(p.toDouble()), style = MonoNumbers)
        }
        Field(stringResource(R.string.detail_size)) {
            val total = row.total
            Text(
                when {
                    total != null && total > 0 ->
                        context.getString(R.string.size_of, sizeText(context, row.downloaded), sizeText(context, total))
                    row.downloaded > 0 -> context.getString(R.string.bytes_counted, sizeText(context, row.downloaded))
                    else -> context.getString(R.string.size_unknown)
                },
                style = MonoNumbers,
            )
        }
        if (row.isPhone && row.connections > 0 && (row.status == RowStatus.Downloading || row.status == RowStatus.Queued)) {
            Field(stringResource(R.string.detail_connections)) { Text(row.connections.toString(), style = MonoNumbers) }
        }
        if (row.status == RowStatus.Downloading) {
            Field(stringResource(R.string.detail_speed)) { Text(speedText(context, row.speed), style = MonoNumbers) }
            row.timeLeftSeconds?.let {
                Field(stringResource(R.string.detail_time_left)) { Text(timeLeftText(context, it), style = MonoNumbers) }
            }
        }
        row.torrent?.let { TorrentSection(row, it, onSequential, onFileSelected, onStopSeeding) }
        Field(stringResource(R.string.detail_source)) { SelectionContainer { Text(row.url) } }
        Field(stringResource(R.string.detail_destination)) {
            if (row.isPhone) Text(stringResource(R.string.detail_saved_on_phone))
            else Text(state.queueName?.let { context.getString(R.string.detail_queue_named, it) } ?: stringResource(R.string.detail_queue_default))
        }
        Field(stringResource(R.string.detail_device)) { DeviceBadge(row.device) }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val enabled = !state.removing
            // Primary first: retry for failed rows, otherwise pause or resume.
            row.actions.forEach { a ->
                val primary = a == RowAction.Retry && row.status == RowStatus.Failed
                val content: @Composable () -> Unit = {
                    Icon(actionIcon(a), contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(actionLabel(a), modifier = Modifier.padding(start = 8.dp))
                }
                if (primary) {
                    Button(onClick = { onAction(a) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { content() }
                } else {
                    OutlinedButton(onClick = { onAction(a) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { content() }
                }
            }
            if (row.isPhone && row.status == RowStatus.Done && row.fileUri != null) {
                Button(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_open_file)) }
                OutlinedButton(onClick = onShare, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_share_file)) }
            }
            if (state.canCopyToPhone) {
                Button(onClick = onCopyToPhone, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_copy_to_phone)) }
            }
            if (state.canDownloadHere) {
                OutlinedButton(onClick = onDownloadHere, enabled = !state.removing, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_download_here))
                }
            }
            if (state.sendTargets.isNotEmpty()) {
                OutlinedButton(
                    onClick = { if (state.sendTargets.size == 1) onSendToPc(state.sendTargets.first().pcId) else choosingPc = true },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.action_send_to_pc)) }
            }
            OutlinedButton(onClick = onCopy, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(DownloadIcons.Copy, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.action_copy_link), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun ChoosePcDialog(targets: List<PcChoice>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.send_to_pc_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                targets.forEach { t ->
                    TextButton(onClick = { onPick(t.pcId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(t.name) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun Field(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}
