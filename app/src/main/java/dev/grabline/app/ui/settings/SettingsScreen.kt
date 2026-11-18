package dev.grabline.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.grabline.app.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.grabline.app.R
import dev.grabline.app.data.AppSettings
import dev.grabline.app.data.PairedPc
import dev.grabline.app.data.Setting
import dev.grabline.app.ui.about.AboutScreen
import dev.grabline.app.ui.about.LicencesScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val PAGE_MAIN = "main"
private const val PAGE_RULES = "rules"
private const val PAGE_ABOUT = "about"
private const val PAGE_LICENCES = "licences"
private const val PAGE_SERVERS = "servers"

@Composable
private fun <T> AppSettings.state(setting: Setting<T>): State<T> =
    remember(this, setting) { observe(setting) }.collectAsState(initial = setting.default)

/** The 14 languages offered, as (BCP 47 tag, name in its own language). */
val LanguageOptions: List<Pair<String, Int>> = listOf(
    "en" to R.string.lang_en, "ar" to R.string.lang_ar, "zh" to R.string.lang_zh,
    "fr" to R.string.lang_fr, "de" to R.string.lang_de, "hi" to R.string.lang_hi,
    "it" to R.string.lang_it, "ja" to R.string.lang_ja, "ko" to R.string.lang_ko,
    "pt" to R.string.lang_pt, "ru" to R.string.lang_ru, "es" to R.string.lang_es,
    "tr" to R.string.lang_tr, "ur" to R.string.lang_ur,
)

fun applyAppLanguage(context: Context, tag: String) = AppLocale.apply(context, tag)

private fun startSafely(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    pcs: List<PairedPc>,
    modifier: Modifier = Modifier,
    onPairPc: () -> Unit = {},
    onLanguageChange: ((String) -> Unit)? = null,
    crashLogDir: File? = null,
) {
    val appContext = LocalContext.current
    val languageChange: (String) -> Unit = onLanguageChange ?: { applyAppLanguage(appContext, it) }
    var page by rememberSaveable { mutableStateOf(PAGE_MAIN) }
    BackHandler(enabled = page != PAGE_MAIN) {
        page = if (page == PAGE_LICENCES) PAGE_ABOUT else PAGE_MAIN
    }
    when (page) {
        PAGE_RULES -> RulesPage(settings, pcs, onBack = { page = PAGE_MAIN }, modifier = modifier)
        PAGE_ABOUT -> AboutScreen(
            onBack = { page = PAGE_MAIN },
            onLicences = { page = PAGE_LICENCES },
            modifier = modifier,
        )
        PAGE_LICENCES -> LicencesScreen(onBack = { page = PAGE_ABOUT }, modifier = modifier)
        PAGE_SERVERS -> ServersScreen(onBack = { page = PAGE_MAIN }, modifier = modifier)
        else -> SettingsMain(
            settings, pcs, modifier, onPairPc, languageChange, crashLogDir,
            onRules = { page = PAGE_RULES },
            onAbout = { page = PAGE_ABOUT },
            onServers = { page = PAGE_SERVERS },
        )
    }
}

