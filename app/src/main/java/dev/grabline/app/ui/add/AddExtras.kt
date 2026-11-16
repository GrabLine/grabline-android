package dev.grabline.app.ui.add

import android.content.Context
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.grabline.app.R
import dev.grabline.app.engine.StartLater
import java.util.Date

/** "More formats" (resolution, codec, size), the subtitle choice and the clip trim, all under the quality chips. */
@Composable
internal fun MoreFormats(
    state: AddUiState,
    context: Context,
    onSelectFormat: (String?) -> Unit,
    onSelectSubtitles: (String?) -> Unit,
    onClipStart: (String) -> Unit,
    onClipEnd: (String) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.formatRows.isNotEmpty()) {
            val toggleDesc = stringResource(R.string.add_more_formats_description)
            TextButton(
                onClick = { open = !open },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = toggleDesc },
            ) { Text(stringResource(if (open) R.string.add_more_formats_hide else R.string.add_more_formats)) }
            if (open) {
                Column(Modifier.selectableGroup()) {
                    state.formatRows.forEach { row ->
                        val line = formatLine(row, context)
                        val selected = row.id == state.formatId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected, onClick = { onSelectFormat(row.id) }, role = Role.RadioButton)
                                .semantics { contentDescription = line },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Text(line, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        if (state.subtitleOptions.isNotEmpty()) {
            val none = stringResource(R.string.add_subtitles_none)
            val items = listOf<String?>(null) + state.subtitleOptions.map { it.lang }
            val labels = listOf(none) + state.subtitleOptions.map { FormatMapping.subtitleLabel(it) }
            val current = labels[items.indexOf(state.subtitleLang).coerceAtLeast(0)]
            Menu(stringResource(R.string.add_subtitles), current, labels) { onSelectSubtitles(items[it]) }
        }
        if (state.showClip) {
            Text(stringResource(R.string.add_clip_title), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClipBox(state.clipStart, stringResource(R.string.add_clip_start), !state.clipValid, onClipStart, Modifier.weight(1f))
                ClipBox(state.clipEnd, stringResource(R.string.add_clip_end), !state.clipValid, onClipEnd, Modifier.weight(1f))
            }
            Text(
                stringResource(if (state.clipValid) R.string.add_clip_hint else R.string.add_clip_error),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.clipValid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ClipBox(value: String, label: String, error: Boolean, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.heightIn(min = 48.dp),
    )
}

@Composable
private fun formatLine(row: FormatRow, context: Context): String {
    val resolution = row.height?.let { "${it}p" } ?: stringResource(R.string.add_format_audio_only)
    val size = row.sizeBytes?.let { Formatter.formatShortFileSize(context, it) }
    return listOfNotNull(resolution, row.codec.takeIf { it.isNotBlank() }, row.container.takeIf { it.isNotBlank() }, size)
        .joinToString(" · ")
}

/** The optional pasted SHA-256 (phone downloads). A mismatch afterwards is advisory: the file is kept. */
@Composable
internal fun ChecksumField(state: AddUiState, onChecksum: (String) -> Unit) {
    OutlinedTextField(
        value = state.checksum,
        onValueChange = onChecksum,
        label = { Text(stringResource(R.string.add_checksum_label)) },
        isError = !state.checksumValid,
        supportingText = {
            Text(stringResource(if (state.checksumValid) R.string.add_checksum_hint else R.string.add_checksum_error))
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    )
}

/** "Start later" with a time picker. The chosen time is the next time the clock reads that hour and minute. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StartLaterRow(startAt: Long?, onStartLater: (Long?) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var picking by rememberSaveable { mutableStateOf(false) }
    val timeText = startAt?.let { DateFormat.getTimeFormat(context).format(Date(it)) }
    val switchDesc = stringResource(R.string.add_start_later_description)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.add_start_later), style = MaterialTheme.typography.titleSmall)
            timeText?.let { Text(stringResource(R.string.add_start_later_at, it), style = MaterialTheme.typography.bodyMedium) }
        }
        if (startAt != null) {
            TextButton(onClick = { picking = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.add_start_later_change))
            }
        }
        Switch(
            checked = startAt != null,
            onCheckedChange = { on -> if (on) picking = true else onStartLater(null) },
            modifier = Modifier.semantics { contentDescription = switchDesc },
        )
    }
    if (picking) {
        val now = remember { System.currentTimeMillis() }
        val initial = remember { java.util.Calendar.getInstance().apply { timeInMillis = startAt ?: (now + 60 * 60 * 1000L) } }
        val pickerState = rememberTimePickerState(
            initialHour = initial.get(java.util.Calendar.HOUR_OF_DAY),
            initialMinute = initial.get(java.util.Calendar.MINUTE),
            is24Hour = DateFormat.is24HourFormat(context),
        )
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.add_start_later_pick)) },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        picking = false
                        onStartLater(StartLater.nextOccurrence(pickerState.hour, pickerState.minute, System.currentTimeMillis()))
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.add_start_later_set)) }
            },
            dismissButton = {
                TextButton(onClick = { picking = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.add_start_later_cancel))
                }
            },
        )
    }
}
