package dev.grabline.app.remote.connection

import dev.grabline.app.data.PairedPc
import dev.grabline.app.remote.protocol.AuthRequest
import dev.grabline.app.remote.protocol.ControlRequest
import dev.grabline.app.remote.protocol.ErrorCode
import dev.grabline.app.remote.protocol.JobsReply
import dev.grabline.app.remote.protocol.JobsRequest
import dev.grabline.app.remote.protocol.PingRequest
import dev.grabline.app.remote.protocol.PongReply
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PcSessionTest {
    private val pc = PairedPc(
        pcId = "pcA", name = "Desk", fingerprint = "ab", deviceId = "KsucBZHeyJguN6H0BzJQiw",
        addresses = listOf("10.0.0.1"), port = 47821, protocolVersion = 6, pairedAt = 1L,
    )
    private val auth = AuthProvider { p, _, id ->
        AuthRequest(id, deviceId = p.deviceId, signature = "MEUCIQDEkNVli0ImyofhN_Wu6-4n3CBTJwbf9PLA_GpHelIhsAIgGE-UGdJQPUyd2T_zsX1zDXFD6yn-mSsGqKBL67H4TCM")
    }
    private val fake = FakePc()

    private fun session() = PcSession(pc, "10.0.0.1", fake, auth)

    private fun endOf(result: Result<*>) = (result.exceptionOrNull() as PcSession.EndException).end

    @Test fun helloThenAuthOpens() = runBlocking<Unit> {
        val s = session()
        val ok = s.open().getOrThrow()
        assertEquals("pcA", ok.pcId)
        assertEquals("1.4.0", ok.appVersion)
        assertEquals(listOf("auth"), fake.lastLive!!.requests().map { it.second })
        assertTrue(!s.isEnded)
        s.close()
    }

    @Test fun repliesAreCorrelatedOutOfOrder() = runBlocking<Unit> {
        fake.handlers["control"] = { _, _ -> null } // held; the test answers by hand
        val s = session()
        s.open().getOrThrow()
        val conn = fake.lastLive!!
        val a = async { s.request { ControlRequest(it, 1, "pause") } }
        val ctrlId = eventually(what = "control sent") { conn.requests().firstOrNull { it.second == "control" }?.first }
        val b = async { s.request { PingRequest(it) } }
        // ping (sent second) is answered first
        val pong = withTimeout(5_000) { b.await() }
        assertTrue(pong.message is PongReply)
        assertTrue(!a.isCompleted)
        conn.push("""{"id":"$ctrlId","type":"ok","protocolVersion":6}""")
        assertEquals(ctrlId, withTimeout(5_000) { a.await() }.id)
        s.close()
    }

    @Test fun twoPendingAnsweredInReverseOrder() = runBlocking<Unit> {
        fake.handlers["ping"] = { _, _ -> null }
        val s = session()
        s.open().getOrThrow()
        val conn = fake.lastLive!!
        val a = async { runCatching { s.request { PingRequest(it) } } }
        val b = async { runCatching { s.request { PingRequest(it) } } }
        val ids = eventually(what = "two pings") { conn.requests().filter { it.second == "ping" }.map { it.first }.takeIf { it.size == 2 } }
        conn.push(fake.error(ids[1], "notFound"))
        conn.push("""{"id":"${ids[0]}","type":"pong","protocolVersion":6,"appRunning":true}""")
        // whichever deferred owns ids[0] gets the pong, the other the error
        val results = listOf(a, b).map { d -> withTimeout(5_000) { d.await() } }
        assertEquals(1, results.count { it.isSuccess })
        val failure = results.single { it.isFailure }.exceptionOrNull() as RemoteException
        assertEquals(ErrorCode.NOT_FOUND, failure.code)
        assertTrue(results.single { it.isSuccess }.getOrThrow().message is PongReply)
        s.close()
    }

    @Test fun errorReplyBecomesRemoteException() = runBlocking<Unit> {
        fake.handlers["control"] = { id, _ -> listOf(fake.error(id, "conflict")) }
        val s = session()
        s.open().getOrThrow()
        try {
            s.request { ControlRequest(it, 1, "pause") }
            fail("expected RemoteException")
        } catch (e: RemoteException) {
            assertEquals(ErrorCode.CONFLICT, e.code)
            assertEquals("conflict", e.reply?.code)
        }
        s.close()
    }

    @Test fun pendingRequestsFailWhenSocketCloses() = runBlocking<Unit> {
        fake.handlers["ping"] = { _, _ -> null }
        val s = session()
        s.open().getOrThrow()
        val conn = fake.lastLive!!
        val a = async { runCatching { s.request { PingRequest(it) } } }
        eventually(what = "ping sent") { conn.requests().firstOrNull { it.second == "ping" } }
        conn.closeFromPc(1001)
        val failure = withTimeout(5_000) { a.await() }.exceptionOrNull() as RemoteException
        assertEquals(ErrorCode.INTERNAL, failure.code)
        assertEquals(SessionEnd.Closed(1001, 0), s.awaitEnd())
        try {
            s.request { PingRequest(it) }
            fail("expected RemoteException")
        } catch (e: RemoteException) {
            assertEquals(ErrorCode.INTERNAL, e.code)
        }
    }

    @Test fun v5HelloFixtureIsPcTooOld() = runBlocking<Unit> { // QA-17
        val text = javaClass.getResource("/protocol/fixtures/hello.server.old.json")!!.readText()
        fake.helloFrame = Json.parseToJsonElement(text).jsonObject["message"].toString()
        val s = session()
        assertEquals(SessionEnd.PcTooOld, endOf(s.open()))
        assertTrue("must not authenticate", fake.conns.single().requests().isEmpty())
    }

    @Test fun authUnauthorizedIsPairingRemoved() = runBlocking<Unit> {
        fake.authMode = FakePc.AuthMode.UNAUTHORIZED_REPLY
        assertEquals(SessionEnd.PairingRemoved, endOf(session().open()))
    }

    @Test fun close4401DuringAuthIsPairingRemoved() = runBlocking<Unit> {
        fake.authMode = FakePc.AuthMode.CLOSE_4401
        assertEquals(SessionEnd.PairingRemoved, endOf(session().open()))
    }

    @Test fun close4403AfterAuthIsPairingRemoved() = runBlocking<Unit> { // QA-06
        val s = session()
        s.open().getOrThrow()
        fake.lastLive!!.closeFromPc(4403)
        assertEquals(SessionEnd.PairingRemoved, s.awaitEnd())
    }

    @Test fun pinMismatchIsIdentityChanged() = runBlocking<Unit> { // QA-07
        fake.defaultMode = FakePc.Mode.WrongKey
        assertEquals(SessionEnd.IdentityChanged, endOf(session().open()))
    }

    @Test fun unreachableFails() = runBlocking<Unit> {
        fake.defaultMode = FakePc.Mode.Unreachable(10)
        assertTrue(endOf(session().open()) is SessionEnd.Unreachable)
    }

    @Test fun floodCloseCarriesRetryDelay() = runBlocking<Unit> {
        val s = session()
        s.open().getOrThrow()
        fake.lastLive!!.closeFromPc(4429)
        assertEquals(SessionEnd.Closed(4429, 30_000L), s.awaitEnd())
    }

    @Test fun malformedFramesAreDropped() = runBlocking<Unit> {
        val s = session()
        s.open().getOrThrow()
        val conn = fake.lastLive!!
        conn.push("not json")
        conn.push("[1,2]")
        conn.push("""{"type":"event"}""")
        conn.push("""{"id":5,"type":"jobs"}""")
        conn.push("""{"id":"zz","type":"jobs","protocolVersion":6,"jobs":"nope"}""")
        fake.jobPages = listOf(listOf(jobJson(1)))
        val reply = withTimeout(5_000) { s.request { JobsRequest(it) } }
        assertEquals(1, (reply.message as JobsReply).jobs.size)
        assertTrue(!s.isEnded)
        s.close()
    }
}
