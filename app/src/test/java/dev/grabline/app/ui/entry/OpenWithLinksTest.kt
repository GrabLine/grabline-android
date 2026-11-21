package dev.grabline.app.ui.entry

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Open with GrabLine" for links to files: what the manifest offers, and what the app does with the link. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenWithLinksTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun view(url: String, type: String? = null) = Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_BROWSABLE).also {
        if (type == null) it.data = Uri.parse(url) else it.setDataAndType(Uri.parse(url), type)
    }

    private fun offered(intent: Intent) =
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).any { it.activityInfo.packageName == context.packageName }

    @Test fun linksToFilesAreOffered() {
        for (u in listOf(
            "https://example.com/files/archive.zip", "http://example.com/a/b/movie.mp4", "https://cdn.example.org/x/song.mp3",
            "https://example.com/setup.exe", "https://example.com/report.pdf", "https://example.com/app.apk", "https://example.com/a.torrent",
        )) assertTrue(u, offered(view(u)))
    }

    /** Android matches ".*" up to the first occurrence of the next character, so dots earlier in the path need their own patterns. */
    @Test fun linksWithDotsEarlierInThePathAreOffered() {
        for (u in listOf(
            "https://example.com/files/file.tar.gz", "https://cdn.example.com/v1.2/release.zip", "https://example.com/a.b.c/d.e.pdf",
            "https://example.com/my.holiday.photos.2024.mp4", "https://example.com/releases/v2.0.1/app-1.apk",
        )) assertTrue(u, offered(view(u)))
    }

    @Test fun fileTypesAreOfferedWhateverTheLinkLooksLike() {
        assertTrue(offered(view("https://example.com/download?id=7", "video/mp4")))
        assertTrue(offered(view("https://example.com/get/5", "application/zip")))
        assertTrue(offered(view("https://example.com/get/6", "audio/mpeg")))
    }

    @Test fun webPagesAreNotOffered_soGrabLineIsNotABrowser() {
        for (u in listOf("https://example.com/", "https://example.com/index.html", "https://example.com/watch?v=1", "https://example.com/article")) {
            assertFalse(u, offered(view(u)))
        }
        assertFalse(offered(view("https://example.com/page", "text/html")))
    }

    @Test fun anHttpLinkBecomesALinkToAdd() {
        val request = IntentLinkReader.read(context, view("https://example.com/files/archive.zip"))
        assertNotNull(request)
        assertEquals(listOf("https://example.com/files/archive.zip"), request!!.links)
        assertEquals(listOf("http://example.com/a.mp4"), IntentLinkReader.read(context, view("http://example.com/a.mp4"))!!.links)
    }

    @Test fun otherSchemesStillGiveNothing() {
        assertNull(IntentLinkReader.read(context, Intent(Intent.ACTION_VIEW, Uri.parse("ftp://example.com/a.zip"))))
        assertNull(IntentLinkReader.read(context, Intent(Intent.ACTION_VIEW)))
    }
}
