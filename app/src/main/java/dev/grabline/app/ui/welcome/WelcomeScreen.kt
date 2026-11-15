package dev.grabline.app.ui.welcome

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.grabline.app.R
import dev.grabline.app.data.AppSettings
import dev.grabline.app.ui.settings.folderLabel
import kotlinx.coroutines.launch

private fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}

/**
 * First-run screen. Skip and Pair a PC both set the welcomeDone flag; [onPairPc] is called
 * only for Pair a PC, [onFinished] for either.
 */
@Composable
fun WelcomeScreen(
    settings: AppSettings,
    onPairPc: () -> Unit,
    onFinished: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notificationsOn by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var denied by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf<String?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsOn = granted || NotificationManagerCompat.from(context).areNotificationsEnabled()
        denied = !notificationsOn
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                folder = uri.toString()
                scope.launch { settings.set(AppSettings.SAVE_FOLDER, uri.toString()) }
            } catch (_: SecurityException) {
            }
        }
    }

    fun finish(pair: Boolean) {
        if (pair) onPairPc()
        onFinished()
        scope.launch { settings.set(AppSettings.WELCOME_DONE, true) }
    }

    val needsRuntimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val buttonMod = Modifier.fillMaxWidth().heightIn(min = 48.dp)

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            WelcomeCard(R.string.welcome_card1_title, R.string.welcome_card1_body)
            WelcomeCard(R.string.welcome_card2_title, R.string.welcome_card2_body)
            WelcomeCard(R.string.welcome_card3_title, R.string.welcome_card3_body)

            if (needsRuntimePermission && !notificationsOn) {
                Text(stringResource(R.string.welcome_notify_reason), style = MaterialTheme.typography.bodyMedium)
            }
            if (notificationsOn) {
                Text(
                    stringResource(R.string.welcome_notify_on),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            } else {
                Button(
                    onClick = {
                        if (needsRuntimePermission && !denied) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            openAppNotificationSettings(context)
                        }
                    },
                    modifier = buttonMod,
                ) { Text(stringResource(R.string.welcome_allow_notifications)) }
            }
            if (denied && !notificationsOn) {
                Text(stringResource(R.string.welcome_notify_denied), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { openAppNotificationSettings(context) }, modifier = buttonMod) {
                    Text(stringResource(R.string.welcome_open_settings))
                }
            }

            OutlinedButton(onClick = { folderLauncher.launch(null) }, modifier = buttonMod) {
                Text(stringResource(R.string.welcome_pick_folder))
            }
            Text(
                stringResource(R.string.welcome_folder_current, folderLabel(folder ?: "Downloads/GrabLine")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(onClick = { finish(true) }, modifier = buttonMod) {
                Text(stringResource(R.string.welcome_pair_pc))
            }
            TextButton(onClick = { finish(false) }, modifier = buttonMod) {
                Text(stringResource(R.string.welcome_skip))
            }
        }
    }
}

@Composable
private fun WelcomeCard(title: Int, body: Int) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
