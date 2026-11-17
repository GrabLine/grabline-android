package dev.grabline.app.ui.settings

import dev.grabline.app.data.AppSettings
import dev.grabline.app.data.Setting
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Exports and imports user settings as a small JSON document. */
object SettingsTransfer {
    const val FORMAT_VERSION = 1

    private val allowed: Map<String, Set<String>> = mapOf(
        AppSettings.THEME.key.name to setOf("system", "light", "dark"),
        AppSettings.COLOURS.key.name to setOf("grabline", "violet", "green", "orange", "rose", "teal", "wallpaper"),
        AppSettings.KEEP_HISTORY.key.name to setOf("forever", "30d", "7d"),
        AppSettings.VIDEO_QUALITY.key.name to setOf("ask", "best", "1080p", "720p", "audio"),
        AppSettings.AUDIO_FORMAT.key.name to setOf("mp3", "m4a", "flac"),
        AppSettings.UPDATE_YTDLP.key.name to setOf("ask", "manual"),
        AppSettings.SEED_AFTER_DOWNLOAD.key.name to setOf("off", "ratio1", "hour1"),
    )
    private val intRanges: Map<String, IntRange> = mapOf(
        AppSettings.CONNECTIONS_PER_FILE.key.name to 1..16,
        AppSettings.DOWNLOADS_AT_ONCE.key.name to 1..5,
        AppSettings.SPEED_LIMIT_KBPS.key.name to 0..10_000_000,
        AppSettings.AUTO_RETRY.key.name to -1..10,
    )

    /** A content:// folder grant does not travel to another install, so it is not exported. */
    private fun exportable(setting: Setting<*>, value: Any?): Boolean =
        !(setting.key.name == AppSettings.SAVE_FOLDER.key.name && (value as? String)?.startsWith("content:") == true)

    suspend fun export(settings: AppSettings): String {
        val doc = buildJsonObject {
            put("format", FORMAT_VERSION)
            put("settings", buildJsonObject {
                for (s in AppSettings.ALL) {
                    val v = settings.observe(s).first()
                    if (!exportable(s, v)) continue
                    when (v) {
                        is Boolean -> put(s.key.name, v)
                        is Int -> put(s.key.name, v)
                        is String -> put(s.key.name, v)
                    }
                }
            })
        }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), doc)
    }

    /** Applies every valid value in [text] and returns how many were applied. Throws on malformed input. */
    @Suppress("UNCHECKED_CAST")
    suspend fun import(settings: AppSettings, text: String): Int {
        val root = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("Not a GrabLine settings file", e)
        }
        val body = root["settings"] as? JsonObject
            ?: throw IllegalArgumentException("Not a GrabLine settings file")
        var applied = 0
        for (s in AppSettings.ALL) {
            val el = body[s.key.name] as? JsonPrimitive ?: continue
            val name = s.key.name
            when (s.default) {
                is Boolean -> {
                    if (el.isString) continue
                    val b = runCatching { el.boolean }.getOrNull() ?: continue
                    settings.set(s as Setting<Boolean>, b)
                }
                is Int -> {
                    if (el.isString) continue
                    val i = el.intOrNull ?: continue
                    if (intRanges[name]?.contains(i) == false) continue
                    settings.set(s as Setting<Int>, i)
                }
                is String -> {
                    if (!el.isString) continue
                    val str = el.jsonPrimitive.content
                    if (allowed[name]?.contains(str) == false) continue
                    if (name == AppSettings.SAVE_FOLDER.key.name && str.startsWith("content:")) continue
                    settings.set(s as Setting<String>, str)
                }
                else -> continue
            }
            applied++
        }
        return applied
    }
}