@Composable
private fun SettingsMain(
    settings: AppSettings,
    pcs: List<PairedPc>,
    modifier: Modifier,
    onPairPc: () -> Unit,
    onLanguageChange: (String) -> Unit,
    crashLogDir: File?,
    onRules: () -> Unit,
    onAbout: () -> Unit,
    onServers: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun <T> put(setting: Setting<T>, value: T) {
        scope.launch { settings.set(setting, value) }
    }

    var message by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) scope.launch {
            message = try {
                val text = SettingsTransfer.export(settings)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
                }
                context.getString(R.string.settings_exported)
            } catch (e: Exception) {
                context.getString(R.string.settings_export_failed)
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            message = try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        ?: throw IllegalArgumentException()
                }
                val n = SettingsTransfer.import(settings, text)
                val lang = settings.observe(AppSettings.LANGUAGE).first()
                onLanguageChange(lang)
                context.resources.getQuantityString(R.plurals.settings_imported, n, n)
            } catch (e: Exception) {
                context.getString(R.string.settings_import_failed)
            }
        }
    }

    val pickSaveFolder = rememberFolderPicker { put(AppSettings.SAVE_FOLDER, it) }
    val defaultDevice by settings.state(AppSettings.DEFAULT_DEVICE)
    val rules by settings.state(AppSettings.RULES)
    val saveFolder by settings.state(AppSettings.SAVE_FOLDER)
    val connections by settings.state(AppSettings.CONNECTIONS_PER_FILE)
    val atOnce by settings.state(AppSettings.DOWNLOADS_AT_ONCE)
    val speed by settings.state(AppSettings.SPEED_LIMIT_KBPS)
    val onlyWifi by settings.state(AppSettings.ONLY_WIFI)
    val onlyCharging by settings.state(AppSettings.ONLY_CHARGING)
    val autoRetry by settings.state(AppSettings.AUTO_RETRY)
    val videoQuality by settings.state(AppSettings.VIDEO_QUALITY)
    val audioFormat by settings.state(AppSettings.AUDIO_FORMAT)
    val updateYtdlp by settings.state(AppSettings.UPDATE_YTDLP)
    val seed by settings.state(AppSettings.SEED_AFTER_DOWNLOAD)
    val torrentData by settings.state(AppSettings.TORRENTS_ON_MOBILE_DATA)
    val notifyPc by settings.state(AppSettings.NOTIFY_PC_DOWNLOADS)
    val sharePhoneJobs by settings.state(AppSettings.SHARE_PHONE_JOBS)
    val theme by settings.state(AppSettings.THEME)
    val colours by settings.state(AppSettings.COLOURS)
    val language by settings.state(AppSettings.LANGUAGE)
    val offerCopied by settings.state(AppSettings.OFFER_COPIED_LINKS)
    val keepHistory by settings.state(AppSettings.KEEP_HISTORY)

    val deviceOptions = buildList {
        add("ask" to stringResource(R.string.device_ask))
        add("phone" to stringResource(R.string.device_phone))
        pcs.forEach { add(it.pcId to it.name) }
    }
    val ruleCount = RulesCodec.decode(rules).size
    val crashLogs = remember(crashLogDir, message) {
        crashLogDir?.listFiles { f -> f.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text(
            stringResource(R.string.tab_settings),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).semantics { heading() },
        )

        SettingsGroup(stringResource(R.string.group_where)) {
            ChoiceRow(
                stringResource(R.string.setting_default_device), deviceOptions, defaultDevice,
                { put(AppSettings.DEFAULT_DEVICE, it) },
                fallbackLabel = stringResource(R.string.device_unknown),
            )
            ActionRow(
                stringResource(R.string.setting_rules), onRules,
                supporting = if (ruleCount == 0) stringResource(R.string.rules_none)
                else pluralStringResource(R.plurals.rules_count, ruleCount, ruleCount),
            )
        }

        SettingsGroup(stringResource(R.string.group_phone_downloads)) {
            ActionRow(
                stringResource(R.string.setting_save_folder), pickSaveFolder,
                supporting = folderLabel(saveFolder),
            )
            if (saveFolder != AppSettings.SAVE_FOLDER.default) {
                ActionRow(
                    stringResource(R.string.setting_save_folder_reset),
                    { put(AppSettings.SAVE_FOLDER, AppSettings.SAVE_FOLDER.default) },
                )
            }
            SliderRow(stringResource(R.string.setting_connections), connections, 1..16, { put(AppSettings.CONNECTIONS_PER_FILE, it) })
            SliderRow(stringResource(R.string.setting_at_once), atOnce, 1..5, { put(AppSettings.DOWNLOADS_AT_ONCE, it) })
            ChoiceRow(
                stringResource(R.string.setting_speed_limit),
                listOf(
                    0 to stringResource(R.string.speed_off),
                    256 to "256 KB/s", 512 to "512 KB/s", 1024 to "1 MB/s", 2048 to "2 MB/s", 5120 to "5 MB/s",
                ),
                speed, { put(AppSettings.SPEED_LIMIT_KBPS, it) }, fallbackLabel = "$speed KB/s",
            )
            SwitchRow(stringResource(R.string.setting_only_wifi), onlyWifi, { put(AppSettings.ONLY_WIFI, it) })
            SwitchRow(stringResource(R.string.setting_only_charging), onlyCharging, { put(AppSettings.ONLY_CHARGING, it) })
            ChoiceRow(
                stringResource(R.string.setting_auto_retry),
                listOf(
                    0 to stringResource(R.string.retry_off),
                    3 to stringResource(R.string.retry_3),
                    10 to stringResource(R.string.retry_10),
                    -1 to stringResource(R.string.retry_forever),
                ),
                autoRetry, { put(AppSettings.AUTO_RETRY, it) },
            )
        }

        SettingsGroup(stringResource(R.string.group_video)) {
            ChoiceRow(
                stringResource(R.string.setting_video_quality),
                listOf(
                    "ask" to stringResource(R.string.quality_ask),
                    "best" to stringResource(R.string.quality_best),
                    "1080p" to "1080p", "720p" to "720p",
                    "audio" to stringResource(R.string.quality_audio),
                ),
                videoQuality, { put(AppSettings.VIDEO_QUALITY, it) },
            )
            ChoiceRow(
                stringResource(R.string.setting_audio_format),
                listOf("mp3" to "MP3", "m4a" to "M4A", "flac" to "FLAC"),
                audioFormat, { put(AppSettings.AUDIO_FORMAT, it) },
            )
            ChoiceRow(
                stringResource(R.string.setting_update_ytdlp),
                listOf(
                    "ask" to stringResource(R.string.ytdlp_ask),
                    "auto" to stringResource(R.string.ytdlp_auto),
                    "manual" to stringResource(R.string.ytdlp_manual),
                ),
                updateYtdlp, { put(AppSettings.UPDATE_YTDLP, it) },
            )
        }

        SettingsGroup(stringResource(R.string.group_torrents)) {
            ChoiceRow(
                stringResource(R.string.setting_seed),
                listOf(
                    "off" to stringResource(R.string.seed_off),
                    "ratio1" to stringResource(R.string.seed_ratio),
                    "hour1" to stringResource(R.string.seed_hour),
                ),
                seed, { put(AppSettings.SEED_AFTER_DOWNLOAD, it) },
            )
            SwitchRow(stringResource(R.string.setting_torrent_data), torrentData, { put(AppSettings.TORRENTS_ON_MOBILE_DATA, it) })
        }

        SettingsGroup(stringResource(R.string.group_pcs)) {
            ActionRow(
                stringResource(R.string.setting_paired_pcs), onPairPc,
                supporting = if (pcs.isEmpty()) stringResource(R.string.pcs_none)
                else pcs.joinToString(", ") { it.name },
            )
            SwitchRow(stringResource(R.string.setting_notify_pc), notifyPc, { put(AppSettings.NOTIFY_PC_DOWNLOADS, it) })
            SwitchRow(stringResource(R.string.setting_share_phone_jobs), sharePhoneJobs, { put(AppSettings.SHARE_PHONE_JOBS, it) })
        }

        SettingsGroup(stringResource(R.string.group_appearance)) {
            ChoiceRow(
                stringResource(R.string.setting_theme),
                listOf(
                    "system" to stringResource(R.string.theme_system),
                    "light" to stringResource(R.string.theme_light),
                    "dark" to stringResource(R.string.theme_dark),
                ),
                theme, { put(AppSettings.THEME, it) },
            )
            ColourRow(stringResource(R.string.setting_colours), colours) { put(AppSettings.COLOURS, it) }
            ChoiceRow(
                stringResource(R.string.setting_language),
                listOf("system" to stringResource(R.string.theme_system)) +
                    LanguageOptions.map { (tag, res) -> tag to stringResource(res) },
                language, {
                    // Save first: switching the language recreates this screen and would cancel an unfinished save.
                    scope.launch {
                        settings.set(AppSettings.LANGUAGE, it)
                        onLanguageChange(it)
                    }
                },
            )
        }

        SettingsGroup(stringResource(R.string.group_privacy)) {
            SwitchRow(
                stringResource(R.string.setting_offer_copied), offerCopied, { put(AppSettings.OFFER_COPIED_LINKS, it) },
            )
            ChoiceRow(
                stringResource(R.string.setting_keep_history),
                listOf(
                    "forever" to stringResource(R.string.history_forever),
                    "30d" to stringResource(R.string.history_30),
                    "7d" to stringResource(R.string.history_7),
                ),
                keepHistory, { put(AppSettings.KEEP_HISTORY, it) },
            )
        }

        SettingsGroup(stringResource(R.string.group_advanced)) {
            ActionRow(
                stringResource(R.string.setting_battery),
                {
                    startSafely(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                },
                supporting = stringResource(R.string.battery_explain),
            )
            ActionRow(
                stringResource(R.string.setting_export), { exportLauncher.launch("grabline-settings.json") },
                supporting = stringResource(R.string.export_explain),
            )
            ActionRow(
                stringResource(R.string.setting_import),
                { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            )
            if (message.isNotEmpty()) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Text(
                stringResource(R.string.setting_crash_log),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (crashLogs.isEmpty()) {
                Text(
                    stringResource(R.string.crash_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                crashLogs.forEach { f ->
                    ActionRow(f.name, {
                        val body = runCatching { f.readText().take(100_000) }.getOrDefault("")
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, body)
                            .putExtra(Intent.EXTRA_SUBJECT, f.name)
                        startSafely(context, Intent.createChooser(send, null))
                    }, supporting = stringResource(R.string.crash_share))
                }
            }
            ActionRow(stringResource(R.string.setting_reset), { confirmReset = true })
        }

        SettingsGroup(stringResource(R.string.servers_title)) {
            ActionRow(stringResource(R.string.servers_title), onServers, supporting = stringResource(R.string.servers_summary))
        }

        SettingsGroup(stringResource(R.string.group_about)) {
            ActionRow(stringResource(R.string.about_title), onAbout)
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.setting_reset)) },
            text = { Text(stringResource(R.string.reset_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        scope.launch {
                            AppSettings.ALL.forEach { s -> settings.reset(s) }
                            onLanguageChange("system")
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.settings_btn_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.settings_btn_cancel))
                }
            },
        )
    }
}

