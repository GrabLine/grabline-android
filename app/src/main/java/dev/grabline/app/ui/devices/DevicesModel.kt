package dev.grabline.app.ui.devices

import dev.grabline.app.remote.connection.PcLinkState
import dev.grabline.app.remote.protocol.CrockfordCode
import dev.grabline.app.remote.protocol.ManualPairing
import dev.grabline.app.remote.protocol.ManualPairingResult
import java.util.Locale

/** Each link state in the spec's plain words; the text lives in strings_devices.xml. */
enum class PcStatus {
    Online, Connecting, Offline, PcTooOld, PhoneTooOld, IdentityChanged, PairingRemoved;

    val isOnline get() = this == Online
    /** States where the only way forward is to pair again. */
    val needsRepair get() = this == IdentityChanged || this == PairingRemoved
}

fun pcStatusOf(link: PcLinkState?): PcStatus = when (link) {
    null, PcLinkState.Connecting -> PcStatus.Connecting
    is PcLinkState.Online -> PcStatus.Online
    is PcLinkState.Offline -> PcStatus.Offline
    PcLinkState.PcTooOld -> PcStatus.PcTooOld
    PcLinkState.PhoneTooOld -> PcStatus.PhoneTooOld
    PcLinkState.IdentityChanged -> PcStatus.IdentityChanged
    PcLinkState.PairingRemoved -> PcStatus.PairingRemoved
}

/** Speed cap input. KB/s and MB/s are binary (1 KB = 1024 bytes), matching how speeds are shown. */
enum class SpeedUnit(val bytes: Long) { KB(1024L), MB(1024L * 1024L) }

object SpeedCap {
    /** Bytes per second for [text] in [unit], or null when it is not a positive number. */
    fun toBytesPerSecond(text: String, unit: SpeedUnit): Long? {
        val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (value.isNaN() || value.isInfinite() || value <= 0.0) return null
        val bytes = value * unit.bytes
        if (bytes < 1024.0 || bytes > 10_000_000_000.0) return null
        return bytes.toLong()
    }

    /** A stored limit back to a value and unit for the dialog. */
    fun fromBytesPerSecond(bytes: Long): Pair<String, SpeedUnit> =
        if (bytes >= SpeedUnit.MB.bytes && bytes % SpeedUnit.MB.bytes == 0L) {
            (bytes / SpeedUnit.MB.bytes).toString() to SpeedUnit.MB
        } else {
            trim(bytes.toDouble() / SpeedUnit.KB.bytes) to SpeedUnit.KB
        }

    private fun trim(v: Double): String {
        val s = String.format(Locale.US, "%.1f", v)
        return s.removeSuffix(".0")
    }
}

/** Manual entry form and its validation (uses [ManualPairing] for address and code). */
data class ManualForm(
    val address: String = "",
    val port: String = "",
    val code: String = "",
    val addressError: Boolean = false,
    val portError: Boolean = false,
    val codeError: Boolean = false,
) {
    /** The text handed to the pairing controller, or null (with errors set in [validated]). */
    fun entryText(): String? = (parse() as? ManualPairingResult.Valid)?.data?.let { "${it.address}:${it.port}\n${it.code}" }

    private fun portValue(): Int? = port.trim().takeIf { it.isNotEmpty() }?.let { p ->
        p.takeIf { it.length <= 5 && it.all(Char::isDigit) }?.toInt()?.takeIf { it in 1024..65535 }
    }

    private fun parse(): ManualPairingResult {
        val host = address.trim()
        val joined = if (port.isBlank()) host else "$host:${port.trim()}"
        return ManualPairing.parse(joined, code)
    }

    fun validated(): ManualForm = copy(
        addressError = ManualPairing.parseAddress(address.trim()) == null,
        portError = port.isNotBlank() && portValue() == null,
        codeError = CrockfordCode.normalize(code) == null,
    )

    val hasError get() = addressError || portError || codeError
}

/** Speeds and sizes for the PC screen. */
object Formatting {
    enum class Unit { B, KB, MB, GB }

    data class Sized(val unit: Unit, val text: String, val whole: Long)

    fun size(bytes: Long): Sized {
        val b = bytes.coerceAtLeast(0)
        return when {
            b >= 1L shl 30 -> Sized(Unit.GB, one(b / (1L shl 30).toDouble()), b)
            b >= 1L shl 20 -> Sized(Unit.MB, one(b / (1L shl 20).toDouble()), b)
            b >= 1L shl 10 -> Sized(Unit.KB, one(b / 1024.0), b)
            else -> Sized(Unit.B, b.toString(), b)
        }
    }

    private fun one(v: Double) = String.format(Locale.getDefault(), "%.1f", v)
}

@androidx.annotation.StringRes
fun PcStatus.labelRes(): Int = when (this) {
    PcStatus.Online -> dev.grabline.app.R.string.status_online
    PcStatus.Connecting -> dev.grabline.app.R.string.status_connecting
    PcStatus.Offline -> dev.grabline.app.R.string.status_offline
    PcStatus.PcTooOld -> dev.grabline.app.R.string.status_pc_too_old
    PcStatus.PhoneTooOld -> dev.grabline.app.R.string.status_phone_too_old
    PcStatus.IdentityChanged -> dev.grabline.app.R.string.status_identity_changed
    PcStatus.PairingRemoved -> dev.grabline.app.R.string.status_pairing_removed
}
