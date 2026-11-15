@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package dev.grabline.app.ui.downloads

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.grabline.app.R
import dev.grabline.app.data.OutboxItem
import dev.grabline.app.remote.PcRemote
import dev.grabline.app.ui.entry.PasteBox
import dev.grabline.app.ui.theme.MonoNumbers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Downloads tab. [selectedRowKey] highlights the open row in the two-pane layout
 * (see [rowKey]); [onLinksPasted] receives links typed or pasted into the empty state.
 */
@Composable
fun DownloadsRoute(
    remote: PcRemote,
    onOpenDetails: (pcId: String, jobId: Long) -> Unit,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLinksPasted: (List<String>) -> Unit = {},
    selectedRowKey: String? = null,
) {
    val vm: DownloadsViewModel = viewModel(
        factory = viewModelFactory { initializer { DownloadsViewModel(remote, createSavedStateHandle()) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    DownloadsScreen(
        state = state,
        messages = vm.messages,
        actions = DownloadsActions(
            onOpen = { r -> r.pcId?.let { onOpenDetails(it, r.jobId) } },
            onRowAction = vm::act,
            onToggleSelect = vm::toggleSelected,
            onSelectionAction = vm::actOnSelection,
            onClearSelection = vm::clearSelection,
            onToggleDevice = vm::toggleDevice,
            onToggleState = vm::toggleState,
            onClearFilters = vm::clearFilters,
            onUndo = vm::undo,
            onRefresh = vm::refresh,
            onDismissFailed = vm::dismissFailed,
            onLinksPasted = onLinksPasted,
            onAddClick = onAddClick,
        ),
        selectedRowKey = selectedRowKey,
        modifier = modifier,
    )
}

class DownloadsActions(
    val onOpen: (DownloadRow) -> Unit,
    val onRowAction: (DownloadRow, RowAction) -> Unit,
    val onToggleSelect: (String) -> Unit,
    val onSelectionAction: (RowAction) -> Unit,
    val onClearSelection: () -> Unit,
    val onToggleDevice: (DeviceFilter) -> Unit,
    val onToggleState: (StateFilter) -> Unit,
    val onClearFilters: () -> Unit,
    val onUndo: (Long) -> Unit,
    val onRefresh: () -> Unit,
    val onDismissFailed: (Long) -> Unit,
    val onLinksPasted: (List<String>) -> Unit,
    val onAddClick: () -> Unit,
)

@Composable
fun DownloadsScreen(
    state: DownloadsUiState,
    messages: Flow<DownloadMessage>,
    actions: DownloadsActions,
    modifier: Modifier = Modifier,
    selectedRowKey: String? = null,
) {
    val context = LocalContext.current
    val host = androidx.compose.runtime.remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.action_undo)
    val currentActions by rememberUpdatedState(actions)

    LaunchedEffect(messages) {
        messages.collect { m ->
            scope.launch {
                host.currentSnackbarData?.dismiss()
                when (m) {
                    is DownloadMessage.Error -> host.showSnackbar(m.text)
                    is DownloadMessage.UndoOffer -> {
                        val res = context.resources
                        val text = when {
                            m.count == 1 && m.action == RowAction.Cancel -> res.getString(R.string.undo_cancelled_one, m.name)
                            m.count == 1 -> res.getString(R.string.undo_removed_one, m.name)
                            m.action == RowAction.Cancel -> res.getQuantityString(R.plurals.undo_cancelled_many, m.count, m.count)
                            else -> res.getQuantityString(R.plurals.undo_removed_many, m.count, m.count)
                        }
                        val result = withTimeoutOrNull(UNDO_MILLIS) {
                            host.showSnackbar(text, undoLabel, duration = SnackbarDuration.Indefinite)
                        }
                        if (result == SnackbarResult.ActionPerformed) currentActions.onUndo(m.id)
                    }
                }
            }
        }
    }

    BackHandler(enabled = state.selecting) { actions.onClearSelection() }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (state.selecting) SelectionBar(state, actions) else Header()
            FilterRow(state, actions)
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = actions.onRefresh,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                DownloadList(state, actions, selectedRowKey)
            }
        }
        SnackbarHost(
            host,
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = 8.dp, end = 80.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun Header() {
    Text(
        stringResource(R.string.downloads_title),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp).padding(top = 12.dp),
    )
}

@Composable
private fun SelectionBar(state: DownloadsUiState, actions: DownloadsActions) {
    val rows = state.selectedRows
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions.onClearSelection) {
            Icon(DownloadIcons.Close, contentDescription = stringResource(R.string.selection_clear))
        }
        Text(
            pluralStringResource(R.plurals.selected_count, rows.size, rows.size),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        listOf(RowAction.Pause, RowAction.Resume, RowAction.Cancel, RowAction.Remove).forEach { a ->
            IconButton(onClick = { actions.onSelectionAction(a) }, enabled = rows.any { a in it.actions }) {
                Icon(actionIcon(a), contentDescription = actionLabel(a))
            }
        }
    }
}

