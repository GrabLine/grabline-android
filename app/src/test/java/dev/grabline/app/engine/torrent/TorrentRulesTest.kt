package dev.grabline.app.engine.torrent

import dev.grabline.app.data.PhoneJobRow
import dev.grabline.app.engine.EngineConfig
import dev.grabline.app.engine.EngineConstraints
import dev.grabline.app.engine.PhoneJobStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentRulesTest {
    private val mobile = EngineConstraints(connected = true, wifi = false, metered = true)
    private val wifi = EngineConstraints(connected = true, wifi = true, metered = false)
    private val file = PhoneJobRow.KIND_FILE
    private val torrent = PhoneJobRow.KIND_TORRENT

    @Test fun routingSeparatesMagnetTorrentFileAndWebLinks() {
        assertEquals(LinkRoute.MAGNET, TorrentRouting.route("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Movie"))
        assertEquals(LinkRoute.TORRENT_URL, TorrentRouting.route("https://example.com/files/Movie.TORRENT"))
        assertEquals(LinkRoute.TORRENT_URL, TorrentRouting.route("http://example.com/a.torrent?token=1"))
        assertEquals(LinkRoute.TORRENT_FILE, TorrentRouting.route("torrent-file:Movie.torrent"))
        assertEquals(LinkRoute.TORRENT_FILE, TorrentRouting.route("content://downloads/1/Movie.torrent"))
        assertEquals(LinkRoute.WEB, TorrentRouting.route("https://example.com/movie.mp4"))
        assertEquals(LinkRoute.WEB, TorrentRouting.route("https://example.com/torrents/list"))
        assertEquals(LinkRoute.INVALID, TorrentRouting.route("magnet:?dn=no-hash"))
        assertEquals(LinkRoute.INVALID, TorrentRouting.route("not a link"))
        assertTrue(TorrentRouting.isTorrent("magnet:?xt=urn:btih:abc"))
        assertFalse(TorrentRouting.isTorrent("https://example.com/a.zip"))
    }

    @Test fun torrentsOffMobileDataWaitForWifiWhateverWifiOnlySays() { // QA-37
        val torrentsOff = EngineConfig(onlyWifi = false, torrentsOnMobile = false)
        assertEquals(PhoneJobStatus.WAITING_WIFI, NetworkHold.block(torrent, torrentsOff, mobile, false))
        assertNull("a normal file is not held when Wi-Fi only is off", NetworkHold.block(file, torrentsOff, mobile, false))
        assertNull(NetworkHold.block(torrent, torrentsOff, wifi, false))
        assertNull("Download now anyway", NetworkHold.block(torrent, torrentsOff, mobile, true))
    }

    @Test fun torrentsOnMobileDataIgnoreWifiOnlyButFilesStillObeyIt() {
        val cfg = EngineConfig(onlyWifi = true, torrentsOnMobile = true)
        assertNull(NetworkHold.block(torrent, cfg, mobile, false))
        assertEquals(PhoneJobStatus.WAITING_WIFI, NetworkHold.block(file, cfg, mobile, false))
    }

    @Test fun noNetworkAndChargingStillHoldTorrents() {
        val cfg = EngineConfig(torrentsOnMobile = true, onlyCharging = true)
        assertEquals(PhoneJobStatus.WAITING_NETWORK, NetworkHold.block(torrent, cfg, EngineConstraints(connected = false), false))
        assertEquals(PhoneJobStatus.WAITING_CHARGING, NetworkHold.block(torrent, cfg, wifi, false))
        assertNull(NetworkHold.block(torrent, cfg, wifi.copy(charging = true), false))
    }

    @Test fun seedingUploadsOnlyWhereTorrentsMayGo() {
        assertTrue(NetworkHold.seedBlocked(EngineConfig(torrentsOnMobile = false), mobile))
        assertFalse(NetworkHold.seedBlocked(EngineConfig(torrentsOnMobile = false), wifi))
        assertFalse(NetworkHold.seedBlocked(EngineConfig(torrentsOnMobile = true), mobile))
        assertTrue(NetworkHold.seedBlocked(EngineConfig(torrentsOnMobile = true), EngineConstraints(connected = false)))
    }

    @Test fun seedingStopsAtRatioOneHourOrAtOnceWhenOff() {
        val size = 1_000L
        assertTrue(SeedRules.shouldStop(SeedRules.OFF, 0, size, 0, 0))
        assertFalse(SeedRules.shouldStop(SeedRules.RATIO, 999, size, 0, Long.MAX_VALUE / 2))
        assertTrue(SeedRules.shouldStop(SeedRules.RATIO, 1_000, size, 0, 0))
        assertTrue(SeedRules.shouldStop(SeedRules.RATIO, 5_000, size, 0, 0))
        assertFalse(SeedRules.shouldStop(SeedRules.HOUR, 10 * size, size, 1_000, 1_000 + SeedRules.HOUR_MS - 1))
        assertTrue(SeedRules.shouldStop(SeedRules.HOUR, 0, size, 1_000, 1_000 + SeedRules.HOUR_MS))
        assertEquals(0.5f, SeedRules.ratio(500, 1_000), 0.0001f)
        assertEquals(0f, SeedRules.ratio(500, 0), 0f)
    }

    @Test fun fileSelectionRoundTripsAndDefaultsToAll() {
        assertEquals(listOf(true, true, true), StoredFiles.wanted(null, 3))
        assertEquals(listOf(true, false, true), StoredFiles.wanted("101", 3))
        assertEquals("a short mask means download the rest", listOf(true, false, true), StoredFiles.wanted("10", 3))
        assertNull(StoredFiles.mask(listOf(true, true)))
        assertEquals("010", StoredFiles.mask(listOf(false, true, false)))
        val files = listOf(TorrentFileInfo("Show/a.mkv", 10), TorrentFileInfo("Show/b \"x\".srt", 2))
        assertEquals(files, StoredFiles.decode(StoredFiles.encode(files)))
        assertEquals(emptyList<TorrentFileInfo>(), StoredFiles.decode("not json"))
    }
}
