package dev.grabline.app.engine.torrent

import dev.grabline.app.data.PhoneJobRow
import dev.grabline.app.engine.EngineConfig
import dev.grabline.app.engine.EngineConstraints
import dev.grabline.app.engine.PhoneJobStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Where a link goes: magnet and .torrent links to the torrent engine, other web links to the segment/video engines. */
enum class LinkRoute { MAGNET, TORRENT_URL, TORRENT_FILE, WEB, INVALID }

object TorrentRouting {
    /** The stored link of a job made from a shared .torrent file (its bytes are kept with the job). */
    const val FILE_PREFIX = "torrent-file:"

    fun route(link: String): LinkRoute {
        val t = link.trim()
        val lower = t.lowercase()
        return when {
            lower.startsWith("magnet:?") -> if ("xt=urn:bt" in lower) LinkRoute.MAGNET else LinkRoute.INVALID
            lower.startsWith(FILE_PREFIX) || lower.startsWith("content:") || lower.startsWith("file:") -> LinkRoute.TORRENT_FILE
            lower.startsWith("http://") || lower.startsWith("https://") ->
                if (t.substringBefore('#').substringBefore('?').trimEnd('/').endsWith(".torrent", ignoreCase = true)) LinkRoute.TORRENT_URL else LinkRoute.WEB
            else -> LinkRoute.INVALID
        }
    }

    fun isTorrent(link: String) = route(link).let { it == LinkRoute.MAGNET || it == LinkRoute.TORRENT_URL || it == LinkRoute.TORRENT_FILE }
}

/** "Seed after download" (SPEC Settings): off, until ratio 1.0, or for 1 hour. */
object SeedRules {
    const val OFF = "off"
    const val RATIO = "ratio1"
    const val HOUR = "hour1"
    const val HOUR_MS = 60L * 60 * 1000

    /** True when seeding must stop now. [size] is the torrent's wanted bytes; [startedAt] is when seeding began. */
    fun shouldStop(mode: String, uploaded: Long, size: Long, startedAt: Long, now: Long): Boolean = when (mode) {
        RATIO -> size <= 0 || uploaded >= size
        HOUR -> now - startedAt >= HOUR_MS
        else -> true
    }

    fun ratio(uploaded: Long, size: Long): Float = if (size <= 0) 0f else (uploaded.toDouble() / size).toFloat()
}

/** Whether a job must wait for a better connection. Torrents use their own mobile-data setting, not Wi-Fi only (QA-37). */
object NetworkHold {
    fun block(kind: String, cfg: EngineConfig, c: EngineConstraints, mobileOverride: Boolean): PhoneJobStatus? = when {
        !c.connected -> PhoneJobStatus.WAITING_NETWORK
        kind == PhoneJobRow.KIND_TORRENT && !cfg.torrentsOnMobile && !mobileOverride && (!c.wifi || c.metered) -> PhoneJobStatus.WAITING_WIFI
        kind != PhoneJobRow.KIND_TORRENT && cfg.onlyWifi && !mobileOverride && (!c.wifi || c.metered) -> PhoneJobStatus.WAITING_WIFI
        cfg.onlyCharging && !c.charging -> PhoneJobStatus.WAITING_CHARGING
        else -> null
    }

    /** Seeding only uses the network when torrents may use mobile data (or the phone is on Wi-Fi). */
    fun seedBlocked(cfg: EngineConfig, c: EngineConstraints): Boolean =
        !c.connected || (!cfg.torrentsOnMobile && (!c.wifi || c.metered)) || (cfg.onlyCharging && !c.charging)
}

@Serializable
data class StoredFile(val n: String, val s: Long)

object StoredFiles {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(files: List<TorrentFileInfo>): String =
        json.encodeToString(ListSerializer(StoredFile.serializer()), files.map { StoredFile(it.path, it.size) })
    fun decode(text: String?): List<TorrentFileInfo> =
        if (text.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString(ListSerializer(StoredFile.serializer()), text).map { TorrentFileInfo(it.n, it.s) } }.getOrDefault(emptyList())

    /** The selection as booleans for [count] files; a missing or short mask means "download it". */
    fun wanted(mask: String?, count: Int): List<Boolean> = List(count) { mask?.getOrNull(it) != '0' }
    fun mask(wanted: List<Boolean>): String? = if (wanted.all { it }) null else wanted.joinToString("") { if (it) "1" else "0" }
}
