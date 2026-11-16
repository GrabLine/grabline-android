package dev.grabline.app.engine.video

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Starts the bundled Python, yt-dlp and FFmpeg on the device and reads the yt-dlp version. No network. */
@RunWith(AndroidJUnit4::class)
class YtDlpInitTest {
    @Test fun engineStartsAndReportsItsVersion() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = YoutubeDlVideoEngine(context)
        engine.ensureReady()
        val version = engine.version()
        assertNotNull("yt-dlp version", version)
        assertFalse("yt-dlp version is not blank", version!!.isBlank())
    }
}
