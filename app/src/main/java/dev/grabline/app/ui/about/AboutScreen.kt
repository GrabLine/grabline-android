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
        InfoRow(stringResource(R.string.about_ytdlp), stringResource(R.string.about_ytdlp_none), mono = true)
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
