package dev.grabline.app.engine.torrent

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Loads the native libtorrent4j library for this phone's ABI and starts a session. Touches no torrent and no tracker. */
@RunWith(AndroidJUnit4::class)
class TorrentSessionTest {
    @Test fun nativeLibraryLoadsAndTheSessionStartsAndStops() {
        val engine = TorrentEngine()
        val version = engine.libraryVersion()
        assertTrue("libtorrent version was '$version'", version.isNotBlank() && version.first().isDigit())
        assertFalse(engine.isRunning())
        engine.ensureStarted()
        try {
            assertTrue(engine.isRunning())
        } finally {
            engine.stopSession()
        }
        assertFalse(engine.isRunning())
    }
}
