package dev.grabline.app.ui.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import dev.grabline.app.ui.theme.Button
import dev.grabline.app.ui.theme.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.grabline.app.R

@Composable
fun PasteBox(onLinks: (List<String>) -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val links = remember(text) { IncomingLinks.extract(text) }
    val pasteDesc = stringResource(R.string.paste_button_description)
    val goDesc = stringResource(R.string.go_button_description)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.paste_a_link)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 1,
            maxLines = 6,
            supportingText = {
                if (text.isNotBlank() && links.isEmpty()) Text(stringResource(R.string.paste_no_link_found))
            },
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { clipboard.getText()?.text?.let { text = it } },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = pasteDesc },
            ) { Text(stringResource(R.string.paste_button)) }
            Button(
                onClick = { onLinks(links) },
                enabled = links.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = goDesc },
            ) { Text(stringResource(R.string.go_button)) }
        }
    }
}
