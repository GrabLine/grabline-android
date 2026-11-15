package dev.grabline.app.remote.pairing

import dev.grabline.app.remote.protocol.Base64Url
import dev.grabline.app.remote.protocol.ErrorCode
import dev.grabline.app.remote.protocol.ManualPairing
import dev.grabline.app.remote.protocol.ManualPairingData
import dev.grabline.app.remote.protocol.ManualPairingResult
import dev.grabline.app.remote.protocol.PairRequest
import dev.grabline.app.remote.protocol.PairingData
import dev.grabline.app.remote.protocol.PairingUri
import dev.grabline.app.remote.protocol.PairingUriResult
import dev.grabline.app.remote.protocol.ProtocolCodec
import dev.grabline.app.remote.protocol.RequestResult
import dev.grabline.app.remote.security.InMemoryDeviceKeyStore
import dev.grabline.app.remote.security.Pin
import dev.grabline.app.remote.security.PinMismatchException
import dev.grabline.app.remote.security.PairingProofs
import dev.grabline.app.remote.security.TestCerts
import dev.grabline.app.remote.security.Vectors
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeConnection(override val peerPin: String) : Connection {
    val incoming = Channel<Frame>(Channel.UNLIMITED)
    val sent = mutableListOf<String>()
    var closedWith: Int? = null
    var onSend: (String) -> Unit = {}
    private var terminal: Frame? = null

    override suspend fun receive(): Frame {
        terminal?.let { return it }
        val frame = incoming.receive()
        if (frame !is Frame.Text) terminal = frame
        return frame
    }

    override fun send(text: String): Boolean {
        if (closedWith != null) return false
        sent += text
        onSend(text)
        return true
    }

    override fun close(code: Int) {
        if (closedWith == null) closedWith = code
    }

    fun push(json: String) {
        incoming.trySend(Frame.Text(json))
    }

    fun pushClose(code: Int) {
        incoming.trySend(Frame.Closed(code))
    }
}

private class FakeTransport(private val answer: (String) -> Connection) : Transport {
    val attempts = mutableListOf<Pair<String, TrustMode>>()

