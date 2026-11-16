package dev.grabline.app.ui.about

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.grabline.app.BuildConfig
import dev.grabline.app.GrabLineApp
import dev.grabline.app.engine.video.EngineUpdateState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import dev.grabline.app.R
import dev.grabline.app.ui.settings.ActionRow
import dev.grabline.app.ui.settings.BackRow
import dev.grabline.app.ui.settings.InfoRow
import dev.grabline.app.ui.settings.SettingsGroup

const val SOURCE_URL = "https://github.com/GrabLine/grabline-android"
const val SUPPORT_URL = "https://github.com/GrabLine/grabline-android/issues"

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit, onLicences: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackRow(stringResource(R.string.about_title), onBack)
        InfoRow(stringResource(R.string.about_version), BuildConfig.VERSION_NAME, mono = true)
        YtDlpSection()
        SettingsGroup(stringResource(R.string.about_links)) {
            ActionRow(stringResource(R.string.about_licences), onLicences)
            ActionRow(
                stringResource(R.string.about_source), { openUrl(context, SOURCE_URL) },
                supporting = SOURCE_URL,
            )
            ActionRow(
                stringResource(R.string.about_support), { openUrl(context, SUPPORT_URL) },
                supporting = SUPPORT_URL,
            )
        }
    }
}

/** The yt-dlp version and "Update yt-dlp" with progress, the result, or the failure and a Retry. */
@Composable
private fun YtDlpSection() {
    val context = LocalContext.current
    val video = remember { (context.applicationContext as? GrabLineApp)?.container?.video }
    val idle = remember { MutableStateFlow<EngineUpdateState>(EngineUpdateState.Idle) }
    val update by (video?.updateState ?: idle).collectAsState()
    var version by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(video, update) { version = video?.version() }
    InfoRow(stringResource(R.string.about_ytdlp), version ?: stringResource(R.string.about_ytdlp_none), mono = true)
    if (video == null) return
    when (val u = update) {
        EngineUpdateState.Running -> {
            val desc = stringResource(R.string.about_ytdlp_update_progress_description)
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.about_ytdlp_updating), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = desc })
            }
        }
        is EngineUpdateState.Failed -> {
            Text(
                u.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            ActionRow(stringResource(R.string.about_ytdlp_retry), { scope.launch { video.update() } })
        }
        else -> {
            if (u is EngineUpdateState.Done) {
                Text(
                    stringResource(if (u.changed) R.string.about_ytdlp_updated else R.string.about_ytdlp_current, u.version.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            ActionRow(
                stringResource(R.string.about_ytdlp_update), { scope.launch { video.update() } },
                supporting = stringResource(R.string.about_ytdlp_update_supporting),
            )
        }
    }
}

@Composable
fun LicencesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ofl = remember {
        runCatching {
            context.resources.openRawResource(R.raw.ofl_plex).bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackRow(stringResource(R.string.about_licences), onBack)
        InfoRow(stringResource(R.string.lic_grabline), stringResource(R.string.lic_agpl))
        InfoRow(stringResource(R.string.lic_okhttp), stringResource(R.string.lic_apache))
        InfoRow(stringResource(R.string.lic_zxing), stringResource(R.string.lic_apache))
        InfoRow(stringResource(R.string.lic_ytdlp), stringResource(R.string.lic_unlicense))
        InfoRow(stringResource(R.string.lic_ffmpeg), stringResource(R.string.lic_lgpl))
        InfoRow(stringResource(R.string.lic_androidx), stringResource(R.string.lic_apache))
        InfoRow(stringResource(R.string.lic_plex), stringResource(R.string.lic_ofl))
        if (ofl.isNotEmpty()) {
            Text(
                ofl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
