package dev.grabline.app.ui.add

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.grabline.app.R
import dev.grabline.app.data.AppSettings
import dev.grabline.app.remote.PcRemote
import dev.grabline.app.ui.entry.LinkKind
import androidx.lifecycle.createSavedStateHandle

/**
 * The Add sheet. Phase 1: PCs are the only targets ("This phone" is shown disabled).
 * [onSent] gets the snackbar text; [onDismiss] is called after it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSheetRoute(
    remote: PcRemote,
    settings: AppSettings,
    initialLinks: List<String>,
    sourceTitle: String?,
    onDismiss: () -> Unit,
    onSent: (String) -> Unit = {},
    onPairClick: () -> Unit = {},
) {
    val vm: AddViewModel = viewModel(
        key = "add:" + initialLinks.joinToString("\n"),
        factory = viewModelFactory {
            initializer { AddViewModel(remote, settings, createSavedStateHandle()) }
        },
    )
    LaunchedEffect(initialLinks, sourceTitle) { vm.init(initialLinks, sourceTitle) }
    val state by vm.state.collectAsState()

    val sentTo = stringResource(R.string.add_sent_to, state.sent?.pcName ?: "")
    val later = stringResource(R.string.add_will_send_later)
    LaunchedEffect(state.sent) {
        val sent = state.sent ?: return@LaunchedEffect
        onSent(if (sent.outcome == SendOutcome.SENT) sentTo else later)
        vm.consumeSent()
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        AddSheetContent(
            state = state,
            onSelectPc = vm::selectPc,
            onSelectQuality = vm::selectQuality,
            onSelectQueue = vm::selectQueue,
            onSelectFolder = vm::selectFolder,
            onToggleLinks = vm::toggleLinks,
            onRetry = vm::retryAnalyze,
            onStart = vm::startNow,
            onPairClick = onPairClick,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddSheetContent(
    state: AddUiState,
    onSelectPc: (String) -> Unit,
    onSelectQuality: (String) -> Unit,
    onSelectQueue: (Int?) -> Unit,
    onSelectFolder: (String?) -> Unit,
    onToggleLinks: () -> Unit,
    onRetry: () -> Unit,
    onStart: () -> Unit,
    onPairClick: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.add_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        if (!state.ready) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }
        LinkSummary(state, onToggleLinks)

        if (state.notDownloadable) {
            Text(stringResource(R.string.add_not_downloadable), style = MaterialTheme.typography.bodyLarge)
            val openDesc = stringResource(R.string.add_open_in_browser_description)
            OutlinedButton(
                onClick = { openInBrowser(context, state.links.firstOrNull()) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = openDesc },
            ) { Text(stringResource(R.string.add_open_in_browser)) }
            return@Column
        }

        if (state.pcs.isEmpty()) {
            Text(stringResource(R.string.add_pair_first), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onPairClick, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.add_pair_button))
            }
            return@Column
        }

        Section(stringResource(R.string.add_download_on)) {
            DeviceChoice(state, onSelectPc)
            if (!state.pcOnline) {
                Text(
                    stringResource(R.string.add_offline_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.showQuality) {
            Section(stringResource(R.string.add_quality)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    state.qualityOptions.forEach { wire ->
                        QualityChip(wire, wire == state.quality, state.sizes[wire], context) { onSelectQuality(wire) }
                    }
                }
                when (val a = state.analysis) {
                    AnalysisUi.Loading -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.add_analysing), style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    is AnalysisUi.Failed -> Column {
                        Text(a.message, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.add_analyse_failed_best),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.add_analyse_retry))
                        }
                    }
                    else -> Unit
                }
            }
        }

        if (state.queues.isNotEmpty()) {
            val default = stringResource(R.string.add_queue_default)
            val items = listOf<Pair<Int?, String>>(null to default) + state.queues.map { it.id.toInt() to it.name }
            val current = items.firstOrNull { it.first == state.queueId }?.second ?: default
            Menu(stringResource(R.string.add_queue), current, items.map { it.second }) { index ->
                onSelectQueue(items[index].first)
            }
        }
        if (state.folders.size > 1) {
            val current = state.folders.firstOrNull { it.id == (state.folderId ?: state.folders.first().id) }?.name.orEmpty()
            Menu(stringResource(R.string.add_folder), current, state.folders.map { it.name }) { index ->
                onSelectFolder(state.folders[index].id)
            }
        }

        val startDesc = stringResource(R.string.add_start_now_description)
        Button(
            onClick = onStart,
            enabled = state.canStart && !state.sending,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = startDesc },
        ) {
            Text(stringResource(if (state.sending) R.string.add_sending else R.string.add_start_now))
        }
        state.notice?.let {
            Text(
                stringResource(if (it == Notice.ALREADY_SENT) R.string.add_already_sent else R.string.add_send_failed),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun LinkSummary(state: AddUiState, onToggle: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        if (state.links.size == 1) {
            Text(state.links[0], maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(stringResource(R.string.add_links_count, state.links.size), style = MaterialTheme.typography.bodyLarge)
            val toggleDesc = stringResource(R.string.add_links_toggle_description)
            TextButton(onClick = onToggle, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = toggleDesc }) {
                Text(stringResource(if (state.linksExpanded) R.string.add_links_hide else R.string.add_links_show))
            }
            if (state.linksExpanded) {
                state.links.forEach {
                    Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        state.kind?.let { kind ->
            val label = stringResource(kindLabel(kind))
            val desc = stringResource(R.string.add_kind_description, label)
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { contentDescription = desc })
        }
    }
}

private fun kindLabel(kind: LinkKind): Int = when (kind) {
    LinkKind.MAGNET, LinkKind.TORRENT -> R.string.kind_torrent
    LinkKind.VIDEO -> R.string.kind_video
    LinkKind.FILE -> R.string.kind_file
    LinkKind.PAGE -> R.string.kind_page
}

@Composable
private fun DeviceChoice(state: AddUiState, onSelect: (String) -> Unit) {
    val online = stringResource(R.string.add_pc_online)
    val offline = stringResource(R.string.add_pc_offline)
    val phone = stringResource(R.string.add_this_phone)
    val phoneLater = stringResource(R.string.add_phone_later)
    val phoneDesc = stringResource(R.string.add_phone_description)
    val big = LocalDensity.current.fontScale > 1.3f
    if (state.pcs.size <= 1 && !big) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val count = state.pcs.size + 1
            state.pcs.forEachIndexed { index, pc ->
                val status = if (pc.online) online else offline
                val desc = stringResource(R.string.add_pc_description, pc.name, status)
                SegmentedButton(
                    selected = pc.id == state.selectedPcId,
                    onClick = { onSelect(pc.id) },
                    shape = SegmentedButtonDefaults.itemShape(index, count),
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = desc },
                    label = { Column { Text(pc.name, maxLines = 2); Text(status, style = MaterialTheme.typography.labelSmall) } },
                )
            }
            SegmentedButton(
                selected = false,
                onClick = {},
                enabled = false,
                shape = SegmentedButtonDefaults.itemShape(count - 1, count),
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = phoneDesc },
                label = { Column { Text(phone, maxLines = 2); Text(phoneLater, style = MaterialTheme.typography.labelSmall) } },
            )
        }
    } else {
        Column(Modifier.selectableGroup()) {
            state.pcs.forEach { pc ->
                val status = if (pc.online) online else offline
                val desc = stringResource(R.string.add_pc_description, pc.name, status)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(pc.id == state.selectedPcId, onClick = { onSelect(pc.id) }, role = Role.RadioButton)
                        .semantics { contentDescription = desc },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = pc.id == state.selectedPcId, onClick = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(pc.name, style = MaterialTheme.typography.bodyLarge)
                        Text(status, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = phoneDesc },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = false, onClick = null, enabled = false)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(phone, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(phoneLater, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun QualityChip(wire: String, selected: Boolean, size: Long?, context: Context, onClick: () -> Unit) {
    val name = if (wire == "best") stringResource(R.string.add_quality_best) else wire.let {
        if (it.endsWith("p")) it else it.uppercase()
    }
    val sizeText = size?.let { Formatter.formatShortFileSize(context, it) }
    val desc = if (sizeText != null) {
        stringResource(R.string.add_quality_description_size, name, sizeText)
    } else {
        stringResource(R.string.add_quality_description, name)
    }
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(if (sizeText != null) "$name · $sizeText" else name) },
        modifier = Modifier.semantics { contentDescription = desc },
    )
}

@Composable
private fun Menu(title: String, current: String, items: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val desc = stringResource(R.string.add_menu_description, title, current)
    Section(title) {
        Box {
            OutlinedButton(
                onClick = { open = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = desc },
            ) { Text(current, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                items.forEachIndexed { index, name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = { open = false; onPick(index) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
    }
}

private fun openInBrowser(context: Context, link: String?) {
    if (link == null) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}
