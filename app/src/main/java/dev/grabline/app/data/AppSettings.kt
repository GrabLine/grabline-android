package dev.grabline.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** One setting: its key and the SPEC "Settings" default. */
class Setting<T>(val key: Preferences.Key<T>, val default: T)

/** Settings repository over DataStore Preferences. Defaults follow docs/SPEC.md "Settings". */
class AppSettings(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.settingsStore)

    fun <T> observe(setting: Setting<T>): Flow<T> =
        store.data.map { it[setting.key] ?: setting.default }

    suspend fun <T> set(setting: Setting<T>, value: T) {
        store.edit { it[setting.key] = value }
    }

    suspend fun <T> reset(setting: Setting<T>) {
        store.edit { it.remove(setting.key) }
    }

    companion object {
        /** "ask", "phone", or a PC id. */
        val DEFAULT_DEVICE = Setting(stringPreferencesKey("default_device"), "ask")
        /** Per-type routing rules as JSON (type to "phone" or a PC id); empty means none. */
        val RULES = Setting(stringPreferencesKey("rules"), "")
        val SAVE_FOLDER = Setting(stringPreferencesKey("save_folder"), "Downloads/GrabLine")
        val CONNECTIONS_PER_FILE = Setting(intPreferencesKey("connections_per_file"), 8)
        val DOWNLOADS_AT_ONCE = Setting(intPreferencesKey("downloads_at_once"), 3)
        /** Speed limit in KB/s; 0 means off. */
        val SPEED_LIMIT_KBPS = Setting(intPreferencesKey("speed_limit_kbps"), 0)
        val ONLY_WIFI = Setting(booleanPreferencesKey("only_wifi"), true)
        val ONLY_CHARGING = Setting(booleanPreferencesKey("only_charging"), false)
        /** Retries after a failure: 0 is off, 3, 10, or -1 for forever. */
        val AUTO_RETRY = Setting(intPreferencesKey("auto_retry"), 3)
        /** "ask", "best", "1080p", "720p", "audio". */
        val VIDEO_QUALITY = Setting(stringPreferencesKey("video_quality"), "ask")
        /** "mp3", "m4a", "flac". */
        val AUDIO_FORMAT = Setting(stringPreferencesKey("audio_format"), "mp3")
        /** "ask" or "manual". */
        val UPDATE_YTDLP = Setting(stringPreferencesKey("update_ytdlp"), "ask")
        /** "off", "ratio1", "hour1". */
        val SEED_AFTER_DOWNLOAD = Setting(stringPreferencesKey("seed_after_download"), "off")
        val TORRENTS_ON_MOBILE_DATA = Setting(booleanPreferencesKey("torrents_on_mobile_data"), false)
        val NOTIFY_PC_DOWNLOADS = Setting(booleanPreferencesKey("notify_pc_downloads"), true)
        /** "system", "light", "dark". */
        val THEME = Setting(stringPreferencesKey("theme"), "system")
        /** "grabline" or "wallpaper". */
        val COLOURS = Setting(stringPreferencesKey("colours"), "grabline")
        /** "system" or a BCP 47 language tag. */
        val LANGUAGE = Setting(stringPreferencesKey("language"), "system")
        val OFFER_COPIED_LINKS = Setting(booleanPreferencesKey("offer_copied_links"), true)
        /** "forever", "30d", "7d". */
        val KEEP_HISTORY = Setting(stringPreferencesKey("keep_history"), "forever")
    }
}
