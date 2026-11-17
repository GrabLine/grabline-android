package dev.grabline.app.remote.protocol

import java.io.File
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Section 20 (feature `phoneJobs`): the models against the shared fixtures, and the rules the phone obeys before sending. */
class PhoneProtocolTest {
    private val root: File = File(
        checkNotNull(javaClass.classLoader.getResource("protocol/fixtures/manifest.json")).toURI(),
    ).parentFile

    private fun message(file: String): JsonObject = ProtocolCodec.parseTree(File(root, file).readText()).jsonObject["message"]!!.jsonObject

    private fun accepted(file: String): Request =
        (ProtocolCodec.decodeRequest(message(file).toString()) as RequestResult.Accepted).request

    private fun info(id: Long, name: String = "f$id") = PhoneJobInfo(id, name, "https://e.com/$id", "queued", "direct")

    @Test fun authOkFixtureListsTheFeature() {
        val reply = ProtocolCodec.decode(message("auth.reply.ok.json").toString()) as Inbound.Reply
        val authOk = reply.message as AuthOkReply
        assertTrue(authOk.supports(Feature.PHONE_JOBS))
        assertEquals("phoneJobs", Feature.PHONE_JOBS)
    }

    @Test fun phoneJobsFixtureDecodesToTheModel() {
        val request = accepted("phoneJobs.request.json") as PhoneJobsRequest
        assertTrue(request.shared)
        assertEquals(listOf(12L, 11L, 10L), request.jobs.map { it.id })
        val first = request.jobs[0]
        assertEquals("downloading", first.status)
        assertEquals("direct", first.kind)
        assertEquals(6_114_656_256L, first.total)
        assertEquals(1026L, first.eta)
        assertEquals(1794700100L, first.createdAt)
        val transfer = request.jobs[2]
        assertEquals("transfer", transfer.kind)
        assertEquals("", transfer.url)
        assertEquals("Your PC is off or on another network", transfer.error)
        assertEquals(null, transfer.eta)
    }

    @Test fun phoneJobsEncodingKeepsTheExplicitNullsOfTheFixture() {
        val request = accepted("phoneJobs.request.json")
        val encoded = ProtocolCodec.parseTree(ProtocolCodec.encode(request)).jsonObject
        assertEquals(message("phoneJobs.request.json"), encoded)
        val jobs = encoded["jobs"]!!.jsonArray
        assertEquals(JsonNull, jobs[1].jsonObject["eta"])
        assertEquals(JsonNull, jobs[0].jsonObject["error"])
    }

    @Test fun notSharedFixtureIsEmptyAndRoundTrips() {
        val request = accepted("phoneJobs.request.notShared.json") as PhoneJobsRequest
        assertFalse(request.shared)
        assertTrue(request.jobs.isEmpty())
        assertEquals(message("phoneJobs.request.notShared.json"), ProtocolCodec.parseTree(ProtocolCodec.encode(request)))
    }

    @Test fun commandResultFixturesRoundTrip() {
        val ok = accepted("phoneCommandResult.request.json") as PhoneCommandResultRequest
        assertEquals(3L, ok.commandId)
        assertEquals("ok", ok.result)
        assertEquals(null, ok.message)
        assertEquals(message("phoneCommandResult.request.json"), ProtocolCodec.parseTree(ProtocolCodec.encode(ok)))
        val refused = accepted("phoneCommandResult.request.refused.json") as PhoneCommandResultRequest
        assertEquals("refused", refused.result)
        assertEquals("This phone doesn't let PCs control its downloads", refused.message)
        assertEquals(message("phoneCommandResult.request.refused.json"), ProtocolCodec.parseTree(ProtocolCodec.encode(refused)))
    }

    @Test fun commandEventFixtureDecodes() {
        val decoded = ProtocolCodec.decode(message("event.phone.command.json").toString()) as Inbound.Event
        val event = decoded.message as PhoneCommandEvent
        assertEquals(PhoneCommandEvent(seq = 9, ts = 1794700120, commandId = 3, action = "pause", jobId = 12), event)
    }

