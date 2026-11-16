package dev.grabline.app.ui.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.grabline.app.GrabLineApp
import dev.grabline.app.R
import dev.grabline.app.engine.cloud.CloudAccount
import dev.grabline.app.engine.cloud.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_KEY_BYTES = 64 * 1024

/** Settings > Servers: saved logins for SFTP, FTP, FTPS, WebDAV and S3, and the SSH servers this phone trusts. */
@Composable
fun ServersScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as? GrabLineApp)?.container }
    val store = container?.cloudAccounts
    val hostKeys = container?.db?.sshHostKeys()
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf<List<CloudAccount>>(emptyList()) }
    var editing by remember { mutableStateOf<CloudAccount?>(null) }
    var adding by remember { mutableStateOf(false) }
    suspend fun reload() { accounts = store?.list().orEmpty() }
    LaunchedEffect(store) { reload() }
    val trusted by (hostKeys?.observeAll() ?: remember { kotlinx.coroutines.flow.flowOf(emptyList()) }).collectAsState(initial = emptyList())

    BackHandler(enabled = adding || editing != null) { adding = false; editing = null }
    if ((adding || editing != null) && store != null) {
        ServerEditor(store, editing, onDone = { adding = false; editing = null; scope.launch { reload() } }, modifier = modifier)
        return
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackRow(stringResource(R.string.servers_title), onBack)
        Text(
            stringResource(R.string.servers_note), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
        )
        Button(
            onClick = { adding = true },
            modifier = Modifier.padding(16.dp).heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.servers_add)) }
        if (accounts.isEmpty()) {
            Text(
                stringResource(R.string.servers_empty), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        accounts.forEach { a ->
            val title = a.label.ifBlank { listOf(a.username.ifBlank { null }, a.host).filterNotNull().joinToString("@") }
            ActionRow(
                title, { editing = a },
                supporting = a.service.uppercase() + " - " + a.host + if (a.port > 0) ":${a.port}" else "",
            )
        }
        if (trusted.isNotEmpty()) {
            SettingsGroup(stringResource(R.string.servers_trusted), note = stringResource(R.string.servers_trusted_note)) {
                trusted.forEach { k ->
                    val name = "${k.host}:${k.port}"
                    val forgetDesc = stringResource(R.string.server_forget_description, name)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                            Text(k.sha256, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(
                            onClick = { scope.launch(Dispatchers.IO) { hostKeys?.forget(k.host, k.port) } },
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = forgetDesc },
                        ) { Text(stringResource(R.string.server_forget)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerEditor(store: CredentialStore, account: CloudAccount?, onDone: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var form by remember { mutableStateOf(ServerForm()) }
    var loaded by remember { mutableStateOf(account == null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }
    var importFailed by remember { mutableStateOf(false) }
    LaunchedEffect(account) {
        if (account != null) {
            val hasKey = store.secretsFor(account)?.privateKey?.isNotEmpty() == true
            form = ServerForm.from(account, hasKey)
            loaded = true
        }
    }
    val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { readUpTo(it, MAX_KEY_BYTES + 1) }
                        ?.takeIf { it.size <= MAX_KEY_BYTES }?.toString(Charsets.UTF_8)
                }.getOrNull()
            }
            if (text == null) importFailed = true else { importFailed = false; form = form.copy(privateKey = text) }
        }
    }
    if (!loaded) return
    val problem = if (tried) form.problem() else null
    val isS3 = form.service == "s3"
    val isSftp = form.service == "sftp"
    val services = CloudAccount.SERVICES.map { it to serviceName(it) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackRow(stringResource(if (account == null) R.string.servers_add else R.string.servers_edit), onDone)
        ChoiceRow(
            stringResource(R.string.server_type), services, form.service,
            { form = form.copy(service = it, port = "", useKey = it == "sftp" && form.useKey) },
            enabled = account == null,
        )
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(stringResource(R.string.server_label), form.label, { form = form.copy(label = it) })
            Field(
                stringResource(if (isS3) R.string.server_bucket else R.string.server_host), form.host, { form = form.copy(host = it) },
                error = if (problem == ServerForm.Problem.HOST) stringResource(R.string.server_error_host) else null,
                keyboard = KeyboardType.Uri, enabled = account == null,
            )
            if (!isS3) {
                Field(
                    stringResource(R.string.server_port), form.port, { form = form.copy(port = it.filter(Char::isDigit).take(5)) },
                    error = if (problem == ServerForm.Problem.PORT) stringResource(R.string.server_error_port) else null,
                    keyboard = KeyboardType.Number,
                )
            }
            Field(
                stringResource(if (isS3) R.string.server_access_key else R.string.server_user), form.user, { form = form.copy(user = it) },
                enabled = account == null,
            )
            if (isSftp) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val pw = !form.useKey
                    (if (pw) Button({ }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.server_auth_password)) }
                    else OutlinedButton({ form = form.copy(useKey = false) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.server_auth_password)) })
                    (if (!pw) Button({ }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.server_auth_key)) }
                    else OutlinedButton({ form = form.copy(useKey = true) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.server_auth_key)) })
                }
            }
            if (isSftp && form.useKey) {
                Field(
                    stringResource(R.string.server_private_key), form.privateKey, { form = form.copy(privateKey = it) },
                    secret = true, lines = 4,
                    error = if (problem == ServerForm.Problem.KEY) stringResource(R.string.server_error_key) else null,
                    hint = if (form.keepKey) stringResource(R.string.server_secret_kept) else null,
                )
                OutlinedButton(onClick = { pickKey.launch("*/*") }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.server_import_key))
                }
                if (importFailed) Text(stringResource(R.string.server_import_failed), color = MaterialTheme.colorScheme.error)
                Field(stringResource(R.string.server_passphrase), form.passphrase, { form = form.copy(passphrase = it) }, secret = true)
            } else {
                Field(
                    stringResource(if (isS3) R.string.server_secret_key else R.string.server_password), form.password,
                    { form = form.copy(password = it) }, secret = true,
                    hint = if (account?.hasSecrets == true) stringResource(R.string.server_secret_kept) else null,
                )
            }
            if (isS3) {
                Field(
                    stringResource(R.string.server_endpoint), form.endpoint, { form = form.copy(endpoint = it) },
                    keyboard = KeyboardType.Uri, hint = stringResource(R.string.server_endpoint_hint),
                    error = if (problem == ServerForm.Problem.ENDPOINT) stringResource(R.string.server_error_endpoint) else null,
                )
                Field(stringResource(R.string.server_region), form.region, { form = form.copy(region = it) }, hint = stringResource(R.string.server_region_hint))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        tried = true
                        if (form.problem() == null) scope.launch {
                            store.save(form.account(account?.id ?: 0L), form.secrets())
                            onDone()
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.server_save)) }
                OutlinedButton(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_btn_cancel)) }
                if (account != null) {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.server_delete)) }
                }
            }
        }
    }
    if (confirmDelete && account != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.server_delete)) },
            text = { Text(stringResource(R.string.server_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = { confirmDelete = false; scope.launch { store.delete(account.id); onDone() } },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.server_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.settings_btn_cancel))
                }
            },
        )
    }
}

private fun readUpTo(input: java.io.InputStream, limit: Int): ByteArray {
    val buf = ByteArray(limit)
    var n = 0
    while (n < limit) {
        val r = input.read(buf, n, limit - n)
        if (r < 0) break
        n += r
    }
    return buf.copyOf(n)
}

private fun serviceName(service: String) = when (service) {
    "sftp" -> "SFTP"
    "ftp" -> "FTP"
    "ftps" -> "FTPS"
    "webdav" -> "WebDAV"
    else -> "S3"
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    secret: Boolean = false,
    error: String? = null,
    hint: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
    lines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = lines == 1,
        minLines = lines,
        isError = error != null,
        supportingText = { (error ?: hint)?.let { Text(it) } },
        visualTransformation = if (secret && lines == 1) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else keyboard,
            autoCorrectEnabled = false,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
