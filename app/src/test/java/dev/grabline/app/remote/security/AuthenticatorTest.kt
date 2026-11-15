package dev.grabline.app.remote.security

import dev.grabline.app.data.PairedPc
import dev.grabline.app.remote.protocol.AuthRequest
import dev.grabline.app.remote.protocol.Base64Url
import dev.grabline.app.remote.protocol.ErrorCode
import dev.grabline.app.remote.protocol.ProtocolCodec
import dev.grabline.app.remote.protocol.RequestResult
import dev.grabline.app.remote.protocol.ServerHello
import java.security.Signature
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthenticatorTest {
    private val v = Vectors
    private val keys = InMemoryDeviceKeyStore().apply { put(v.pcId, v.keyPair) }
    private val pc = PairedPc(
        pcId = v.pcId,
        name = "Study PC",
        fingerprint = v.pin,
        deviceId = v.deviceId,
        addresses = listOf("192.168.1.20"),
        port = 47821,
        protocolVersion = 6,
        pairedAt = 0,
    )
    private val hello = ProtocolCodec.decode(v.message("hello.server.json")) as ServerHello

    private fun TestScope.authenticator(store: DeviceKeyStore = keys) =
        Authenticator(store, appVersion = "1.0.0", keyDispatcher = StandardTestDispatcher(testScheduler))

    private fun verifies(transcript: ByteArray, signature: String): Boolean =
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(v.publicKey)
            update(transcript)
            verify(Base64Url.decode(signature))
        }

    @Test
    fun authFrameSignsTheVectorTranscript() = runTest {
        val step = authenticator().onHello(pc, hello, id = "r-2") as AuthStep.Send
        assertEquals("r-2", step.requestId)
        val request = (ProtocolCodec.decodeRequest(step.frame) as RequestResult.Accepted).request as AuthRequest
        assertEquals(v.deviceId, request.deviceId)
        assertEquals(6, request.protocolVersion)
        assertEquals("1.0.0", request.appVersion)
        val signature = Base64Url.decode(request.signature)!!
        assertEquals(0x30, signature[0].toInt()) // DER, as the PC expects
        assertTrue(signature.size <= 72)
        assertTrue(verifies(v.hex(v.transcriptHex), request.signature))
        assertFalse(step.toString().contains(request.signature))
    }

    @Test
    fun signatureIsBoundToThisConnectionsNonceAndThisPcsPin() = runTest {
        val auth = authenticator()
        val otherNonce = hello.copy(nonce = Base64Url.encode(ByteArray(32) { 7 }))
        assertFalse(verifies(v.hex(v.transcriptHex), auth.authRequest(pc, otherNonce).signature))
        val otherPin = pc.copy(fingerprint = TestCerts.selfSigned().pin)
        assertFalse(verifies(v.hex(v.transcriptHex), auth.authRequest(otherPin, hello).signature))
        assertTrue(verifies(v.hex(v.transcriptHex), auth.authRequest(pc, hello).signature))
    }

    @Test
    fun versionGateSendsNothing() = runTest {
        val old = ProtocolCodec.decode(v.message("hello.server.old.json")) as ServerHello
        assertEquals(AuthStep.Stop(AuthOutcome.PcTooOld), authenticator().onHello(pc, old))
        assertEquals("Update GrabLine on your PC", AuthOutcome.PcTooOld.userMessage)
        assertEquals(AuthStep.Stop(AuthOutcome.PhoneTooOld), authenticator().onHello(pc, hello.copy(minClientVersion = 7)))
    }

    @Test
    fun malformedHelloOrMissingKeyFailsClosed() = runTest {
        assertEquals(AuthStep.Stop(AuthOutcome.Failed(ErrorCode.INTERNAL)), authenticator().onHello(pc, hello.copy(nonce = "AAEC")))
        assertEquals(AuthStep.Stop(AuthOutcome.Failed(ErrorCode.INTERNAL)), authenticator().onHello(pc, hello.copy(nonce = null)))
        assertEquals(AuthStep.Stop(AuthOutcome.Failed(ErrorCode.INTERNAL)), authenticator().onHello(pc.copy(fingerprint = "x"), hello))
        // no key for this PC any more: the pairing is unusable
        assertEquals(AuthStep.Stop(AuthOutcome.PairingRemoved), authenticator(InMemoryDeviceKeyStore()).onHello(pc, hello))
    }

    @Test
    fun repliesAreInterpreted() = runTest {
        val auth = authenticator()
        val ok = auth.onReply(ProtocolCodec.decode(v.message("auth.reply.ok.json")), "r-2") as AuthOutcome.Ok
        assertEquals("Study PC", ok.reply.pcName)
        assertNull(ok.userMessage)

        val removed = auth.onReply(ProtocolCodec.decode(v.message("error.unauthorized.json")), "r-7")
        assertEquals(AuthOutcome.PairingRemoved, removed)
        assertEquals("This PC no longer accepts this phone. Pair again to reconnect", removed!!.userMessage)

        assertEquals(AuthOutcome.PhoneTooOld, auth.onReply(ProtocolCodec.decode(v.message("error.versionTooOld.json")), "r-7"))
        assertEquals(AuthOutcome.Failed(ErrorCode.RATE_LIMITED), auth.onReply(ProtocolCodec.decode(v.message("error.rateLimited.json")), "r-7"))
        // an error with no id is still the answer: nothing else is pending before authOk
        assertEquals(
            AuthOutcome.PairingRemoved,
            auth.onReply(ProtocolCodec.decode("""{"type":"error","protocolVersion":6,"code":"unauthorized","message":"not authorized"}"""), "r-2"),
        )
        // not ours: keep waiting
        assertNull(auth.onReply(ProtocolCodec.decode(v.message("auth.reply.ok.json")), "other"))
        assertNull(auth.onReply(ProtocolCodec.decode(v.message("pair.event.pending.json")), "r-2"))
        assertNull(auth.onReply(hello, "r-2"))
        // right id, wrong kind of reply, or an old PC
        assertEquals(AuthOutcome.Failed(ErrorCode.INTERNAL), auth.onReply(ProtocolCodec.decode("""{"id":"r-2","type":"ok","protocolVersion":6}"""), "r-2"))
        assertEquals(
            AuthOutcome.PcTooOld,
            auth.onReply(ProtocolCodec.decode(v.message("auth.reply.ok.json").replace("\"protocolVersion\":6", "\"protocolVersion\":5")), "r-2"),
        )
    }

    @Test
    fun closeCodesAreInterpreted() = runTest {
        val auth = authenticator()
        assertEquals(AuthOutcome.PairingRemoved, auth.onClose(4401))
        assertEquals(AuthOutcome.PairingRemoved, auth.onClose(4403))
        assertEquals(AuthOutcome.PhoneTooOld, auth.onClose(4426))
        for (code in listOf(1000, 1001, 4408, 4409, 4429)) assertNull(auth.onClose(code))
    }
}
