package dev.grabline.app.ui.devices

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.grabline.app.remote.connection.PcLinkState
import dev.grabline.app.ui.pc.PcRoute
import dev.grabline.app.ui.theme.GrabLineTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DevicesPcUiTest {
    @get:Rule val compose = createComposeRule()
    private val remote = FakeRemote()
    private val noPairing = object : PairingController {
        override fun pair(entry: PairingEntry, deviceName: String): Flow<PairingProgress> = emptyFlow()
    }

    @Test fun emptyStateExplainsPairingAndOffersTheButton() {
        compose.setContent { GrabLineTheme { DevicesRoute(remote, noPairing, onOpenPc = {}) } }
        compose.onNodeWithText("No PCs yet").assertExists()
        compose.onNodeWithText("Pair a PC").assertExists()
    }

    @Test fun confirmDialogAppearsBeforeUnpair() {
        remote.pcList.value = listOf(testPc())
        remote.links.value = mapOf("pc1" to PcLinkState.Offline(null))
        compose.setContent { GrabLineTheme { PcRoute(remote, "pc1", onBack = {}) } }
        compose.onNodeWithText("Your PC is off or on another network").assertExists()
        compose.onNodeWithText("Remove pairing").performScrollTo().performClick()
        compose.onNodeWithText("Remove this pairing?").assertExists()
        assertTrue(remote.unpaired.isEmpty())
        compose.onNodeWithText("Remove").performClick()
        compose.waitUntil(5_000) { remote.unpaired.isNotEmpty() }
        assertEquals(listOf("pc1"), remote.unpaired)
    }
}
