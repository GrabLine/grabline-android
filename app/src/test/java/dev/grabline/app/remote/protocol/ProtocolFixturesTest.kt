package dev.grabline.app.remote.protocol

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.reflect.KClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Runs the shared fixtures in src/test/resources/protocol/fixtures (the same files the desktop tests load). */
class ProtocolFixturesTest {
    private val json = Json
    private val root: File = File(
        checkNotNull(javaClass.classLoader.getResource("protocol/fixtures/manifest.json")) { "manifest.json not on the test classpath" }.toURI(),
    ).parentFile

    private class Entry(val file: String, val direction: String, val type: String?, val expectError: String?)

    private val entries: List<Entry> by lazy {
        json.parseToJsonElement(File(root, "manifest.json").readText()).jsonObject["fixtures"]!!.jsonArray.map {
            val o = it.jsonObject
            Entry(
                o["file"]!!.jsonPrimitive.content,
                o["direction"]!!.jsonPrimitive.content,
                o["type"]?.jsonPrimitive?.contentOrNull,
                o["expectError"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    private fun fixture(file: String): JsonObject = json.parseToJsonElement(File(root, file).readText()).jsonObject
    private fun messageText(file: String): String = fixture(file)["message"]!!.toString()

    @Test
    fun manifestAndFolderAgree() {
        val onDisk = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path.replace('\\', '/') }
            .filter { it != "README.md" && it != "manifest.json" }.toSet()
        val listed = entries.map { it.file }.toSet()
        assertEquals("files on disk but not in the manifest", emptySet<String>(), onDisk - listed)
        assertEquals("files in the manifest but not on disk", emptySet<String>(), listed - onDisk)
    }

    // ------------------------------------------------------------- phone to PC, valid

    @Test
    fun everyValidRequestRoundTripsSemantically() {
        val requests = entries.filter { it.direction == "phone-to-pc" && !it.file.startsWith("invalid/") }
        assertTrue(requests.size > 30)
        for (e in requests) {
            val f = fixture(e.file)
            val result = ProtocolCodec.decodeRequest(f["message"]!!.toString())
            val accepted = result as? RequestResult.Accepted ?: fail("${e.file}: rejected as $result").let { return }
            assertEquals("${e.file}: type", e.type, accepted.request.type)
            val ignored = f["expectIgnored"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
            val expected = JsonObject(f["message"]!!.jsonObject.filterKeys { it !in ignored })
            val encoded = ProtocolCodec.parseTree(ProtocolCodec.encode(accepted.request))
            assertEquals("${e.file}: encode(decode(message))", expected, encoded)
        }
    }

    // ------------------------------------------------------------- PC to phone

    private val replyTypes: Map<String, KClass<out ReplyMessage>> = mapOf(
        "analysis" to AnalysisReply::class, "authOk" to AuthOkReply::class, "ok" to OkReply::class,
        "queued" to QueuedReply::class, "error" to ErrorReply::class, "fetchStart" to FetchStartReply::class,
        "fileHash" to FileHashReply::class, "files" to FilesReply::class, "global" to GlobalReply::class,
        "jobs" to JobsReply::class, "outbox" to OutboxReply::class, "paired" to PairedReply::class,
        "pong" to PongReply::class, "queues" to QueuesReply::class, "recent" to RecentReply::class,
        "status" to StatusReply::class, "subscribed" to SubscribedReply::class,
    )

    private val eventClasses: Map<String, KClass<out ServerEvent>> = mapOf(
        "job.added" to JobAddedEvent::class, "job.updated" to JobUpdatedEvent::class,
        "job.progress" to JobProgressEvent::class, "job.finished" to JobFinishedEvent::class,
        "job.failed" to JobFailedEvent::class, "job.removed" to JobRemovedEvent::class,
        "global.updated" to GlobalUpdatedEvent::class, "outbox.added" to OutboxAddedEvent::class,
        "phone.command" to PhoneCommandEvent::class,
    )

    @Test
    fun everyServerMessageDecodesToTheRightModel() {
        val messages = entries.filter { it.direction == "pc-to-phone" }
        assertTrue(messages.size > 50)
        for (e in messages) {
            val text = messageText(e.file)
            val decoded = ProtocolCodec.decode(text)
            when (e.type) {
                "hello" -> assertTrue("${e.file}: $decoded", decoded is ServerHello)
                "pairPending" -> assertEvent(e, decoded, PairPendingEvent::class)
                "fetchEnd" -> assertEvent(e, decoded, FetchEndEvent::class)
                "event" -> {
                    val name = fixture(e.file)["message"]!!.jsonObject["event"]!!.jsonPrimitive.content
                    assertEvent(e, decoded, eventClasses.getValue(name))
                }
                else -> {
                    val reply = decoded as? Inbound.Reply ?: fail("${e.file}: not a reply: $decoded").let { return }
                    assertEquals("${e.file}: reply type", e.type, reply.type)
                    assertEquals("${e.file}: model", replyTypes.getValue(e.type!!), reply.message::class)
                }
            }
            assertTrue("${e.file}: never Malformed/TooLarge", decoded !is Inbound.Malformed && decoded !is Inbound.TooLarge)
        }
    }

    private fun assertEvent(e: Entry, decoded: Inbound, cls: KClass<out ServerEvent>) {
        val event = decoded as? Inbound.Event ?: fail("${e.file}: not an event: $decoded").let { return }
        assertEquals("${e.file}: model", cls, event.message::class)
    }

    @Test
    fun oldPcFixturesAreTooOldAndCurrentOnesAreSupported() {
        // QA-17 basis.
        assertEquals(VersionVerdict.PC_TOO_OLD, VersionCheck.evaluate(ProtocolCodec.decode(messageText("hello.server.old.json"))))
        assertEquals(VersionVerdict.PC_TOO_OLD, VersionCheck.evaluate(ProtocolCodec.decode(messageText("pong.v5.json"))))
        for (e in entries.filter { it.direction == "pc-to-phone" && it.file != "hello.server.old.json" && it.file != "pong.v5.json" }) {
            assertEquals(e.file, VersionVerdict.SUPPORTED, VersionCheck.evaluate(ProtocolCodec.decode(messageText(e.file))))
        }
        val old = ProtocolCodec.decode(messageText("pong.v5.json")) as Inbound.Reply
        assertEquals(PongReply(appRunning = true), old.message)
    }

    // ------------------------------------------------------------- invalid requests

    /** Fixtures whose error a phone-side parser can see from the message alone: the parser must give the manifest's error code. */
    private val detectable = setOf(
        "invalid/control.pathAsJobId.json", "invalid/control.unknownAction.json",
        "invalid/download.badQuality.json", "invalid/download.boolQueueId.json", "invalid/download.fileScheme.json",
        "invalid/download.filenameField.json", "invalid/download.folderAbsolute.json", "invalid/download.folderTraversal.json",
        "invalid/download.missingRequestId.json", "invalid/download.pathField.json", "invalid/download.wrongTypes.json",
        "invalid/fetch.negativeOffset.json", "invalid/fetch.path.json", "invalid/gallery.json",
        "invalid/global.setSpeedLimit.missingValue.json", "invalid/links.tooMany.marker.json", "invalid/missingId.json",
        "invalid/missingType.json", "invalid/notJson.marker.json", "invalid/oversize.marker.json",
        "invalid/pair.badPublicKey.json", "invalid/unknownType.json",
    )

    /**
     * PC-only rules: the message is well formed and only the PC's state decides (unknown ids, session state, device
     * list, rate limit, pre-auth state). The phone-side parser must accept these; the desktop tests own the outcome.
     */
    private val pcOnly = setOf(
        "invalid/auth.badSignature.json", "invalid/auth.oldClient.json", "invalid/auth.unknownDevice.json",
        "invalid/control.conflict.json", "invalid/control.unknownJob.json", "invalid/download.unknownFolder.json",
        "invalid/download.unknownQueue.json", "invalid/fetch.unfinishedJob.json", "invalid/flood.marker.json",
        "invalid/pair.expired.json", "invalid/pair.wrongCode.json", "invalid/preauth.download.json",
        "invalid/preauth.jobs.json",
    )

    @Test
    fun everyInvalidFixtureIsClassified() {
        val invalid = entries.filter { it.file.startsWith("invalid/") }.map { it.file }.toSet()
        assertEquals("unclassified invalid fixtures", emptySet<String>(), invalid - detectable - pcOnly)
        assertEquals("stale classification", emptySet<String>(), (detectable + pcOnly) - invalid)
    }

    @Test
    fun detectableInvalidRequestsAreRejectedWithTheManifestError() {
        for (e in entries.filter { it.file in detectable }) {
            val text = buildFrame(e.file)
            val result = ProtocolCodec.decodeRequest(text)
            val rejected = result as? RequestResult.Rejected ?: fail("${e.file}: accepted $result").let { return }
            assertEquals("${e.file}: code", e.expectError, rejected.code.wire)
        }
    }

    @Test
    fun pcOnlyInvalidRequestsAreWellFormedForThePhoneParser() {
        for (e in entries.filter { it.file in pcOnly }) {
            val result = ProtocolCodec.decodeRequest(messageText(e.file))
            assertTrue("${e.file}: $result", result is RequestResult.Accepted)
        }
    }

    /** Builds the frame for a fixture, expanding the marker files as the README describes. */
    private fun buildFrame(file: String): String {
        val f = fixture(file)
        val gen = f["generate"]?.jsonObject
        val raw = f["raw"]?.jsonPrimitive?.contentOrNull
        return when {
            raw != null -> raw
            gen != null && "padField" in gen -> {
                val field = gen["padField"]!!.jsonPrimitive.content
                val pad = gen["padChar"]!!.jsonPrimitive.content
                val total = gen["totalBytes"]!!.jsonPrimitive.int
                fun build(n: Int): String {
                    val m = f["message"]!!.jsonObject.toMutableMap()
                    m[field] = JsonPrimitive(pad.repeat(n))
                    return JsonObject(m).toString()
                }
                val base = build(0).length
                build(total - base)
            }
            gen != null && "listField" in gen -> {
                val m = f["message"]!!.jsonObject.toMutableMap()
                val template = gen["template"]!!.jsonPrimitive.content
                m[gen["listField"]!!.jsonPrimitive.content] =
                    JsonArray((1..gen["count"]!!.jsonPrimitive.int).map { JsonPrimitive(template.replace("{n}", it.toString())) })
                JsonObject(m).toString()
            }
            else -> f["message"]!!.toString()
        }
    }

    @Test
    fun oversizeMarkerFrameIsReallyOverTheLimit() {
        val text = buildFrame("invalid/oversize.marker.json")
        assertEquals(2_097_152L, ProtocolCodec.utf8Size(text))
        assertTrue(ProtocolCodec.decode(text) is Inbound.TooLarge)
    }

    // ------------------------------------------------------------- text fixtures

    @Test
    fun qrUriFixtureParsesAndMatchesTheVectors() {
        val vectors = fixture("vectors.json")
        val result = PairingUri.parse(File(root, "qr.uri.txt").readText())
        val data = (result as? PairingUriResult.Valid ?: fail("qr.uri.txt: $result").let { return }).data
        assertEquals(listOf("192.168.1.20", "10.8.0.3", "[fd7a:115c:a1e0::20]"), data.addresses)
        assertEquals(47821, data.port)
        assertEquals(vectors["pinnedFingerprint"]!!.jsonPrimitive.content, data.pin)
        assertEquals(vectors["pairingCode"]!!.jsonPrimitive.content, data.code)
        assertEquals(vectors["pcId"]!!.jsonPrimitive.content, data.pcId)
        assertEquals("Study PC", data.pcName)
        assertEquals(1794700120L, data.expiry)
        assertEquals(32, data.pinBytes().size)
        assertEquals(vectors["qrUri"]!!.jsonPrimitive.content, File(root, "qr.uri.txt").readText().trim())
    }

    @Test
    fun manualFixtureParsesAndMatchesTheVectors() {
        val vectors = fixture("vectors.json")
        val result = ManualPairing.parse(File(root, "qr.manual.txt").readText())
        val data = (result as? ManualPairingResult.Valid ?: fail("qr.manual.txt: $result").let { return }).data
        assertEquals("192.168.1.20", data.address)
        assertEquals(47821, data.port)
        assertEquals(vectors["pairingCode"]!!.jsonPrimitive.content, data.code)
    }

    @Test
    fun fetchFrameFixtureHasTheDocumentedLayout() {
        val bytes = File(root, "fetch.frame.hex.txt").readText().trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals("GLF1", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(7, java.nio.ByteBuffer.wrap(bytes, 4, 4).int)
        assertEquals(0L, java.nio.ByteBuffer.wrap(bytes, 8, 8).long)
        assertEquals("hello, file bytes", String(bytes, 16, bytes.size - 16, Charsets.US_ASCII))
    }
}