@Composable
private fun FilterRow(state: DownloadsUiState, actions: DownloadsActions) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item { Chip(stringResource(R.string.chip_all), state.noFilters, actions.onClearFilters) }
        item {
            Chip(
                stringResource(R.string.chip_phone),
                DeviceFilter.Phone in state.devices,
            ) { actions.onToggleDevice(DeviceFilter.Phone) }
        }
        items(state.pcChips, key = { it.pcId }) { pc ->
            val f = DeviceFilter.Pc(pc.pcId)
            Chip(pc.name, f in state.devices) { actions.onToggleDevice(f) }
        }
        item { Chip(stringResource(R.string.chip_active), StateFilter.Active in state.states) { actions.onToggleState(StateFilter.Active) } }
        item { Chip(stringResource(R.string.chip_done), StateFilter.Done in state.states) { actions.onToggleState(StateFilter.Done) } }
        item { Chip(stringResource(R.string.chip_failed), StateFilter.Failed in state.states) { actions.onToggleState(StateFilter.Failed) } }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(DownloadIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else null,
    )
}

@Composable
private fun DownloadList(state: DownloadsUiState, actions: DownloadsActions, selectedRowKey: String?) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.outboxPending > 0 || state.outboxFailed.isNotEmpty()) {
            item(key = "outbox") { OutboxBanner(state.outboxPending, state.outboxFailed, actions.onDismissFailed) }
        }
        when {
            state.totalRows == 0 -> item(key = "empty") { EmptyState(actions) }
            state.rows.isEmpty() -> item(key = "nomatch") {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.downloads_no_match), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = actions.onClearFilters, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.downloads_clear_filters))
                    }
                }
            }
            else -> items(state.rows, key = { it.key }) { row ->
                DownloadRowItem(
                    row = row,
                    selected = row.key in state.selection,
                    selecting = state.selecting,
                    highlighted = row.key == selectedRowKey,
                    onClick = { if (state.selecting) actions.onToggleSelect(row.key) else actions.onOpen(row) },
                    onLongClick = { actions.onToggleSelect(row.key) },
                    onAction = { actions.onRowAction(row, it) },
                )
            }
        }
    }
}

@Composable
private fun EmptyState(actions: DownloadsActions) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PasteBox(onLinks = actions.onLinksPasted)
        Text(
            stringResource(R.string.downloads_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = actions.onAddClick, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.downloads_add_link))
        }
    }
}

@Composable
private fun OutboxBanner(pending: Int, failed: List<OutboxItem>, onDismiss: (Long) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (pending > 0) {
                Text(stringResource(R.string.outbox_waiting), style = MaterialTheme.typography.titleSmall)
                Text(pluralStringResource(R.plurals.outbox_count, pending, pending), style = MaterialTheme.typography.bodyMedium)
            }
            failed.forEach { item ->
                val reason = item.lastError?.takeIf { it.isNotBlank() } ?: stringResource(R.string.outbox_failed_generic)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.outbox_failed_item, item.title ?: item.url, reason),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    val desc = stringResource(R.string.outbox_dismiss_description, item.title ?: item.url)
                    TextButton(
                        onClick = { onDismiss(item.id) },
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = desc },
                    ) { Text(stringResource(R.string.outbox_dismiss)) }
                }
            }
        }
    }
}