    override suspend fun connect(address: String, port: Int, trust: TrustMode): Connection {
        attempts += address to trust
        return answer(address)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PairingClientTest {
    private val v = Vectors
    private val qr: PairingData = (PairingUri.parse(v.qrUri) as PairingUriResult.Valid).data
    private val keys = InMemoryDeviceKeyStore()
    private val hello = v.message("hello.server.json")
    private val progress = mutableListOf<PairingProgress>()
    private var now = qr.expiry - 60

    private fun TestScope.client(transport: Transport) = PairingClient(
        transport = transport,
        keyStore = keys,
        deviceName = "Pixel 8",
        appVersion = "1.0.0",
        nowSeconds = { now },
        keyDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun withId(fixture: String, id: String = PairingClient.REQUEST_ID): String {
        val message = Json.parseToJsonElement(v.message(fixture)).jsonObject
        return JsonObject(message + ("id" to JsonPrimitive(id))).toString()
    }

    private fun sentPair(text: String): PairRequest =
        (ProtocolCodec.decodeRequest(text) as RequestResult.Accepted).request as PairRequest

    /** What an honest PC that knows [code] and has the key with [pin] replies after Allow. */
    private fun paired(sent: String, code: String = v.code, pin: String = qr.pin, deviceId: String = v.deviceId, pcId: String = v.pcId): String {
        val spki = Base64Url.decode(sentPair(sent).publicKey)!!
        val proof = PairingProofs.serverProof(code, v.nonce, Pin.bytes(pin)!!, spki, deviceId)
        return """{"id":"pair-1","type":"paired","protocolVersion":6,"deviceId":"$deviceId","pcId":"$pcId","pcName":"Study PC","serverProof":"${Base64Url.encode(proof)}"}"""
    }

    private fun honestPc(pin: String = qr.pin, afterPair: FakeConnection.(String) -> Unit): FakeConnection =
        FakeConnection(pin).apply {
            push(hello)
            onSend = { afterPair(it) }
        }

    private fun assertNothingLeft(connection: FakeConnection) {
        assertTrue("the device key must be deleted", keys.aliases.isEmpty())
        assertNotNull("the socket must be closed", connection.closedWith)
    }

    // ------------------------------------------------------------------ approved

    @Test
    fun helloPendingPairedGivesAPairing() = runTest {
        val pc = honestPc {
            push(v.message("pair.event.pending.json"))
            push(paired(it))
        }
        val transport = FakeTransport { pc }
        val result = client(transport).pair(qr) { progress += it }

        val ok = result as PairResult.Paired
        assertEquals(v.deviceId, ok.deviceId)
        assertEquals(v.pcId, ok.pcId)
        assertEquals("Study PC", ok.pcName)
        assertEquals(qr.pin, ok.pin)
        assertEquals(qr.addresses, ok.addresses)
        assertEquals(47821, ok.port)
        assertEquals("192.168.1.20", ok.address)
        assertEquals(6, ok.protocolVersion)
        assertNull(ok.userMessage)
        assertTrue(ok.connection === pc)
        assertNull("the socket stays open for auth", pc.closedWith)
        assertEquals(TrustMode.Pinned(qr.pin), transport.attempts.single().second)

        assertEquals(setOf("grabline-device-${v.pcId}"), keys.aliases)
        val request = sentPair(pc.sent.single())
        assertEquals("pair-1", request.id)
        assertEquals(6, request.protocolVersion)
        assertEquals("Pixel 8", request.deviceName)
        val spki = keys.publicKeySpki(v.pcId)!!
        assertEquals(Base64Url.encode(spki), request.publicKey)
        assertEquals(Base64Url.encode(PairingProofs.codeProof(v.code, v.nonce, v.pinBytes, spki)), request.codeProof)
        assertFalse("the code is never sent", pc.sent.single().contains(v.code))

        assertEquals(PairingProgress.ConfirmOnPc(60), progress.last())
        assertEquals("Confirm on your PC", (progress.last() as PairingProgress.ConfirmOnPc).userMessage)
        assertTrue(progress.first() is PairingProgress.Connecting)
    }

    @Test
    fun reproducesTheFixturesWithTheVectorKey() = runTest {
        keys.nextKey = v.keyPair
        val pc = honestPc { push(withId("pair.reply.paired.json")) }
        val result = client(FakeTransport { pc }).pair(qr)

        val sent = Json.parseToJsonElement(pc.sent.single()).jsonObject
        val fixture = Json.parseToJsonElement(v.message("pair.request.json")).jsonObject
        assertEquals(v.codeProof, sent["codeProof"]!!.jsonPrimitive.content)
        for (field in listOf("type", "protocolVersion", "deviceName", "publicKey", "codeProof", "appVersion")) {
            assertEquals(field, fixture[field], sent[field])
        }
        assertEquals(v.deviceId, (result as PairResult.Paired).deviceId)
    }

    @Test
    fun unknownAndMalformedFramesAreDropped() = runTest {
        val pc = honestPc {
            push("not json")
            push("""{"type":"event","protocolVersion":6,"event":"something.new","seq":1}""")
            push("""{"id":"other","type":"paired","protocolVersion":6,"deviceId":"${v.deviceId}","pcId":"${v.pcId}","pcName":"x","serverProof":"${v.serverProof}"}""")
            push(paired(it))
        }
        assertTrue(client(FakeTransport { pc }).pair(qr) is PairResult.Paired)
    }

    // ------------------------------------------------------------------ refused by the PC

    @Test
    fun deniedDeletesTheKey_QA04() = runTest {
        val pc = honestPc {
            push(v.message("pair.event.pending.json"))
            push(withId("pair.reply.denied.json"))
            pushClose(4403)
        }
        val result = client(FakeTransport { pc }).pair(qr)
        assertEquals(PairResult.PcDeclined, result)
        assertEquals("PC declined", result.userMessage)
        assertEquals(1, keys.created)
        assertNothingLeft(pc)
    }

    @Test
    fun close4403WithoutAReplyIsAlsoDeclined() = runTest {
        val pc = honestPc { pushClose(4403) }
        assertEquals(PairResult.PcDeclined, client(FakeTransport { pc }).pair(qr))
        assertNothingLeft(pc)
    }

    @Test
    fun expired_QA02() = runTest {
        val pc = honestPc { push(withId("error.pairingExpired.json")) }
        val result = client(FakeTransport { pc }).pair(qr)
        assertEquals(PairResult.CodeExpired, result)
        assertEquals("Code expired. Show a new code on your PC", result.userMessage)
        assertNothingLeft(pc)
    }

    @Test
    fun locked_QA03() = runTest {
        val pc = honestPc { push(withId("error.pairingLocked.json")) }
        val result = client(FakeTransport { pc }).pair(qr)
        assertEquals(PairResult.CodeLocked, result)
        assertEquals("Too many wrong codes. Show a new code on your PC", result.userMessage)
        assertNothingLeft(pc)
    }

    @Test
    fun badCodeCarriesAttemptsLeft() = runTest {
        val pc = honestPc { push(v.message("error.badCode.json").replace("\"r-7\"", "\"pair-1\"")) }
        val result = client(FakeTransport { pc }).pair(qr)
        assertEquals(PairResult.BadCode(4), result)
        assertEquals("That code didn't match. Check it and try again", result.userMessage)
        assertNothingLeft(pc)
    }

    @Test
    fun errorWithoutAnIdStillEndsPairing() = runTest {
        val pc = honestPc { push("""{"type":"error","protocolVersion":6,"code":"conflict","message":"x"}""") }
        assertEquals(PairResult.Rejected(ErrorCode.CONFLICT), client(FakeTransport { pc }).pair(qr))
        assertNothingLeft(pc)
    }

    @Test
    fun versionTooOldAndClose4426MeanUpdateThisPhone() = runTest {
        val a = honestPc { push(withId("error.versionTooOld.json")) }
        assertEquals(PairResult.PhoneTooOld, client(FakeTransport { a }).pair(qr))
        val b = honestPc { pushClose(4426) }
        val result = client(FakeTransport { b }).pair(qr)
        assertEquals(PairResult.PhoneTooOld, result)
        assertEquals("Update GrabLine on this phone", result.userMessage)
        assertNothingLeft(b)
    }

    // ------------------------------------------------------------------ serverProof

    @Test
    fun badServerProofStoresNothing() = runTest {
        val pc = honestPc { push(paired(it, code = "0000000000AB")) } // a PC that does not know the code
        val result = client(FakeTransport { pc }).pair(qr)
        assertEquals(PairResult.ProofMismatch, result)
        assertEquals("That code didn't match. Check it and try again", result.userMessage)
        assertNothingLeft(pc)
    }

    @Test
    fun serverProofForAnotherDeviceIdPinOrKeyIsRefused() = runTest {
        // proof made for another deviceId than the one in the reply
        val a = honestPc { push(paired(it).replace(v.deviceId, v.pcId)) }
        assertEquals(PairResult.ProofMismatch, client(FakeTransport { a }).pair(qr))
        assertNothingLeft(a)
        // proof bound to another PC key (what a relay in the middle would produce)
        val b = honestPc { push(paired(it, pin = TestCerts.selfSigned().pin)) }
        assertEquals(PairResult.ProofMismatch, client(FakeTransport { b }).pair(qr))
        assertNothingLeft(b)
        // the fixture proof belongs to the vector key, not to the key this phone just made
        val c = honestPc { push(withId("pair.reply.paired.json")) }
        assertEquals(PairResult.ProofMismatch, client(FakeTransport { c }).pair(qr))
        assertNothingLeft(c)
        // malformed proof
        val d = honestPc { push(withId("pair.reply.paired.json").replace(v.serverProof, "AAAA")) }
        assertEquals(PairResult.ProofMismatch, client(FakeTransport { d }).pair(qr))
        assertNothingLeft(d)
    }

    @Test
    fun pairedForAnotherPcIdIsRefused() = runTest {
        val pc = honestPc { push(paired(it, pcId = v.deviceId)) }
        assertEquals(PairResult.ProofMismatch, client(FakeTransport { pc }).pair(qr))
        assertNothingLeft(pc)
    }

    // ------------------------------------------------------------------ hello

    @Test
    fun oldPcGetsNothingSent_QA17() = runTest {
        val pc = FakeConnection(qr.pin).apply { push(v.message("hello.server.old.json")) }
        val result = client(FakeTransport { pc }).pair(qr)
        assertEquals(PairResult.PcTooOld, result)
        assertEquals("Update GrabLine on your PC", result.userMessage)
        assertTrue(pc.sent.isEmpty())
        assertEquals(0, keys.created)
        assertEquals(1000, pc.closedWith)
    }

    @Test
    fun pcNeedingANewerPhoneGetsNothingSent() = runTest {
        val pc = FakeConnection(qr.pin).apply { push(hello.replace("\"minClientVersion\":6", "\"minClientVersion\":7")) }
        assertEquals(PairResult.PhoneTooOld, client(FakeTransport { pc }).pair(qr))
        assertTrue(pc.sent.isEmpty())
        assertEquals(0, keys.created)
    }

    @Test
    fun malformedHelloFailsClosed() = runTest {
        val wrongPc = hello.replace(v.pcId, v.deviceId) // not the PC the QR named
        val badNonce = hello.replace("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", "AAEC")
        val notHello = v.message("pair.event.pending.json")
        for (first in listOf(wrongPc, badNonce, notHello, "[]")) {
            val pc = FakeConnection(qr.pin).apply { push(first) }
            assertEquals(first, PairResult.Rejected(ErrorCode.INTERNAL), client(FakeTransport { pc }).pair(qr))
            assertTrue(pc.sent.isEmpty())
            assertEquals(0, keys.created)
            assertNotNull(pc.closedWith)
        }
    }

    @Test
    fun aConnectionWhoseKeyIsNotThePinIsRefusedEvenIfTheTransportLetItThrough() = runTest {
        val pc = FakeConnection(TestCerts.selfSigned().pin).apply { push(hello) }
        assertEquals(PairResult.IdentityMismatch, client(FakeTransport { pc }).pair(qr))
        assertTrue(pc.sent.isEmpty())
        assertEquals(0, keys.created)
    }

    // ------------------------------------------------------------------ addresses

    @Test
    fun lastGoodAddressIsTriedFirstThenTheOthers() = runTest {
        val pc = honestPc { push(paired(it)) }
        val transport = FakeTransport { if (it == "192.168.1.20") pc else throw IOException("unreachable") }
        val result = client(transport).pair(qr, lastGoodAddress = "10.8.0.3")
        assertEquals(listOf("10.8.0.3", "192.168.1.20"), transport.attempts.map { it.first })
        assertEquals("192.168.1.20", (result as PairResult.Paired).address)
    }

    @Test
    fun nothingAnsweringIsPcNotFoundOrCodeExpiredByTheClock() = runTest {
        val transport = FakeTransport { throw IOException("unreachable") }
        val result = client(transport).pair(qr)
        assertEquals(PairResult.PcNotFound, result)
        assertEquals("PC not found", result.userMessage)
        assertEquals(qr.addresses, transport.attempts.map { it.first })
        now = qr.expiry
        assertEquals(PairResult.CodeExpired, client(transport).pair(qr))
        assertEquals(0, keys.created)
    }

    @Test
    fun aWrongKeyAtEveryAddressIsAnIdentityMismatch_QA07() = runTest {
        val result = client(FakeTransport { throw PinMismatchException() }).pair(qr)
        assertEquals(PairResult.IdentityMismatch, result)
        assertEquals("Your PC's identity changed. If you reinstalled GrabLine, pair again", result.userMessage)
        assertEquals(0, keys.created)
    }

    // ------------------------------------------------------------------ timeouts, cancellation, key failure

    @Test
    fun noAnswerToPairTimesOutAfter90Seconds() = runTest {
        val pc = honestPc { push(v.message("pair.event.pending.json")) }
        val result = client(FakeTransport { pc }).pair(qr)
        assertEquals(PairResult.TimedOut, result)
        assertEquals(90_000, currentTime)
        assertNothingLeft(pc)
    }

    @Test
    fun noHelloTimesOut() = runTest {
        val pc = FakeConnection(qr.pin)
        assertEquals(PairResult.TimedOut, client(FakeTransport { pc }).pair(qr))
        assertEquals(10_000, currentTime)
        assertEquals(0, keys.created)
        assertNotNull(pc.closedWith)
    }

    @Test
    fun cancellingWhileWaitingForAllowDeletesTheKey() = runTest {
        val pc = honestPc { push(v.message("pair.event.pending.json")) }
        val job = async { client(FakeTransport { pc }).pair(qr) { progress += it } }
        testScheduler.runCurrent()
        assertTrue(progress.last() is PairingProgress.ConfirmOnPc)
        assertEquals(1, keys.aliases.size)
        job.cancelAndJoin()
        assertNothingLeft(pc)
    }

    @Test
    fun droppedConnectionDeletesTheKey() = runTest {
        val pc = honestPc { incoming.trySend(Frame.Failed(IOException("reset"))) }
        assertEquals(PairResult.ConnectionLost, client(FakeTransport { pc }).pair(qr))
        assertNothingLeft(pc)
    }

    @Test
    fun keyCreationFailureSendsNothing() = runTest {
        keys.failCreate = true
        val pc = FakeConnection(qr.pin).apply { push(hello) }
        assertEquals(PairResult.KeyUnavailable, client(FakeTransport { pc }).pair(qr))
        assertTrue(pc.sent.isEmpty())
        assertNotNull(pc.closedWith)
    }

    // ------------------------------------------------------------------ manual entry

    private val manual: ManualPairingData = (ManualPairing.parse(v.text("qr.manual.txt")) as ManualPairingResult.Valid).data

    @Test
    fun manualEntryUsesTheKeyItSawAsThePinOnlyAfterTheProofVerifies() = runTest {
        val seen = TestCerts.selfSigned().pin
        val pc = honestPc(pin = seen) { push(paired(it, pin = seen)) }
        val transport = FakeTransport { pc }
        val result = client(transport).pair(manual) as PairResult.Paired
        assertEquals(TrustMode.ManualPairing, transport.attempts.single().second)
        assertEquals("192.168.1.20", transport.attempts.single().first)
        assertEquals(seen, result.pin)
        assertEquals(listOf("192.168.1.20"), result.addresses)
        assertEquals(47821, result.port)
        assertEquals(v.pcId, result.pcId)
        // codeProof was bound to the key that was seen
        val request = sentPair(pc.sent.single())
        val spki = Base64Url.decode(request.publicKey)!!
        assertEquals(Base64Url.encode(PairingProofs.codeProof(v.code, v.nonce, Pin.bytes(seen)!!, spki)), request.codeProof)
    }

    @Test
    fun manualEntryThroughAMachineInTheMiddleStoresNothing() = runTest {
        // The phone sees the relay's key; the real PC made its proof with its own key.
        val relayPin = TestCerts.selfSigned().pin
        val realPcPin = TestCerts.selfSigned().pin
        val pc = honestPc(pin = relayPin) { push(paired(it, pin = realPcPin)) }
        assertEquals(PairResult.ProofMismatch, client(FakeTransport { pc }).pair(manual))
        assertNothingLeft(pc)
    }

    // ------------------------------------------------------------------ secrets stay out of text

    @Test
    fun framesAndResultsDoNotPrintSecrets() = runTest {
        val pc = honestPc { push(paired(it)) }
        val result = client(FakeTransport { pc }).pair(qr)
        val sent = pc.sent.single()
        val proof = sentPair(sent).codeProof
        val printed = "$result ${Frame.Text(sent)} ${sentPair(sent)}"
        assertFalse(printed.contains(proof))
        assertFalse(printed.contains(v.code))
        advanceUntilIdle()
    }
}
