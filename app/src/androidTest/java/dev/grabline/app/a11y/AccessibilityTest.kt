package dev.grabline.app.a11y

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import dev.grabline.app.data.AppSettings
import dev.grabline.app.data.PairedPc
import dev.grabline.app.remote.protocol.GlobalState
import dev.grabline.app.remote.protocol.QueueInfo
import dev.grabline.app.ui.about.AboutScreen
import dev.grabline.app.ui.about.LicencesScreen
import dev.grabline.app.ui.add.AddSheetContent
import dev.grabline.app.ui.add.AddUiState
import dev.grabline.app.ui.add.AnalysisUi
import dev.grabline.app.ui.add.PcOption
import dev.grabline.app.ui.details.DownloadDetailsRoute
import dev.grabline.app.ui.devices.DevicesScreen
import dev.grabline.app.ui.devices.DevicesUiState
import dev.grabline.app.ui.devices.FakeRemote
import dev.grabline.app.ui.devices.ManualForm
import dev.grabline.app.ui.devices.PairingController
import dev.grabline.app.ui.devices.PairingEntry
import dev.grabline.app.ui.devices.PairingProgress
import dev.grabline.app.ui.devices.PcRowUi
import dev.grabline.app.ui.devices.PcStatus
import dev.grabline.app.ui.devices.SpeedUnit
import dev.grabline.app.ui.devices.testPc
import dev.grabline.app.ui.downloads.Device
import dev.grabline.app.ui.downloads.DownloadRow
import dev.grabline.app.ui.downloads.DownloadRowItem
import dev.grabline.app.ui.downloads.DownloadsRoute
import dev.grabline.app.ui.downloads.FakePcRemote
import dev.grabline.app.ui.downloads.RowStatus
import dev.grabline.app.ui.downloads.fakeJob
import dev.grabline.app.ui.entry.IncomingRequest
import dev.grabline.app.ui.entry.LinkKind
import dev.grabline.app.ui.pc.PcScreen
import dev.grabline.app.ui.pc.PcUiState
import dev.grabline.app.ui.settings.SettingsScreen
import dev.grabline.app.ui.shell.GrabLineAppShell
import dev.grabline.app.ui.theme.ThemeMode
import dev.grabline.app.ui.theme.accentText
import dev.grabline.app.ui.welcome.WelcomeScreen
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase 1 accessibility pass: QA-41 (every control labelled), QA-42 (200% font, no clipped
 * text), QA-44 (light and dark contrast) and right-to-left layout. Run on the grabline_pixel
 * emulator; the fakes are the ones the other UI tests use.
 */
class AccessibilityTest {
    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings: AppSettings = run {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = File(ctx.cacheDir, "a11y-${System.nanoTime()}.preferences_pb")
        AppSettings(PreferenceDataStoreFactory.create(scope = scope) { file })
    }
    private val remote = FakePcRemote()
    private val noPairing = object : PairingController {
        override fun pair(entry: PairingEntry, deviceName: String): Flow<PairingProgress> = emptyFlow()
    }

    @After fun tearDown() = scope.cancel()

    // ---- Fixtures: each screen filled with fake data -------------------------------------

    private fun fillDownloads() {
        remote.jobsFlow.value = listOf(
            fakeJob("pc1", 1, "downloading", downloaded = 45_000_000, total = 100_000_000, speed = 460_000),
            fakeJob("pc1", 2, "paused", downloaded = 5_000_000, total = 50_000_000),
            fakeJob("pc1", 3, "failed", error = "Not enough space on the PC"),
            fakeJob("pc1", 4, "completed", downloaded = 7_000_000, total = 7_000_000),
        )
    }

    @Composable private fun Downloads() = DownloadsRoute(remote, onOpenDetails = { _, _ -> }, onAddClick = {})

    @Composable private fun DownloadsEmpty() = DownloadsRoute(FakePcRemote(), onOpenDetails = { _, _ -> }, onAddClick = {})

    @Composable private fun Details() = DownloadDetailsRoute(remote, "pc1", 3L, onBack = {})

    @Composable private fun Add() = AddSheetContent(
        state = AddUiState(
            ready = true,
            links = listOf("https://www.youtube.com/watch?v=abc"),
            kind = LinkKind.VIDEO,
            title = "A sample video",
            pcs = listOf(PcOption("pc1", "Desk PC", online = true)),
            selectedPcId = "pc1",
            pcOnline = true,
            showQuality = true,
            qualityOptions = listOf("best", "1080p", "720p"),
            quality = "best",
            sizes = mapOf("1080p" to 250_000_000L),
            analysis = AnalysisUi.Idle,
            queues = listOf(QueueInfo(1, "Night queue")),
            canStart = true,
        ),
        onSelectPc = {}, onSelectQuality = {}, onSelectQueue = {}, onSelectFolder = {},
        onToggleLinks = {}, onRetry = {}, onStart = {}, onPairClick = {},
    )

