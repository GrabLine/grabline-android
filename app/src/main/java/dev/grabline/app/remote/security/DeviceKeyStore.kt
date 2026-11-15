package dev.grabline.app.remote.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** The device key is missing, unusable, or could not be created. Never carries key material. */
open class DeviceKeyException(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)

/** There is no key under the alias any more (deleted, wiped or invalidated): this phone can never log in again. */
class DeviceKeyMissingException(message: String = "no device key", cause: Throwable? = null) : DeviceKeyException(message, cause)

/**
 * Signing keys (PROTOCOL.md section 5.6), addressed by keystore alias. Each pairing attempt creates its own key
 * under a fresh alias ([newAlias]), so a re-pair never overwrites the key of a working pairing. The private key
 * never leaves the store; callers only get the public key and signatures.
 *
 * Aliases are always `grabline-device-<pcId>` (legacy) or `grabline-device-<pcId>-<16 hex>`; anything else is refused.
 */
interface DeviceKeyStore {
    /**
     * Creates a new P-256 key under [alias], replacing any key already stored there, and returns its
     * public key as SubjectPublicKeyInfo DER (exactly 91 bytes).
     */
    @Throws(DeviceKeyException::class)
    fun create(alias: String): ByteArray

    /** The public key (91-byte SPKI DER), or null when there is no key under [alias]. */
    fun publicKeySpki(alias: String): ByteArray?

    /** ECDSA P-256 with SHA-256 over [transcript], ASN.1 DER encoded (at most 72 bytes). */
    @Throws(DeviceKeyException::class)
    fun sign(alias: String, transcript: ByteArray): ByteArray

    /** Deletes the key. No error when there is none. */
    fun delete(alias: String)

    companion object {
        const val ALIAS_PREFIX = "grabline-device-"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        const val MAX_SIGNATURE_BYTES = 72
        private val PC_ID = Regex("^[A-Za-z0-9_-]{22}$")
        private val ALIAS = Regex("^grabline-device-[A-Za-z0-9_-]{22}(-[0-9a-f]{16})?$")
        private val random = SecureRandom()

        /** The alias used before per-attempt aliases existed; rows without a `keyAlias` still point here. */
        fun legacyAlias(pcId: String): String {
            if (!PC_ID.matches(pcId)) throw DeviceKeyException("not a pcId")
            return ALIAS_PREFIX + pcId
        }

        /** A fresh alias for one pairing attempt with [pcId]: `grabline-device-<pcId>-<16 random hex>`. */
        fun newAlias(pcId: String): String {
            val bytes = ByteArray(8).also { random.nextBytes(it) }
            return legacyAlias(pcId) + "-" + bytes.joinToString("") { "%02x".format(it) }
        }

        /** Refuses anything that is not one of our aliases, so an alias can never be steered. */
        fun requireAlias(alias: String): String {
            if (!ALIAS.matches(alias)) throw DeviceKeyException("not a device key alias")
            return alias
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

    override fun create(alias: String): ByteArray {
        DeviceKeyStore.requireAlias(alias)
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

    override fun publicKeySpki(alias: String): ByteArray? = try {
        keyStore().getCertificate(DeviceKeyStore.requireAlias(alias))?.publicKey?.encoded?.takeIf { SpkiP256.isValid(it) }
    } catch (_: Exception) {
        null
    }

    override fun sign(alias: String, transcript: ByteArray): ByteArray {
        try {
            DeviceKeyStore.requireAlias(alias)
            val key = keyStore().getKey(alias, null) as? PrivateKey ?: throw DeviceKeyMissingException()
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
        } catch (e: UnrecoverableKeyException) {
            throw DeviceKeyMissingException("device key is unusable", e)
        } catch (e: Exception) {
            throw DeviceKeyException("could not sign with the device key", e)
        }
    }

    override fun delete(alias: String) {
        runCatching { deleteAlias(DeviceKeyStore.requireAlias(alias)) }
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