@Composable
private fun RulesPage(settings: AppSettings, pcs: List<PairedPc>, onBack: () -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val rules by settings.state(AppSettings.RULES)
    val decoded = RulesCodec.decode(rules)
    val options = buildList {
        add("" to stringResource(R.string.rule_default))
        add("phone" to stringResource(R.string.device_phone))
        pcs.forEach { add(it.pcId to it.name) }
    }
    val typeLabels = mapOf(
        "video" to R.string.rule_video, "music" to R.string.rule_music,
        "torrent" to R.string.rule_torrent, "other" to R.string.rule_other,
    )
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BackRow(stringResource(R.string.setting_rules), onBack)
        Text(
            stringResource(R.string.rules_explain),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        RulesCodec.TYPES.forEach { type ->
            ChoiceRow(
                stringResource(typeLabels.getValue(type)), options, decoded[type] ?: "",
                { choice ->
                    scope.launch {
                        settings.set(AppSettings.RULES, RulesCodec.with(rules, type, choice.ifEmpty { null }))
                    }
                },
                fallbackLabel = stringResource(R.string.device_unknown),
            )
        }
    }
}

/** A heading row with a Back button, shared by the Settings sub-pages. */
@Composable
fun BackRow(title: String, onBack: () -> Unit) {
    dev.grabline.app.ui.common.BackTopBar(title, stringResource(R.string.settings_btn_back), onBack)
}

/** Reads settings and PCs itself so the shell only needs one line. */
@Composable
fun SettingsRoute(settings: AppSettings, onPairPc: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val db = remember { dev.grabline.app.data.AppDatabase.get(context) }
    val pcs by remember(db) { db.pcs().observeAll() }.collectAsState(initial = emptyList())
    SettingsScreen(
        settings, pcs, modifier, onPairPc,
        crashLogDir = File(context.filesDir, "crash"),
    )
}