    @Composable private fun Devices() = DevicesScreen(
        state = DevicesUiState(rows = listOf(PcRowUi("pc1", "Office PC", PcStatus.Online, "2.4.0", 2))),
        onPair = {}, onOpenPc = {}, onRefresh = {}, onForm = {}, onSubmitManual = {},
        onCloseManual = {}, onRetryPairing = {}, onDismissPairing = {},
    )

    @Composable private fun DevicesManual() = DevicesScreen(
        state = DevicesUiState(rows = emptyList(), manualOpen = true, form = ManualForm()),
        onPair = {}, onOpenPc = {}, onRefresh = {}, onForm = {}, onSubmitManual = {},
        onCloseManual = {}, onRetryPairing = {}, onDismissPairing = {},
    )

    @Composable private fun Pc(online: Boolean = true) = PcScreen(
        state = PcUiState(
            loaded = true,
            pc = testPc(name = "Office PC"),
            status = if (online) PcStatus.Online else PcStatus.Offline,
            global = if (online) GlobalState(paused = false, speedLimit = 1_048_576, speed = 2_000_000, todayBytes = 5_000_000_000) else null,
            queues = listOf(QueueInfo(1, "Night queue", paused = true), QueueInfo(2, "Main")),
            capUnit = SpeedUnit.MB,
        ),
        onBack = {}, onRetry = {}, onPauseResume = {}, onOpenCap = {}, onCapText = {}, onCapUnit = {},
        onCapOff = {}, onSaveCap = {}, onOpenQueue = {}, onChooseQueue = {}, onAskUnpair = {},
        onConfirmUnpair = {}, onDismissDialog = {},
    )

    private val pairedPc = PairedPc("pc1", "Office PC", "fp", "dev", listOf("192.168.1.5"), 49152, protocolVersion = 6, pairedAt = 1L)

    @Composable private fun Settings() = SettingsScreen(settings, listOf(pairedPc), onLanguageChange = {})

    @Composable private fun Welcome() = WelcomeScreen(settings, onPairPc = {})

    @Composable private fun About() = AboutScreen(onBack = {}, onLicences = {})

    @Composable private fun Licences() = LicencesScreen(onBack = {})

    @Composable private fun Shell() = GrabLineAppShell(
        remote = remote,
        pairing = noPairing,
        settings = settings,
        incoming = MutableStateFlow<IncomingRequest?>(null),
        onIncomingHandled = {},
        clipboardOffer = MutableStateFlow<String?>(null),
        onClipboardHandled = {},
    )

    private fun accessible(name: String, content: @Composable () -> Unit) {
        fillDownloads()
        compose.showScreen { content() }
        compose.assertAccessible(name)
    }

    // ---- QA-41: every control labelled, 48 dp targets, headings -------------------------

    @Test fun qa41_welcome() = accessible("welcome") { Welcome() }
    @Test fun qa41_downloads() = accessible("downloads") { Downloads() }
    @Test fun qa41_downloadsEmpty() = accessible("downloads empty") { DownloadsEmpty() }
    @Test fun qa41_details() = accessible("details") { Details() }
    @Test fun qa41_add() = accessible("add") { Add() }
    @Test fun qa41_devices() = accessible("devices") { Devices() }
    @Test fun qa41_pc() = accessible("pc") { Pc() }
    @Test fun qa41_pcOffline() = accessible("pc offline") { Pc(online = false) }
    @Test fun qa41_settings() = accessible("settings") { Settings() }
    @Test fun qa41_about() = accessible("about") { About() }
    @Test fun qa41_licences() = accessible("licences") { Licences() }

