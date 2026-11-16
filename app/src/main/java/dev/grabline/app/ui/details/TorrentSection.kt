package dev.grabline.app.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.grabline.app.R
import dev.grabline.app.engine.TorrentDetails
import dev.grabline.app.ui.downloads.DownloadRow
import dev.grabline.app.ui.downloads.RowStatus
import dev.grabline.app.ui.downloads.sizeText
import dev.grabline.app.ui.theme.MonoNumbers
import java.text.NumberFormat

/** Details for a phone torrent: peers, ratio, seeding, the in-order switch and the file list (QA-37). */
@Composable
internal fun TorrentSection(
    row: DownloadRow,
    t: TorrentDetails,
    onSequential: (Boolean) -> Unit,
    onFileSelected: (Int, Boolean) -> Unit,
    onStopSeeding: () -> Unit,
) {
    val context = LocalContext.current
    val finished = row.status == RowStatus.Done
    val ratioText = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2; minimumFractionDigits = 2 }.format(t.ratio.toDouble())
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (row.status == RowStatus.Downloading || t.seeding) {
            TorrentField(stringResource(R.string.detail_torrent_peers)) { Text(t.peers.toString(), style = MonoNumbers) }
        }
        if (t.uploaded > 0 || t.seeding) {
            TorrentField(stringResource(R.string.detail_torrent_ratio)) { Text(ratioText, style = MonoNumbers) }
        }
        if (t.seeding) {
            TorrentField(stringResource(R.string.detail_torrent_seeding)) {
                Text(stringResource(R.string.detail_torrent_seeding_value, ratioText))
            }
            OutlinedButton(onClick = onStopSeeding, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_stop_seeding))
            }
        }
        if (!finished) {
            val label = stringResource(R.string.detail_torrent_sequential)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = t.sequential, role = Role.Switch, onValueChange = onSequential),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(label, modifier = Modifier.weight(1f))
                Switch(checked = t.sequential, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics { })
            }
        }
        if (!finished && t.files.isEmpty() && row.status != RowStatus.Failed) {
            Text(stringResource(R.string.detail_torrent_wait_metadata), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (t.files.isNotEmpty()) {
            TorrentField(stringResource(R.string.detail_torrent_files)) {
                val selectable = !finished && t.files.size > 1
                Column {
                    t.files.forEach { f ->
                        val state = stringResource(if (f.selected) R.string.detail_torrent_file_included else R.string.detail_torrent_file_skipped)
                        val description = stringResource(R.string.detail_torrent_file_description, f.name, sizeText(context, f.size), state)
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .then(
                                    if (selectable) Modifier.toggleable(value = f.selected, role = Role.Checkbox, onValueChange = { onFileSelected(f.index, it) })
                                    else Modifier,
                                )
                                .semantics(mergeDescendants = true) { contentDescription = description },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (selectable) Checkbox(checked = f.selected, onCheckedChange = null, modifier = Modifier.padding(end = 8.dp).clearAndSetSemantics { })
                            Text(f.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(sizeText(context, f.size), style = MonoNumbers, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TorrentField(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}
