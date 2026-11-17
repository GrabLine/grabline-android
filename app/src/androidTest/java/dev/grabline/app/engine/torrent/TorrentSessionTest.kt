package dev.grabline.app.engine.torrent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Loads the native libtorrent4j library for this phone's ABI and starts a session. Talks to no tracker or peer. */
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

    @Test fun aTorrentFileIsAddedUnderItsInfoHash() = runBlocking { // QA-37: adding a .torrent used to time out
        val info = bencodedInfo()
        val torrent = ByteArrayOutputStream().apply { write("d4:info".toByteArray()); write(info); write("e".toByteArray()) }.toByteArray()
        val expected = MessageDigest.getInstance("SHA-1").digest(info).joinToString("") { "%02x".format(it) }
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(ctx.cacheDir, "torrent-add-${System.nanoTime()}")
        val engine = TorrentEngine()
        try {
            val hash = engine.add(TorrentSource.Metainfo(torrent), dir, null, false, null)
            assertEquals(expected, hash)
            // The handle must stay usable: one kept from the add alert crashed a few seconds later.
            repeat(20) {
                assertTrue("listed after the add", engine.snapshot(hash) != null)
                Thread.sleep(150)
            }
            engine.remove(hash, deleteFiles = true)
        } finally {
            engine.stopSession()
            dir.deleteRecursively()
        }
    }

    /** A one-file, one-piece v1 info dictionary with no trackers, so nothing goes on the network. */
    private fun bencodedInfo(): ByteArray {
        val pieces = MessageDigest.getInstance("SHA-1").digest(ByteArray(16 * 1024))
        return ByteArrayOutputStream().apply {
            write("d6:lengthi16384e4:name8:zero.bin12:piece lengthi16384e6:pieces20:".toByteArray())
            write(pieces)
            write("e".toByteArray())
        }.toByteArray()
    }
}
