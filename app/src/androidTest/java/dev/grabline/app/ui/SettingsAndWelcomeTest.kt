package dev.grabline.app.ui

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import dev.grabline.app.data.AppSettings
import dev.grabline.app.ui.settings.SettingsScreen
import dev.grabline.app.ui.theme.GrabLineTheme
import dev.grabline.app.ui.welcome.WelcomeScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class SettingsAndWelcomeTest {
    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings: AppSettings = run {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = File(ctx.cacheDir, "test-${System.nanoTime()}.preferences_pb")
        AppSettings(PreferenceDataStoreFactory.create(scope = scope) { file })
    }

    @After fun tearDown() = scope.cancel()

    @Test fun settingsShowSpecDefaults() {
        compose.setContent {
            GrabLineTheme { SettingsScreen(settings, emptyList(), onLanguageChange = {}) }
        }
        compose.onNodeWithText("Ask every time").assertExists()
        compose.onAllNodesWithText("System").assertCountEquals(2) // theme and language
        compose.onNodeWithText("Forever").assertExists() // keep history
        compose.onNodeWithText("Downloads/GrabLine").assertExists()
        compose.onAllNodesWithText("Coming in a later update").assertCountEquals(3)
        compose.onNodeWithText("No crash logs").assertExists()
        compose.onNodeWithText("8").assertExists() // connections per file
    }

    @Test fun themeSwitchUpdatesTheSetting() {
        compose.setContent {
            GrabLineTheme { SettingsScreen(settings, emptyList(), onLanguageChange = {}) }
        }
        compose.onNodeWithText("Theme").performScrollTo().performClick()
        compose.onNodeWithText("Dark").performClick()
        compose.waitUntil(5_000) { runBlocking { settings.observe(AppSettings.THEME).first() } == "dark" }
        compose.onNodeWithText("Dark").assertExists()
        assertEquals("dark", runBlocking { settings.observe(AppSettings.THEME).first() })
    }

    @Test fun welcomeSkipSetsTheFlag() {
        var finished = false
        compose.setContent {
            GrabLineTheme { WelcomeScreen(settings, onPairPc = {}, onFinished = { finished = true }) }
        }
        compose.onNodeWithText("Skip").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { settings.observe(AppSettings.WELCOME_DONE).first() } }
        assertTrue(finished)
    }
}
