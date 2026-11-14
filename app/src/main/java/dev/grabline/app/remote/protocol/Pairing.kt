package dev.grabline.app.remote.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Parsing only. Proofs, keys and the TLS pin check live in the pairing code (security agent). */

/** Crockford base32 alphabet used by pairing codes (PROTOCOL.md section 5.1). */
object CrockfordCode {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH = 12

    /** Manual entry normalisation (PROTOCOL.md section 5.4); null when the result is not 12 alphabet characters. */
    fun normalize(input: String): String? {
        val sb = StringBuilder()
        for (c in input) {
            if (c == ' ' || c == '-' || c == '\t' || c == '\r' || c == '\n') continue
            sb.append(
                when (val u = c.uppercaseChar()) {
                    'O' -> '0'
                    'I', 'L' -> '1'
                    else -> u
                },
            )
        }
        val code = sb.toString()
        return code.takeIf { it.length == LENGTH && it.all { ch -> ch in ALPHABET } }
    }

    fun isValid(code: String): Boolean = code.length == LENGTH && code.all { it in ALPHABET }
}

/** Address syntax shared by the QR and manual entry. */
object AddressSyntax {
    private val ipv4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private val hostname = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$")

    fun isIpv4(s: String): Boolean = ipv4.matchEntire(s)?.groupValues?.drop(1)?.all { it.toInt() in 0..255 } == true

    /** IPv6 literal without brackets and without a zone id. */
    fun isIpv6(s: String): Boolean {
        if (s.isEmpty() || s.any { it !in "0123456789abcdefABCDEF:" } || s.count { it == ':' } < 2) return false
        val parts = s.split("::")
        if (parts.size > 2) return false
        fun groups(p: String): List<String>? = if (p.isEmpty()) emptyList() else p.split(':').takeIf { g -> g.all { it.length in 1..4 } }
        val head = groups(parts[0]) ?: return false
        if (parts.size == 1) return head.size == 8
        val tail = groups(parts[1]) ?: return false
        return head.size + tail.size <= 7
    }

    /** QR notation: dotted IPv4, or IPv6 in square brackets. Returns the address unchanged when valid. */
    fun isQrAddress(s: String): Boolean =
        isIpv4(s) || (s.length > 2 && s.startsWith("[") && s.endsWith("]") && isIpv6(s.substring(1, s.length - 1)))

    fun isHostname(s: String): Boolean = s.length <= 253 && hostname.matches(s) && !(s.all { it.isDigit() || it == '.' } && !isIpv4(s))
}

enum class PairingParseError(val userMessage: String) {
    /** Missing or malformed parameter, or not our scheme. */
    NOT_A_PAIRING_CODE(ErrorMessages.NOT_A_PAIRING_CODE),

    /** `v` is a number this phone does not know. */
    UPDATE_THIS_APP(ErrorMessages.PHONE_TOO_OLD),

    /** Manual entry: the address field is not `host:port`. */
    BAD_ADDRESS("That address doesn't look right"),

    /** Manual entry: the code does not normalise to 12 characters. */
    BAD_CODE(ErrorMessages.CODE_DOES_NOT_LOOK_RIGHT),
}

/** A parsed QR code. [code] is secret: it is kept out of [toString]. */
class PairingData(
    val addresses: List<String>,
    val port: Int,
    /** SPKI hash of the PC key, base64url (43 characters). */
    val pin: String,
    val code: String,
    val pcId: String,
    val pcName: String,
    /** Unix seconds when the session expires (PC clock). */
    val expiry: Long,
) {
    /** The 32 raw pin bytes. */
    fun pinBytes(): ByteArray = Base64Url.decode(pin)!!

    fun isExpired(nowSeconds: Long): Boolean = nowSeconds >= expiry

    override fun equals(other: Any?): Boolean =
        other is PairingData && addresses == other.addresses && port == other.port && pin == other.pin &&
            code == other.code && pcId == other.pcId && pcName == other.pcName && expiry == other.expiry

    override fun hashCode(): Int = listOf(addresses, port, pin, code, pcId, pcName, expiry).hashCode()

    override fun toString(): String = "PairingData(addresses=$addresses, port=$port, pcId=$pcId, pcName=$pcName, expiry=$expiry, code=<redacted>)"
}

sealed interface PairingUriResult {
    data class Valid(val data: PairingData) : PairingUriResult
    data class Invalid(val error: PairingParseError) : PairingUriResult
}

/** `grabline-pair:?v=1&a=..&p=..&f=..&c=..&i=..&n=..&x=..` (PROTOCOL.md section 5.2). */
object PairingUri {
    const val SCHEME = "grabline-pair:"
    private const val MAX_ADDRESSES = 8
    private val known = setOf("v", "a", "p", "f", "c", "i", "n", "x")
    private val digits = Regex("^[0-9]{1,18}$")

