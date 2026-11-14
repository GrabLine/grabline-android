package dev.grabline.app.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.grabline.app.data.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTransferTest {
    private class FakeStore : DataStore<Preferences> {
        val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }

    private fun newSettings() = AppSettings(FakeStore())

    @Test fun roundTripKeepsEveryValue() = runBlocking {
        val a = newSettings()
        a.set(AppSettings.THEME, "dark")
        a.set(AppSettings.COLOURS, "wallpaper")
        a.set(AppSettings.CONNECTIONS_PER_FILE, 12)
        a.set(AppSettings.ONLY_WIFI, false)
        a.set(AppSettings.AUTO_RETRY, -1)
        a.set(AppSettings.KEEP_HISTORY, "7d")
        a.set(AppSettings.DEFAULT_DEVICE, "pc-1")
        a.set(AppSettings.RULES, RulesCodec.with("", "video", "pc-1"))
        val json = SettingsTransfer.export(a)

        val b = newSettings()
        val applied = SettingsTransfer.import(b, json)
        assertEquals(AppSettings.ALL.size, applied)
        for (s in AppSettings.ALL) {
            assertEquals(s.key.name, a.observe(s).first(), b.observe(s).first())
        }
        assertEquals("dark", b.observe(AppSettings.THEME).first())
    }

    @Test fun defaultsRoundTripToDefaults() = runBlocking {
        val json = SettingsTransfer.export(newSettings())
        val b = newSettings()
        SettingsTransfer.import(b, json)
        for (s in AppSettings.ALL) assertEquals(s.default, b.observe(s).first())
    }

    @Test fun invalidValuesAreSkipped() = runBlocking {
        val b = newSettings()
        val json = """{"format":1,"settings":{"theme":"neon","connections_per_file":99,"only_wifi":"yes","downloads_at_once":2,"unknown":1}}"""
        val applied = SettingsTransfer.import(b, json)
        assertEquals(1, applied)
        assertEquals("system", b.observe(AppSettings.THEME).first())
        assertEquals(8, b.observe(AppSettings.CONNECTIONS_PER_FILE).first())
        assertEquals(2, b.observe(AppSettings.DOWNLOADS_AT_ONCE).first())
    }

    @Test fun malformedFileIsRejected() {
        val b = newSettings()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { SettingsTransfer.import(b, "not json") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { SettingsTransfer.import(b, "{}") } }
    }

    @Test fun pickedFolderGrantIsNotExported() = runBlocking {
        val a = newSettings()
        a.set(AppSettings.SAVE_FOLDER, "content://com.android.externalstorage.documents/tree/primary%3AMovies")
        assertFalse(SettingsTransfer.export(a).contains("content:"))
        assertTrue(SettingsTransfer.export(newSettings()).contains("Downloads/GrabLine"))
    }

    @Test fun welcomeFlagIsNotExported() = runBlocking {
        val a = newSettings()
        a.set(AppSettings.WELCOME_DONE, true)
        assertFalse(SettingsTransfer.export(a).contains("welcome"))
    }

    @Test fun rulesCodecRoundTrip() {
        var r = RulesCodec.with("", "video", "phone")
        r = RulesCodec.with(r, "music", "pc-2")
        assertEquals(mapOf("video" to "phone", "music" to "pc-2"), RulesCodec.decode(r))
        assertEquals("", RulesCodec.with(RulesCodec.with("", "video", "phone"), "video", null))
        assertTrue(RulesCodec.decode("garbage").isEmpty())
    }
}
