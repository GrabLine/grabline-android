package dev.grabline.app.remote.security

import dev.grabline.app.remote.protocol.Base64Url
import dev.grabline.app.remote.protocol.CrockfordCode
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

private val ID_22 = Regex("^[A-Za-z0-9_-]{22}$")

/** A `deviceId` or `pcId`: 22 base64url characters holding 16 bytes. */
internal fun isId22(id: String?): Boolean = id != null && ID_22.matches(id) && Base64Url.decode(id)?.size == 16

private fun concat(label: String, vararg parts: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(label.toByteArray(Charsets.US_ASCII))
    out.write(0)
    for (p in parts) out.write(p)
    return out.toByteArray()
}

private fun requireSizes(nonce: ByteArray, pin: ByteArray) {
    require(nonce.size == 32) { "nonce must be 32 bytes" }
    require(pin.size == 32) { "pin must be 32 bytes" }
}

/**
 * Pairing proofs (PROTOCOL.md section 5.3). The code is the HMAC key and is never sent.
 *
 * ```
 * codeProof   = HMAC-SHA256(code, "grabline-pair-v1"   || 0x00 || nonce || pin || SHA-256(device SPKI))
 * serverProof = HMAC-SHA256(code, "grabline-paired-v1" || 0x00 || nonce || pin || SHA-256(device SPKI) || deviceId)
 * ```
 *
 * Every function throws [IllegalArgumentException] on a wrongly sized input; nothing is padded or truncated.
 */
object PairingProofs {
    private const val PAIR_LABEL = "grabline-pair-v1"
    private const val PAIRED_LABEL = "grabline-paired-v1"
    const val PROOF_BYTES = 32

    fun codeProofInput(nonce: ByteArray, pin: ByteArray, deviceSpki: ByteArray): ByteArray {
        requireSizes(nonce, pin)
        require(SpkiP256.isValid(deviceSpki)) { "device key must be a 91-byte P-256 SPKI" }
        return concat(PAIR_LABEL, nonce, pin, sha256(deviceSpki))
    }

    fun serverProofInput(nonce: ByteArray, pin: ByteArray, deviceSpki: ByteArray, deviceId: String): ByteArray {
        requireSizes(nonce, pin)
        require(SpkiP256.isValid(deviceSpki)) { "device key must be a 91-byte P-256 SPKI" }
        require(isId22(deviceId)) { "deviceId must be 22 base64url characters" }
        return concat(PAIRED_LABEL, nonce, pin, sha256(deviceSpki), deviceId.toByteArray(Charsets.US_ASCII))
    }

    fun codeProof(code: String, nonce: ByteArray, pin: ByteArray, deviceSpki: ByteArray): ByteArray =
        hmac(code, codeProofInput(nonce, pin, deviceSpki))

    fun serverProof(code: String, nonce: ByteArray, pin: ByteArray, deviceSpki: ByteArray, deviceId: String): ByteArray =
        hmac(code, serverProofInput(nonce, pin, deviceSpki, deviceId))

    /**
     * True only when [received] is the PC's proof for exactly these values. Constant-time comparison;
     * any malformed input gives false (fail closed). Call this BEFORE storing anything from `paired`.
     */
    fun verifyServerProof(
        received: ByteArray?,
        code: String,
        nonce: ByteArray,
        pin: ByteArray,
        deviceSpki: ByteArray,
        deviceId: String,
    ): Boolean {
        if (received == null || received.size != PROOF_BYTES) return false
        val expected = try {
            serverProof(code, nonce, pin, deviceSpki, deviceId)
        } catch (_: Exception) {
            return false
        }
        return MessageDigest.isEqual(expected, received)
    }

    private fun hmac(code: String, input: ByteArray): ByteArray {
        require(CrockfordCode.isValid(code)) { "pairing code must be 12 Crockford characters" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(code.toByteArray(Charsets.US_ASCII), "HmacSHA256"))
        return mac.doFinal(input)
    }
}

/**
 * What the device key signs to log in (PROTOCOL.md section 6):
 * `"grabline-auth-v1" || 0x00 || nonce (32) || pin (32) || deviceId (22 ASCII)`: 103 bytes, fixed layout.
 * The signature sent in `auth` is ECDSA P-256 / SHA-256 in ASN.1 DER, base64url without padding.
 */
object AuthTranscript {
    private const val LABEL = "grabline-auth-v1"
    const val LENGTH = 16 + 1 + 32 + 32 + 22

    fun build(nonce: ByteArray, pin: ByteArray, deviceId: String): ByteArray {
        requireSizes(nonce, pin)
        require(isId22(deviceId)) { "deviceId must be 22 base64url characters" }
        return concat(LABEL, nonce, pin, deviceId.toByteArray(Charsets.US_ASCII))
    }
}
