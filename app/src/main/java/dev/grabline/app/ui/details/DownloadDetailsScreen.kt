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
import androidx.compose.material3.Button
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
import dev.grabline.app.R
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
) {
    val vm: DownloadDetailsViewModel = viewModel(
        key = "details:$pcId:$jobId",
        factory = viewModelFactory {
            initializer { DownloadDetailsViewModel(remote, pcId, jobId, createSavedStateHandle()) }
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
    )
}

@Composable
fun DownloadDetailsScreen(
    state: DetailsUiState,
    messages: Flow<DownloadMessage>,
    onAction: (RowAction) -> Unit,
    onUndo: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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
                DetailsBody(row, state, onAction) { clipboard.setText(AnnotatedString(row.url)); scope.launch { host.showSnackbar(copied) } }
            }
        }
        SnackbarHost(host, Modifier.align(Alignment.BottomCenter).padding(8.dp))
    }
}

@Composable
private fun DetailsBody(row: DownloadRow, state: DetailsUiState, onAction: (RowAction) -> Unit, onCopy: () -> Unit) {
    val context = LocalContext.current
    val statuses = AndroidStatusStrings(context)
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
                Text(stringResource(R.string.detail_failed_advice))
            }
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
        if (row.status == RowStatus.Downloading) {
            Field(stringResource(R.string.detail_speed)) { Text(speedText(context, row.speed), style = MonoNumbers) }
            row.timeLeftSeconds?.let {
                Field(stringResource(R.string.detail_time_left)) { Text(timeLeftText(context, it), style = MonoNumbers) }
            }
        }
        Field(stringResource(R.string.detail_source)) { SelectionContainer { Text(row.url) } }
        Field(stringResource(R.string.detail_destination)) {
            Text(state.queueName?.let { context.getString(R.string.detail_queue_named, it) } ?: stringResource(R.string.detail_queue_default))
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
            OutlinedButton(onClick = onCopy, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(DownloadIcons.Copy, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.action_copy_link), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun Field(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}
