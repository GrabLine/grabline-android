package dev.grabline.app.remote.security

import dev.grabline.app.remote.protocol.Base64Url
import java.security.Signature
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Proofs and transcripts must reproduce vectors.json byte for byte (PROTOCOL.md sections 5.3 and 6). */
class ProofVectorsTest {
    private val v = Vectors

    @Test
    fun deviceKeyIsA91ByteP256Spki() {
        assertEquals(91, v.spki.size)
        assertTrue(SpkiP256.isValid(v.spki))
        assertEquals(v.spkiSha256Hex, v.toHex(sha256(v.spki)))
        assertEquals(v.pin, Base64Url.encode(v.pinBytes))
        assertArrayEquals(v.pinBytes, Pin.bytes(v.pin))
    }

    @Test
    fun spkiShapeCheckRefusesOtherKeys() {
        assertFalse(SpkiP256.isValid(null))
        assertFalse(SpkiP256.isValid(v.spki.copyOf(90)))
        assertFalse(SpkiP256.isValid(v.spki + 0))
        // compressed-point marker instead of 0x04
        assertFalse(SpkiP256.isValid(v.spki.copyOf().also { it[26] = 0x02 }))
        // another curve OID byte
        assertFalse(SpkiP256.isValid(v.spki.copyOf().also { it[22] = 0x22 }))
        assertThrows(DeviceKeyException::class.java) { SpkiP256.require(ByteArray(91)) }
    }

    @Test
    fun codeProofMatchesTheVector() {
        assertEquals(v.codeProofInputHex, v.toHex(PairingProofs.codeProofInput(v.nonce, v.pinBytes, v.spki)))
        assertEquals(v.codeProof, Base64Url.encode(PairingProofs.codeProof(v.code, v.nonce, v.pinBytes, v.spki)))
    }

    @Test
    fun serverProofMatchesTheVector() {
        assertEquals(v.serverProofInputHex, v.toHex(PairingProofs.serverProofInput(v.nonce, v.pinBytes, v.spki, v.deviceId)))
        assertEquals(v.serverProof, Base64Url.encode(PairingProofs.serverProof(v.code, v.nonce, v.pinBytes, v.spki, v.deviceId)))
        assertTrue(PairingProofs.verifyServerProof(Base64Url.decode(v.serverProof), v.code, v.nonce, v.pinBytes, v.spki, v.deviceId))
    }

    @Test
    fun serverProofIsBoundToEveryInput() {
        val good = Base64Url.decode(v.serverProof)!!
        fun verify(
            proof: ByteArray? = good,
            code: String = v.code,
            nonce: ByteArray = v.nonce,
            pin: ByteArray = v.pinBytes,
            spki: ByteArray = v.spki,
            deviceId: String = v.deviceId,
        ) = PairingProofs.verifyServerProof(proof, code, nonce, pin, spki, deviceId)

        assertTrue(verify())
        assertFalse(verify(proof = null))
        assertFalse(verify(proof = good.copyOf(31)))
        assertFalse(verify(proof = good.copyOf().also { it[31] = (it[31] + 1).toByte() }))
        assertFalse(verify(proof = Base64Url.decode(v.codeProof))) // the phone's own proof is not the PC's
        assertFalse(verify(code = "7K3M9QX2RTBX"))
        assertFalse(verify(nonce = v.nonce.copyOf().also { it[0] = 1 }))
        assertFalse(verify(pin = v.pinBytes.copyOf().also { it[0] = 1 }))
        assertFalse(verify(spki = TestCerts.newKeyPair().public.encoded))
        assertFalse(verify(deviceId = v.pcId))
        // malformed inputs are a refusal, not an exception
        assertFalse(verify(code = "short"))
        assertFalse(verify(nonce = ByteArray(31)))
        assertFalse(verify(deviceId = "not an id"))
    }

    @Test
    fun wrongSizesAreRefusedNotPadded() {
        assertThrows(IllegalArgumentException::class.java) { PairingProofs.codeProof(v.code, ByteArray(31), v.pinBytes, v.spki) }
        assertThrows(IllegalArgumentException::class.java) { PairingProofs.codeProof(v.code, v.nonce, ByteArray(33), v.spki) }
        assertThrows(IllegalArgumentException::class.java) { PairingProofs.codeProof(v.code, v.nonce, v.pinBytes, ByteArray(91)) }
        assertThrows(IllegalArgumentException::class.java) { PairingProofs.codeProof("7k3m9qx2rtbw", v.nonce, v.pinBytes, v.spki) }
        assertThrows(IllegalArgumentException::class.java) { AuthTranscript.build(v.nonce, v.pinBytes, v.deviceId + "A") }
        assertThrows(IllegalArgumentException::class.java) { AuthTranscript.build(ByteArray(0), v.pinBytes, v.deviceId) }
    }

    @Test
    fun authTranscriptMatchesTheVector() {
        val transcript = AuthTranscript.build(v.nonce, v.pinBytes, v.deviceId)
        assertEquals(AuthTranscript.LENGTH, transcript.size)
        assertEquals(v.transcriptHex, v.toHex(transcript))
    }

    @Test
    fun fixtureSignatureVerifiesOverOurTranscript() {
        val transcript = AuthTranscript.build(v.nonce, v.pinBytes, v.deviceId)
        assertTrue(verify(transcript, v.signatureDer))
        assertFalse(verify(AuthTranscript.build(v.nonce.copyOf().also { it[5] = 0 }, v.pinBytes, v.deviceId), v.signatureDer))
    }

    @Test
    fun ourSignatureIsDerAndVerifiesWithTheTestPublicKey() {
        val store = InMemoryDeviceKeyStore().apply { put(DeviceKeyStore.legacyAlias(v.pcId), v.keyPair) }
        assertArrayEquals(v.spki, store.publicKeySpki(DeviceKeyStore.legacyAlias(v.pcId)))
        val transcript = AuthTranscript.build(v.nonce, v.pinBytes, v.deviceId)
        val signature = store.sign(DeviceKeyStore.legacyAlias(v.pcId), transcript)
        assertEquals("ASN.1 DER SEQUENCE", 0x30, signature[0].toInt())
        assertEquals(signature.size - 2, signature[1].toInt())
        assertTrue(signature.size <= DeviceKeyStore.MAX_SIGNATURE_BYTES)
        assertTrue(verify(transcript, signature))
    }

    @Test
    fun aliasOnlyAcceptsAPcId() {
        assertEquals("grabline-device-${v.pcId}", DeviceKeyStore.legacyAlias(v.pcId))
        for (bad in listOf("", "short", v.pcId + "A", "../../etc/passwd0000000", "A3D7Za2aw26jbGofVV3ZA=")) {
            assertThrows(DeviceKeyException::class.java) { DeviceKeyStore.legacyAlias(bad) }
        }
    }

    private fun verify(transcript: ByteArray, signature: ByteArray): Boolean =
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(v.publicKey)
            update(transcript)
            verify(signature)
        }
}