    fun parse(text: String): PairingUriResult {
        val raw = text.trim()
        if (!raw.startsWith(SCHEME, ignoreCase = true)) return invalid()
        val query = raw.substring(SCHEME.length).removePrefix("?")
        val params = HashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq <= 0) return invalid()
            val key = pair.substring(0, eq)
            if (key !in known) continue
            val value = percentDecode(pair.substring(eq + 1)) ?: return invalid()
            if (params.put(key, value) != null) return invalid() // duplicate known parameter: ambiguous
        }
        val version = params["v"] ?: return invalid()
        if (version != "1") return if (digits.matches(version)) PairingUriResult.Invalid(PairingParseError.UPDATE_THIS_APP) else invalid()

        val addresses = (params["a"] ?: return invalid()).split(',')
        if (addresses.size !in 1..MAX_ADDRESSES || addresses.any { !AddressSyntax.isQrAddress(it) }) return invalid()

        val portText = params["p"] ?: return invalid()
        val port = if (digits.matches(portText)) portText.toLong() else return invalid()
        if (port !in 1024..65535) return invalid()

        val pin = params["f"] ?: return invalid()
        if (pin.length != 43 || Base64Url.decode(pin)?.size != 32) return invalid()

        val code = params["c"] ?: return invalid()
        if (!CrockfordCode.isValid(code)) return invalid()

        val pcId = params["i"] ?: return invalid()
        if (pcId.length != 22 || Base64Url.decode(pcId)?.size != 16) return invalid()

        val name = params["n"] ?: return invalid()
        if (name.trim().isEmpty() || name.length > 64 || name.any { it.code < 0x20 || it.code == 0x7f } || name.contains('\u0000')) return invalid()

        val expiryText = params["x"] ?: return invalid()
        if (!digits.matches(expiryText)) return invalid()

        return PairingUriResult.Valid(
            PairingData(addresses, port.toInt(), pin, code, pcId, name.trim(), expiryText.toLong()),
        )
    }

    private fun invalid() = PairingUriResult.Invalid(PairingParseError.NOT_A_PAIRING_CODE)

    /** Strict percent-decoding to UTF-8; null on a bad escape or invalid UTF-8. '+' stays '+'. */
    internal fun percentDecode(s: String): String? {
        if ('%' !in s) return s
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            if (s[i] == '%') {
                if (i + 2 >= s.length) return null
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                out.write(hi * 16 + lo)
                i += 3
            } else {
                var j = i
                while (j < s.length && s[j] != '%') j++
                out.write(s.substring(i, j).toByteArray(Charsets.UTF_8))
                i = j
            }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(out.toByteArray()))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}

/** Manual entry (PROTOCOL.md section 5.4). [code] is secret: kept out of [toString]. */
class ManualPairingData(
    /** QR notation: dotted IPv4, `[ipv6]`, or a host name. */
    val address: String,
    val port: Int,
    /** Normalised, 12 characters. */
    val code: String,
) {
    override fun equals(other: Any?): Boolean =
        other is ManualPairingData && address == other.address && port == other.port && code == other.code

    override fun hashCode(): Int = listOf(address, port, code).hashCode()

    override fun toString(): String = "ManualPairingData(address=$address, port=$port, code=<redacted>)"
}

sealed interface ManualPairingResult {
    data class Valid(val data: ManualPairingData) : ManualPairingResult
    data class Invalid(val error: PairingParseError) : ManualPairingResult
}

object ManualPairing {
    /** [text]: the address on the first line (or first word) and the code after it, as in `qr.manual.txt`. */
    fun parse(text: String): ManualPairingResult {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ManualPairingResult.Invalid(PairingParseError.BAD_ADDRESS)
        return if (lines.size >= 2) {
            parse(lines[0], lines.drop(1).joinToString(""))
        } else {
            val one = lines[0]
            val split = one.indexOfFirst { it.isWhitespace() }
            if (split < 0) ManualPairingResult.Invalid(PairingParseError.BAD_CODE) else parse(one.substring(0, split), one.substring(split))
        }
    }

    fun parse(address: String, code: String): ManualPairingResult {
        val hostPort = parseAddress(address.trim()) ?: return ManualPairingResult.Invalid(PairingParseError.BAD_ADDRESS)
        val normalised = CrockfordCode.normalize(code) ?: return ManualPairingResult.Invalid(PairingParseError.BAD_CODE)
        return ManualPairingResult.Valid(ManualPairingData(hostPort.first, hostPort.second, normalised))
    }

    /** `host:port`, IPv6 in brackets, port optional (default [DEFAULT_PORT]), 1024 to 65535. */
    fun parseAddress(text: String): Pair<String, Int>? {
        if (text.isEmpty()) return null
        val host: String
        val portText: String?
        if (text.startsWith("[")) {
            val close = text.indexOf(']')
            if (close < 0) return null
            host = text.substring(0, close + 1)
            if (!AddressSyntax.isQrAddress(host)) return null
            val rest = text.substring(close + 1)
            portText = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> rest.substring(1)
                else -> return null
            }
        } else {
            if (text.count { it == ':' } > 1) return null
            host = text.substringBefore(':')
            portText = if (':' in text) text.substringAfter(':') else null
            if (!AddressSyntax.isIpv4(host) && !AddressSyntax.isHostname(host)) return null
        }
        val port = if (portText == null) DEFAULT_PORT else {
            if (portText.isEmpty() || portText.length > 5 || !portText.all { it.isDigit() }) return null
            portText.toInt()
        }
        if (port !in 1024..65535) return null
        return host to port
    }
}