    @Test fun qa41_shell() {
        fillDownloads()
        runBlocking { settings.set(AppSettings.WELCOME_DONE, true) }
        compose.showScreen { Shell() }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTextCount("Downloads") > 0
        }
        compose.assertAccessible("shell")
    }

    // ---- QA-42: font at 200%, nothing clipped ---------------------------------------------

    private fun largeFont(name: String, content: @Composable () -> Unit) {
        fillDownloads()
        compose.showScreen(fontScale = 2f) { content() }
        compose.assertNoClippedText("$name at 200%")
    }

    @Test fun qa42_downloadsRow() = largeFont("downloads") { Downloads() }
    @Test fun qa42_downloadsEmpty() = largeFont("downloads empty") { DownloadsEmpty() }
    @Test fun qa42_details() = largeFont("details") { Details() }
    @Test fun qa42_addSheet() = largeFont("add sheet") { Add() }
    @Test fun qa42_devices() = largeFont("devices") { Devices() }
    @Test fun qa42_devicesManual() = largeFont("devices manual entry") { DevicesManual() }
    @Test fun qa42_pc() = largeFont("pc") { Pc() }
    @Test fun qa42_pcOffline() = largeFont("pc offline") { Pc(online = false) }
    @Test fun qa42_settings() = largeFont("settings") { Settings() }
    @Test fun qa42_welcome() = largeFont("welcome") { Welcome() }
    @Test fun qa42_about() = largeFont("about") { About() }

    // ---- QA-44: light and dark, text against its background --------------------------------

    private fun assertReadable(label: String, scheme: ColorScheme, dark: Boolean) {
        val pairs = listOf(
            "onBackground/background" to (scheme.onBackground to scheme.background),
            "onSurface/surface" to (scheme.onSurface to scheme.surface),
            "onSurface/background" to (scheme.onSurface to scheme.background),
            "onSurfaceVariant/surface" to (scheme.onSurfaceVariant to scheme.surface),
            "onSurfaceVariant/background" to (scheme.onSurfaceVariant to scheme.background),
            "onPrimary/primary" to (scheme.onPrimary to scheme.primary),
            "accent text/background" to (scheme.accentText to scheme.background),
            "error/background" to (scheme.error to scheme.background),
            "error/surface" to (scheme.error to scheme.surface),
        )
        pairs.forEach { (name, p) ->
            val ratio = contrastRatio(p.first, p.second)
            assertTrue("$label (${if (dark) "dark" else "light"}) $name = $ratio, needs 4.5", ratio >= 4.5)
        }
    }

    private fun readable(mode: ThemeMode, content: @Composable () -> Unit) {
        fillDownloads()
        var scheme: ColorScheme? = null
        compose.showScreen(mode = mode, onScheme = { scheme = it }) { content() }
        compose.waitForIdle()
        val s = checkNotNull(scheme)
        assertReadable(mode.name, s, mode == ThemeMode.Dark)
        // The switch really changed the palette (dark background is darker than the text).
        val darkBg = s.background.red + s.background.green + s.background.blue < 1.5f
        assertEquals(mode == ThemeMode.Dark, darkBg)
    }

    @Test fun qa44_downloadsLight() = readable(ThemeMode.Light) { Downloads() }
    @Test fun qa44_downloadsDark() = readable(ThemeMode.Dark) { Downloads() }
    @Test fun qa44_settingsLight() = readable(ThemeMode.Light) { Settings() }
    @Test fun qa44_settingsDark() = readable(ThemeMode.Dark) { Settings() }
    @Test fun qa44_pcLight() = readable(ThemeMode.Light) { Pc() }
    @Test fun qa44_pcDark() = readable(ThemeMode.Dark) { Pc() }
    @Test fun qa44_addLight() = readable(ThemeMode.Light) { Add() }
    @Test fun qa44_addDark() = readable(ThemeMode.Dark) { Add() }

    // ---- Right-to-left: the layout mirrors --------------------------------------------------

    private val row = DownloadRow(
        key = "pc:pc1:1",
        device = Device.Pc("pc1", "Desk PC"),
        jobId = 1,
        name = "file1.zip",
        url = "https://example.com/file1.zip",
        status = RowStatus.Downloading,
        downloaded = 45_000_000,
        total = 100_000_000,
        speed = 460_000,
    )

    private fun badgeAndStatusLeft(direction: LayoutDirection): Pair<Float, Float> {
        compose.showScreen(direction = direction) {
            Box(Modifier.size(360.dp, 200.dp)) {
                DownloadRowItem(
                    row = row, selected = false, selecting = false, highlighted = false,
                    onClick = {}, onLongClick = {}, onAction = {},
                )
            }
        }
        compose.waitForIdle()
        val badge = compose.onNodeWithText("Desk PC", useUnmergedTree = true).fetchSemanticsNode()
        val status = compose.onNodeWithText("Downloading", useUnmergedTree = true).fetchSemanticsNode()
        return badge.boundsInRoot.left to status.boundsInRoot.left
    }

    @Test fun rtl_downloadsRowBadgeSitsOnTheRight() {
        val (badgeLeft, statusLeft) = badgeAndStatusLeft(LayoutDirection.Rtl)
        // First in reading order, so with right-to-left it is the right-most item of its line.
        assertTrue("badge ($badgeLeft) should be right of the status ($statusLeft)", badgeLeft > statusLeft)
        val rootWidth = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        assertTrue("badge should hug the right edge", badgeLeft > rootWidth / 2)
    }

    @Test fun ltr_downloadsRowBadgeSitsOnTheLeft() {
        val (badgeLeft, statusLeft) = badgeAndStatusLeft(LayoutDirection.Ltr)
        assertTrue("badge ($badgeLeft) should be left of the status ($statusLeft)", badgeLeft < statusLeft)
        assertNotEquals(0f, statusLeft)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodesWithText(text).fetchSemanticsNodes().size
}