    @Test fun commandEventWithTheWrongTypesIsMalformedNotACommand() {
        val text = message("event.phone.command.json").toString().replace("\"commandId\":3", "\"commandId\":\"3\"")
        assertTrue(ProtocolCodec.decode(text) is Inbound.Malformed)
        val missing = message("event.phone.command.json").toString().replace("\"jobId\":12", "\"x\":1")
        assertTrue(ProtocolCodec.decode(missing) is Inbound.Malformed)
    }

    @Test fun unknownActionStillDecodesSoThePhoneCanRefuseIt() {
        val text = message("event.phone.command.json").toString().replace("\"pause\"", "\"explode\"")
        val event = (ProtocolCodec.decode(text) as Inbound.Event).message as PhoneCommandEvent
        assertEquals("explode", event.action)
    }

    @Test fun repliesAreOkReplies() {
        for (file in listOf("phoneJobs.reply.json", "phoneCommandResult.reply.json")) {
            val reply = ProtocolCodec.decode(message(file).toString()) as Inbound.Reply
            assertEquals(file, "ok", reply.type)
            assertTrue(file, reply.message is OkReply)
        }
    }

    // ------------------------------------------------------------------ the phone's own checks before it sends

    @Test fun phoneJobsRulesAreEnforcedBeforeSending() {
        fun ok(r: PhoneJobsRequest) = assertEquals(null, ProtocolCodec.validate(r))
        fun bad(r: PhoneJobsRequest) = assertNotNull(ProtocolCodec.validate(r))
        ok(PhoneJobsRequest("r1", true, (1L..100L).map { info(it) }))
        bad(PhoneJobsRequest("r1", true, (1L..101L).map { info(it) }))
        bad(PhoneJobsRequest("r1", false, listOf(info(1)))) // not shared: jobs MUST be empty
        bad(PhoneJobsRequest("r1", true, listOf(info(1), info(1)))) // ids unique
        bad(PhoneJobsRequest("r1", true, listOf(info(0))))
        bad(PhoneJobsRequest("r1", true, listOf(info(1, name = ""))))
        ok(PhoneJobsRequest("r1", true, listOf(info(1, name = "a".repeat(256)))))
        bad(PhoneJobsRequest("r1", true, listOf(info(1, name = "a".repeat(257)))))
        bad(PhoneJobsRequest("r1", true, listOf(info(1).copy(url = "u".repeat(2049)))))
        bad(PhoneJobsRequest("r1", true, listOf(info(1).copy(status = "running"))))
        bad(PhoneJobsRequest("r1", true, listOf(info(1).copy(kind = "ftp"))))
        bad(PhoneJobsRequest("r1", true, listOf(info(1).copy(downloaded = -1))))
        bad(PhoneJobsRequest("r1", true, listOf(info(1).copy(error = "e".repeat(513)))))
    }

    @Test fun commandResultRulesAreEnforcedBeforeSending() {
        assertEquals(null, ProtocolCodec.validate(PhoneCommandResultRequest("r1", 2_147_483_647, "failed", "x".repeat(256))))
        assertNotNull(ProtocolCodec.validate(PhoneCommandResultRequest("r1", 0, "ok")))
        assertNotNull(ProtocolCodec.validate(PhoneCommandResultRequest("r1", 2_147_483_648, "ok")))
        assertNotNull(ProtocolCodec.validate(PhoneCommandResultRequest("r1", 1, "maybe")))
        assertNotNull(ProtocolCodec.validate(PhoneCommandResultRequest("r1", 1, "ok", "x".repeat(257))))
    }

    @Test fun aHundredJobsWithEveryFieldAtItsLimitFitTheFrameLimit() {
        val big = (1L..100L).map { info(it, "n".repeat(256)).copy(url = "u".repeat(2048), error = "e".repeat(512)) }
        val text = ProtocolCodec.encode(PhoneJobsRequest("r1", true, big))
        assertTrue(ProtocolCodec.utf8Size(text) < MAX_FRAME_BYTES)
        assertEquals(JsonPrimitive("phoneJobs"), ProtocolCodec.parseTree(text).jsonObject["type"])
    }
}
