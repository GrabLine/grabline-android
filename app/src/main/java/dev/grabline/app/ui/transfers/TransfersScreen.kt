package dev.grabline.app.ui.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import dev.grabline.app.ui.theme.Button
import androidx.compose.material3.MaterialTheme
import dev.grabline.app.ui.theme.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.grabline.app.R
import dev.grabline.app.engine.PhoneEngine
import dev.grabline.app.ui.details.FileActions
import dev.grabline.app.ui.downloads.AndroidStatusStrings
import dev.grabline.app.ui.downloads.DownloadRow
import dev.grabline.app.ui.downloads.RowProgress
import dev.grabline.app.ui.downloads.RowStatus
import dev.grabline.app.ui.downloads.sizeText
import dev.grabline.app.ui.downloads.statusText
import dev.grabline.app.ui.theme.MonoNumbers
import kotlinx.coroutines.launch

@Composable
fun TransfersRoute(phone: PhoneEngine?, modifier: Modifier = Modifier) {
    if (phone == null) return
    val vm: TransfersViewModel = viewModel(factory = viewModelFactory { initializer { TransfersViewModel(phone) } })
    val rows by vm.rows.collectAsStateWithLifecycle()
    TransfersScreen(rows, onCancel = vm::cancel, modifier = modifier)
}

/** Copies from a PC: progress, Cancel while running, Open when done. */
@Composable
fun TransfersScreen(rows: List<DownloadRow>, onCancel: (Long) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val (host, sharedHost) = dev.grabline.app.ui.common.rememberAppSnackbar()
    val scope = rememberCoroutineScope()
    val noApp = stringResource(R.string.file_no_app)
    val statuses = remember(context) { AndroidStatusStrings(context) }
    Column(modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.transfers_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp).semantics { heading() },
        )
        if (rows.isEmpty()) {
            Text(stringResource(R.string.transfers_empty), modifier = Modifier.padding(horizontal = 16.dp))
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(rows, key = { it.key }) { row ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(row.name, style = MaterialTheme.typography.titleMedium)
                    val status = statusText(row, statuses)
                    val sizes = row.total?.takeIf { it > 0 }
                        ?.let { context.getString(R.string.size_of, sizeText(context, row.downloaded), sizeText(context, it)) }
                        ?: sizeText(context, row.downloaded)
                    RowProgress(row, Modifier.fillMaxWidth())
                    Text(
                        "$status · $sizes",
                        style = MonoNumbers,
                        modifier = Modifier.semantics {
                            contentDescription = context.getString(R.string.transfers_progress_description, row.name, "$status, $sizes")
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (TransfersViewModel.cancellable(row.status)) {
                            OutlinedButton(
                                onClick = { onCancel(row.jobId) },
                                modifier = Modifier.heightIn(min = 48.dp).semantics {
                                    contentDescription = context.getString(R.string.transfers_cancel_description, row.name)
                                },
                            ) { Text(stringResource(R.string.transfers_cancel)) }
                        }
                        if (row.status == RowStatus.Done && row.fileUri != null) {
                            Button(
                                onClick = { if (!FileActions.open(context, row.fileUri)) scope.launch { host.showSnackbar(noApp) } },
                                modifier = Modifier.heightIn(min = 48.dp).semantics {
                                    contentDescription = context.getString(R.string.transfers_open_description, row.name)
                                },
                            ) { Text(stringResource(R.string.transfers_open)) }
                        }
                    }
                }
            }
        }
        if (!sharedHost) SnackbarHost(host, Modifier.padding(8.dp))
    }
}