@Composable
fun DownloadRowItem(
    row: DownloadRow,
    selected: Boolean,
    selecting: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAction: (RowAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val currentRow by rememberUpdatedState(row)
    val currentOnAction by rememberUpdatedState(onAction)
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            val r = currentRow
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> r.swipeStartAction?.let(currentOnAction)
                SwipeToDismissBoxValue.EndToStart -> r.swipeEndAction?.let(currentOnAction)
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false // the row itself changes through the data; never leave it half-dismissed
        },
    )
    val description = rowDescription(context, row)
    val spoken = rememberThrottled(description, row.status)
    val selectedLabel = stringResource(R.string.row_selected)
    val labelsByAction = RowAction.entries.associateWith { actionLabelPlain(context, it) }

    SwipeToDismissBox(
        state = swipe,
        modifier = modifier,
        enableDismissFromStartToEnd = !selecting && row.swipeStartAction != null,
        enableDismissFromEndToStart = !selecting && row.swipeEndAction != null,
        backgroundContent = {
            val dir = swipe.dismissDirection
            val a = if (dir == SwipeToDismissBoxValue.StartToEnd) row.swipeStartAction else row.swipeEndAction
            if (a != null && dir != SwipeToDismissBoxValue.Settled) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    horizontalArrangement = if (dir == SwipeToDismissBoxValue.StartToEnd) Arrangement.Start else Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(actionIcon(a), contentDescription = null)
                    Text(labelsByAction.getValue(a), modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    ) {
        val container = when {
            selected -> MaterialTheme.colorScheme.secondaryContainer
            highlighted -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.surface
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = container),
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = if (row.offline) 0.6f else 1f }
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .semantics(mergeDescendants = true) {
                    contentDescription = if (selected) "$selectedLabel, $spoken" else spoken
                    liveRegion = LiveRegionMode.Polite
                    this.selected = selected
                    customActions = row.actions.map { a ->
                        CustomAccessibilityAction(labelsByAction.getValue(a)) { currentOnAction(a); true }
                    }
                },
        ) {
            RowBody(row, selected)
        }
    }
}

private fun actionLabelPlain(context: android.content.Context, a: RowAction): String = context.getString(
    when (a) {
        RowAction.Pause -> R.string.action_pause
        RowAction.Resume -> R.string.action_resume
        RowAction.Cancel -> R.string.action_cancel
        RowAction.Retry -> R.string.action_retry
        RowAction.Remove -> R.string.action_remove
    },
)

@Composable
private fun RowBody(row: DownloadRow, selected: Boolean) {
    val context = LocalContext.current
    val statusStrings = AndroidStatusStrings(context)
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Icon(DownloadIcons.Check, contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(20.dp))
            }
            Text(
                row.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DeviceBadge(row.device)
            Text(statusText(row, statusStrings), style = MaterialTheme.typography.labelMedium)
        }
        if (row.status == RowStatus.Downloading || row.status == RowStatus.Paused || row.status == RowStatus.Queued) {
            RowProgress(row, Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val counted = if (row.total != null && row.total > 0) {
                    context.getString(R.string.size_of, sizeText(context, row.downloaded), sizeText(context, row.total))
                } else {
                    context.getString(R.string.bytes_counted, sizeText(context, row.downloaded))
                }
                Text(counted, style = MonoNumbers)
                if (row.status == RowStatus.Downloading && row.speed > 0) Text(speedText(context, row.speed), style = MonoNumbers)
                row.timeLeftSeconds?.let { Text(timeLeftText(context, it), style = MonoNumbers) }
            }
        } else if (row.status == RowStatus.Done && row.total != null && row.total > 0) {
            Text(sizeText(context, row.total), style = MonoNumbers)
        }
        if (row.offline) {
            Text(lastSeenText(context, row), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
