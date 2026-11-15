package dev.grabline.app.remote.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** The device key is missing, unusable, or could not be created. Never carries key material. */
class DeviceKeyException(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)

/**
 * One signing key per paired PC (PROTOCOL.md section 5.6). The private key never leaves the store;
 * callers only get the public key and signatures.
 *
 * `pcId` is always the 22-character base64url id of the PC; anything else is refused.
 */
interface DeviceKeyStore {
    /**
     * Creates a new P-256 key for [pcId], replacing any key already stored for it, and returns its
     * public key as SubjectPublicKeyInfo DER (exactly 91 bytes).
     */
    @Throws(DeviceKeyException::class)
    fun create(pcId: String): ByteArray

    /** The public key (91-byte SPKI DER), or null when there is no key for [pcId]. */
    fun publicKeySpki(pcId: String): ByteArray?

    /** ECDSA P-256 with SHA-256 over [transcript], ASN.1 DER encoded (at most 72 bytes). */
    @Throws(DeviceKeyException::class)
    fun sign(pcId: String, transcript: ByteArray): ByteArray

    /** Deletes the key. No error when there is none. */
    fun delete(pcId: String)

    companion object {
        const val ALIAS_PREFIX = "grabline-device-"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        const val MAX_SIGNATURE_BYTES = 72
        private val PC_ID = Regex("^[A-Za-z0-9_-]{22}$")

        /** Keystore alias for [pcId]. Refuses anything that is not a pcId, so an alias can never be steered. */
        fun alias(pcId: String): String {
            if (!PC_ID.matches(pcId)) throw DeviceKeyException("not a pcId")
            return ALIAS_PREFIX + pcId
        }
    }
}

/** Shape check for a P-256 SubjectPublicKeyInfo with an uncompressed point (what `pair.publicKey` must be). */
object SpkiP256 {
    const val LENGTH = 91

    /** SEQUENCE { SEQUENCE { id-ecPublicKey, prime256v1 }, BIT STRING (0 unused bits) { 0x04 ... } } */
    private val PREFIX = byteArrayOf(
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01,
        0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00, 0x04,
    )

    fun isValid(spki: ByteArray?): Boolean {
        if (spki == null || spki.size != LENGTH) return false
        for (i in PREFIX.indices) if (spki[i] != PREFIX[i]) return false
        return true
    }

    @Throws(DeviceKeyException::class)
    fun require(spki: ByteArray?): ByteArray {
        if (!isValid(spki)) throw DeviceKeyException("public key is not a 91-byte P-256 SPKI")
        return spki!!
    }
}

/**
 * Keys in AndroidKeyStore: EC secp256r1, purpose SIGN only, digest SHA-256, non-exportable, no user
 * authentication (the app reconnects in the background). StrongBox is used when [preferStrongBox] is set
 * and the device has it; otherwise the TEE.
 */
class AndroidDeviceKeyStore(private val preferStrongBox: Boolean = false) : DeviceKeyStore {

    override fun create(pcId: String): ByteArray {
        val alias = DeviceKeyStore.alias(pcId)
        try {
            deleteAlias(alias)
            val spki = if (preferStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    generate(alias, strongBox = true)
                } catch (_: ProviderException) {
                    // StrongBoxUnavailableException (a ProviderException): fall back to the TEE.
                    deleteAlias(alias)
                    generate(alias, strongBox = false)
                }
            } else {
                generate(alias, strongBox = false)
            }
            return SpkiP256.require(spki)
        } catch (e: DeviceKeyException) {
            runCatching { deleteAlias(alias) }
            throw e
        } catch (e: Exception) {
            runCatching { deleteAlias(alias) }
            throw DeviceKeyException("could not create the device key", e)
        }
    }

    private fun generate(alias: String, strongBox: Boolean): ByteArray? {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        generator.initialize(spec)
        return generator.generateKeyPair().public.encoded
    }

    override fun publicKeySpki(pcId: String): ByteArray? = try {
        keyStore().getCertificate(DeviceKeyStore.alias(pcId))?.publicKey?.encoded?.takeIf { SpkiP256.isValid(it) }
    } catch (_: Exception) {
        null
    }

    override fun sign(pcId: String, transcript: ByteArray): ByteArray {
        val alias = DeviceKeyStore.alias(pcId)
        try {
            val key = keyStore().getKey(alias, null) as? PrivateKey ?: throw DeviceKeyException("no device key")
            val signature = Signature.getInstance(DeviceKeyStore.SIGNATURE_ALGORITHM).run {
                initSign(key)
                update(transcript)
                sign()
            }
            if (signature.isEmpty() || signature.size > DeviceKeyStore.MAX_SIGNATURE_BYTES) {
                throw DeviceKeyException("unexpected signature size")
            }
            return signature
        } catch (e: DeviceKeyException) {
            throw e
        } catch (e: Exception) {
            throw DeviceKeyException("could not sign with the device key", e)
        }
    }

    override fun delete(pcId: String) {
        val alias = DeviceKeyStore.alias(pcId)
        runCatching { deleteAlias(alias) }
    }

    private fun deleteAlias(alias: String) {
        val ks = keyStore()
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}
