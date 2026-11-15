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

    fun put(pcId: String, pair: KeyPair) {
        keys[DeviceKeyStore.alias(pcId)] = pair
    }

    override fun create(pcId: String): ByteArray {
        val alias = DeviceKeyStore.alias(pcId)
        if (failCreate) throw DeviceKeyException("test: cannot create")
        val pair = nextKey
            ?: KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        keys[alias] = pair
        created++
        return SpkiP256.require(pair.public.encoded)
    }

    override fun publicKeySpki(pcId: String): ByteArray? = keys[DeviceKeyStore.alias(pcId)]?.public?.encoded

    override fun sign(pcId: String, transcript: ByteArray): ByteArray {
        val pair = keys[DeviceKeyStore.alias(pcId)] ?: throw DeviceKeyException("no device key")
        return Signature.getInstance(DeviceKeyStore.SIGNATURE_ALGORITHM).run {
            initSign(pair.private)
            update(transcript)
            sign()
        }
    }

    override fun delete(pcId: String) {
        keys.remove(DeviceKeyStore.alias(pcId))
    }
}
