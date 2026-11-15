package dev.grabline.app.remote.security

import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Needs a device or emulator: the key lives in AndroidKeyStore. */
@RunWith(AndroidJUnit4::class)
class AndroidDeviceKeyStoreTest {
    /** These are keystore aliases now: one legacy-style, one fresh per-attempt alias. */
    private val pcId = DeviceKeyStore.legacyAlias("A3D7Za2aw26jbGofVV3ZAQ")
    private val otherPcId = DeviceKeyStore.newAlias("KsucBZHeyJguN6H0BzJQiw")
    private val store = AndroidDeviceKeyStore()
    private val transcript = ByteArray(103) { it.toByte() }

    @After
    fun cleanUp() {
        store.delete(pcId)
        store.delete(otherPcId)
    }

    private fun androidKeyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun verifies(spki: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(data)
            verify(signature)
        }
    }

    @Test
    fun createsAP256KeyUnderThePerPcAlias() {
        val spki = store.create(pcId)
        assertEquals(91, spki.size)
        assertTrue(SpkiP256.isValid(spki))
        assertArrayEquals(spki, store.publicKeySpki(pcId))
        assertTrue(androidKeyStore().containsAlias(pcId))
        assertNull(store.publicKeySpki(otherPcId))
    }

    @Test
    fun privateKeyIsNotExportableAndOnlySigns() {
        store.create(pcId)
        val key = androidKeyStore().getKey(pcId, null) as PrivateKey
        assertNull("no key material leaves the keystore", key.encoded)
        val info = KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
        assertEquals(KeyProperties.PURPOSE_SIGN, info.purposes)
        assertEquals(256, info.keySize)
        assertFalse(info.isUserAuthenticationRequired)
        assertArrayEquals(arrayOf(KeyProperties.DIGEST_SHA256), info.digests)
    }

    @Test
    fun signsDerSignaturesThatVerifyWithThePublicKey() {
        val spki = store.create(pcId)
        val signature = store.sign(pcId, transcript)
        assertEquals(0x30, signature[0].toInt())
        assertTrue(signature.size <= 72)
        assertTrue(verifies(spki, transcript, signature))
        assertFalse(verifies(spki, transcript.copyOf().also { it[0] = 9 }, signature))
    }

    @Test
    fun createReplacesTheOldKeyAndKeysArePerPc() {
        val first = store.create(pcId)
        val second = store.create(pcId)
        assertFalse(first.contentEquals(second))
        val other = store.create(otherPcId)
        assertFalse(second.contentEquals(other))
        assertTrue(verifies(second, transcript, store.sign(pcId, transcript)))
        assertFalse(verifies(other, transcript, store.sign(pcId, transcript)))
    }

    @Test
    fun deleteRemovesTheKey() {
        store.create(pcId)
        store.delete(pcId)
        assertNull(store.publicKeySpki(pcId))
        assertFalse(androidKeyStore().containsAlias(pcId))
        assertThrows(DeviceKeyException::class.java) { store.sign(pcId, transcript) }
        store.delete(pcId) // no error when there is none
    }

    @Test
    fun refusesAnythingThatIsNotAPcId() {
        assertThrows(DeviceKeyException::class.java) { store.create("../evil") }
        assertThrows(DeviceKeyException::class.java) { store.sign("", transcript) }
        assertNotNull(store)
    }
}
