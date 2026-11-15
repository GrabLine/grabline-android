package dev.grabline.app.ui.devices

import kotlinx.coroutines.flow.Flow

/** What the user gave the pairing flow. [text] holds the one-time code, so it stays out of [toString]. */
sealed interface PairingEntry {
    val text: String

    /** The text of a scanned QR code (`grabline-pair:...`). */
    class Scanned(override val text: String) : PairingEntry {
        override fun toString() = "Scanned(<redacted>)"
    }

    /** Manual entry: address (with port) on the first line, the normalised code on the second. */
    class Manual(override val text: String) : PairingEntry {
        override fun toString() = "Manual(<redacted>)"
    }
}

sealed interface PairingProgress {
    data object Connecting : PairingProgress
    data object ConfirmOnPc : PairingProgress
    data class Paired(val pcName: String) : PairingProgress
    data class Failed(val message: String, val canRetry: Boolean) : PairingProgress
}

/** Implemented by the lead on top of PairingClient. */
interface PairingController {
    fun pair(entry: PairingEntry, deviceName: String): Flow<PairingProgress>
}
