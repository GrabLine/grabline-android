package dev.grabline.app.ui.common

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import dev.grabline.app.engine.Checksum
import dev.grabline.app.engine.EngineText
import dev.grabline.app.engine.StartLater
import dev.grabline.app.engine.TransferText
import dev.grabline.app.engine.cloud.CloudText
import dev.grabline.app.engine.torrent.TorrentText
import dev.grabline.app.engine.video.VideoFailure
import dev.grabline.app.engine.video.VideoText
import dev.grabline.app.remote.protocol.ErrorCode
import dev.grabline.app.remote.protocol.ErrorMessages
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EngineMessagesTest {
    private val base: Context get() = ApplicationProvider.getApplicationContext()

    private fun inLocale(tag: String): Context =
        base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })

    /** The fixed messages of an object: its `const val` strings. */
    private fun constants(type: Class<*>): List<Pair<String, String>> =
        type.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java && !it.isSynthetic }
            .map { it.isAccessible = true; it.name to it.get(null) as String }

    private val objects = listOf(
        EngineText::class.java, VideoText::class.java, TorrentText::class.java, CloudText::class.java,
        TransferText::class.java, ErrorMessages::class.java,
    )

    /** Every message the engines or the PC protocol can store, including the parameterised ones. */
    private fun allMessages(): List<String> = buildList {
        for (o in objects) for ((_, text) in constants(o)) add(text)
        add(EngineText.server(503))
        add(EngineText.needsSpace(3_500_000_000L, 120_000_000L))
        add(CloudText.hostKeyChanged("files.example.com", "SHA256:abc", "SHA256:xyz"))
        add(CloudText.tooMany(200))
        VideoFailure.entries.forEach { add(VideoText.forFailure(it)) }
        ErrorCode.entries.forEach { add(ErrorMessages.forCode(it)) }
        ErrorMessages.failedByReason.values.forEach { add(it) }
        add(ErrorMessages.forReply(ErrorCode.FAILED, "drm", "Netflix"))
        add(Checksum.noticeFor(Checksum.VERIFIED)!!)
        add(Checksum.noticeFor(Checksum.MISMATCH)!!)
        add(Checksum.INVALID)
        val zone = TimeZone.getTimeZone("UTC")
        add(StartLater.notice(startAt = 1_000_000_000_000L + 3_600_000, now = 1_000_000_000_000L, zone = zone))
        add(StartLater.notice(startAt = 1_000_000_000_000L + 86_400_000, now = 1_000_000_000_000L, zone = zone))
    }

    @Test
    fun everyConstantHasATranslation() {
        for (o in objects) {
            val found = constants(o)
            assertTrue("no constants found in ${o.simpleName}", found.isNotEmpty())
            for ((name, text) in found) {
                // Non-user strings such as ErrorCode wire names are not in these objects; every String const is a message.
                assertNotNull("${o.simpleName}.$name has no entry in EngineMessages", EngineMessages.resolve(text))
            }
        }
        for (text in allMessages()) assertNotNull("No translation for: $text", EngineMessages.resolve(text))
    }

    @Test
    fun englishStaysTheSameInEnglish() {
        val en = inLocale("en")
        for (text in allMessages()) assertEquals(text, localizeEngineMessage(en, text))
    }

    @Test
    fun everyMessageIsTranslatedInEveryLanguage() {
        val en = inLocale("en")
        val locales = listOf("ar", "de", "es", "fr", "hi", "it", "ja", "ko", "pt", "ru", "tr", "ur", "zh")
        for (tag in locales) {
            val ctx = inLocale(tag)
            for (res in EngineMessages.allResources) {
                val english = en.getString(res, "A", "B", "C")
                val local = ctx.getString(res, "A", "B", "C")
                assertTrue("$tag has an empty string for ${en.resources.getResourceEntryName(res)}", local.isNotBlank())
                assertNotEquals("$tag is still English for ${en.resources.getResourceEntryName(res)}", english, local)
            }
        }
    }

    @Test
    fun parameterisedMessagesKeepTheirValues() {
        val ar = inLocale("ar")
        val server = localizeEngineMessage(ar, EngineText.server(503))
        assertNotEquals(EngineText.server(503), server)
        assertTrue(server, "503" in server)

        val space = localizeEngineMessage(ar, EngineText.needsSpace(3_500_000_000L, 120_000_000L))
        assertTrue(space, "3.3 GB" in space && "114 MB" in space)

        val many = localizeEngineMessage(inLocale("de"), CloudText.tooMany(200))
        assertTrue(many, "200" in many)

        val key = localizeEngineMessage(inLocale("fr"), CloudText.hostKeyChanged("files.example.com", "SHA256:abc", "SHA256:xyz"))
        assertTrue(key, "files.example.com" in key && "SHA256:abc" in key && "SHA256:xyz" in key)
        assertNotEquals(CloudText.hostKeyChanged("files.example.com", "SHA256:abc", "SHA256:xyz"), key)

        val drm = localizeEngineMessage(inLocale("es"), ErrorMessages.forReply(ErrorCode.FAILED, "drm", "Netflix"))
        assertTrue(drm, "Netflix" in drm)
    }

    @Test
    fun startNoticesKeepTheirTime() {
        val zone = TimeZone.getTimeZone("UTC")
        val today = StartLater.notice(1_000_000_000_000L + 3_600_000, 1_000_000_000_000L, zone)
        val tomorrow = StartLater.notice(1_000_000_000_000L + 86_400_000, 1_000_000_000_000L, zone)
        val time = today.removePrefix("Starts at ")
        val ja = inLocale("ja")
        assertTrue(localizeEngineMessage(ja, today).contains(time))
        assertNotEquals(today, localizeEngineMessage(ja, today))
        assertNotEquals(tomorrow, localizeEngineMessage(ja, tomorrow))
        assertNotEquals(localizeEngineMessage(ja, today), localizeEngineMessage(ja, tomorrow))
    }

    @Test
    fun unknownTextComesBackUnchanged() {
        val ar = inLocale("ar")
        for (text in listOf("", "Disk quota exceeded", "كتاب.pdf", "The server answered with an error (x). Try again later", "Starts soon")) {
            assertEquals(text, localizeEngineMessage(ar, text))
        }
    }

    @Test
    fun arabicIsNotEnglish() {
        val ar = inLocale("ar")
        val text = localizeEngineMessage(ar, EngineText.UNEXPECTED)
        assertNotEquals(EngineText.UNEXPECTED, text)
        assertTrue(text, text.any { it in '؀'..'ۿ' })
        val auth = localizeEngineMessage(ar, CloudText.AUTH)
        assertTrue(auth, auth.contains(ar.getString(dev.grabline.app.R.string.servers_title)))
    }
}
