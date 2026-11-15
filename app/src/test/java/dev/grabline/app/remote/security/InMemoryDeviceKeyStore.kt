package dev.grabline.app.remote.security

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.ConcurrentHashMap

/** Software keys for JVM unit tests only. Production uses [AndroidDeviceKeyStore]. */
class InMemoryDeviceKeyStore : DeviceKeyStore {
    private val keys = ConcurrentHashMap<String, KeyPair>()

    /** Number of keys ever created, to assert "no key was made". */
    @Volatile var created: Int = 0
        private set

    /** Makes [create] fail, to exercise the "could not create a key" path. */
    @Volatile var failCreate: Boolean = false

    /** When set, [create] stores this pair instead of a random one (to reproduce vectors.json). */
    @Volatile var nextKey: KeyPair? = null

    val aliases: Set<String> get() = keys.keys.toSet()

    /** Stores [pair] under [alias] (use [DeviceKeyStore.legacyAlias] for a pairing row without a keyAlias). */
    fun put(alias: String, pair: KeyPair) {
        keys[DeviceKeyStore.requireAlias(alias)] = pair
    }

    override fun create(alias: String): ByteArray {
        DeviceKeyStore.requireAlias(alias)
        if (failCreate) throw DeviceKeyException("test: cannot create")
        val pair = nextKey
            ?: KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        keys[alias] = pair
        created++
        return SpkiP256.require(pair.public.encoded)
    }

    override fun publicKeySpki(alias: String): ByteArray? = keys[DeviceKeyStore.requireAlias(alias)]?.public?.encoded

    override fun sign(alias: String, transcript: ByteArray): ByteArray {
        val pair = keys[DeviceKeyStore.requireAlias(alias)] ?: throw DeviceKeyMissingException()
        return Signature.getInstance(DeviceKeyStore.SIGNATURE_ALGORITHM).run {
            initSign(pair.private)
            update(transcript)
            sign()
        }
    }

    override fun delete(alias: String) {
        keys.remove(DeviceKeyStore.requireAlias(alias))
    }
}
