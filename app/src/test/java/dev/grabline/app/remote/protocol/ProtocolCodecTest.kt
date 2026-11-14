package dev.grabline.app.remote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProtocolCodecTest {
    private fun job(extra: String = "") =
        """{"id":1,"url":"https://e.com/a","name":"a","status":"queued"$extra}"""

    @Test
    fun unknownFieldsAreIgnored() {
        val r = ProtocolCodec.decode("""{"id":"r-1","type":"ok","protocolVersion":6,"futureThing":{"x":[1,2]}}""")
        assertTrue(r is Inbound.Reply)
    }

    @Test
    fun unknownEventNameIsSurfacedNotFatal() {
        val r = ProtocolCodec.decode("""{"type":"event","protocolVersion":6,"event":"job.exploded","seq":1,"ts":2}""")
        assertEquals(UnknownEvent("job.exploded"), (r as Inbound.Event).message)
    }

    @Test
    fun unknownReplyTypeIsSurfacedNotFatal() {
        val r = ProtocolCodec.decode("""{"id":"r-1","type":"teleport","protocolVersion":6}""")
        assertEquals(UnknownReply("teleport"), (r as Inbound.Reply).message)
    }

    @Test
    fun wrongTypesInKnownFieldsAreMalformed() {
        val bad = listOf(
            """{"id":"r-1","type":"jobs","protocolVersion":6,"jobs":[${job()}],"total":"3"}""", // number as string
            """{"id":"r-1","type":"jobs","protocolVersion":6,"jobs":[${job(""","downloaded":true""")}]}""", // bool as int
            """{"id":"r-1","type":"jobs","protocolVersion":6,"jobs":[${job(""","downloaded":1.5""")}]}""", // fraction
            """{"id":"r-1","type":"jobs","protocolVersion":6,"jobs":[${job(""","fetchable":"yes"""")}]}""",
            """{"id":"r-1","type":"jobs","protocolVersion":6,"jobs":{"id":1}}""",
            """{"id":"r-1","type":"jobs","protocolVersion":"6","jobs":[]}""",
            """{"id":5,"type":"ok","protocolVersion":6}""",
            """{"id":"r-1","type":6}""",
            """{"id":"r-1","type":"error","protocolVersion":6,"code":7}""",
            """{"id":"r-1","type":"error","protocolVersion":6}""", // code missing
            """{"type":"event","protocolVersion":6,"event":"job.added","seq":1,"ts":1,"job":{"id":1}}""",
            """{"type":"event","protocolVersion":6,"event":7,"seq":1}""",
            """{"type":"hello","protocolVersion":6,"nonce":12}""",
            """["not","an","object"]""",
            """"string"""",
            """{"type":"ping", """,
            "",
        )
        for (text in bad) {
            val r = ProtocolCodec.decode(text)
            assertTrue("should be Malformed: $text -> $r", r is Inbound.Malformed)
        }
    }

    @Test
    fun missingTypeIsMalformed() {
        assertTrue(ProtocolCodec.decode("""{"id":"r-1"}""") is Inbound.Malformed)
    }

    @Test
    fun deeplyNestedJsonDoesNotCrash() {
        val text = "[".repeat(20_000) + "]".repeat(20_000)
        assertTrue(ProtocolCodec.decode(text) is Inbound.Malformed)
    }

    @Test
    fun oversizeFrameIsTooLargeInBothDirections() {
        // Exactly at the limit is allowed on decode; one byte more is not.
        val prefix = """{"id":"r-1","type":"ok","protocolVersion":6,"pad":""""
        val suffix = "\"}"
        val padLen = MAX_FRAME_BYTES - ProtocolCodec.utf8Size(prefix + suffix).toInt()
        val atLimit = prefix + "a".repeat(padLen) + suffix
        assertEquals(MAX_FRAME_BYTES.toLong(), ProtocolCodec.utf8Size(atLimit))
        assertTrue(ProtocolCodec.decode(atLimit) is Inbound.Reply)
        assertEquals(Inbound.TooLarge(MAX_FRAME_BYTES + 1L), ProtocolCodec.decode(atLimit + " "))

        // Multi-byte characters count as bytes, not characters.
        val wide = prefix + "é".repeat(padLen / 2 + 1) + suffix // 2 bytes each
        assertTrue(wide.length < MAX_FRAME_BYTES)
        assertTrue(ProtocolCodec.decode(wide) is Inbound.TooLarge)

        // Encode refuses a request that would not fit.
        val request = DownloadRequest("r-1", "https://e.com/a", "req-0000-aaaaaaaaaaaa", title = null) // fits
        ProtocolCodec.encode(request)
        val big = LinksRequest("r-1", (1..300).map { "https://e.com/" + "x".repeat(8000) + it }, "req-0000-aaaaaaaaaaaa")
        try {
            ProtocolCodec.encode(big)
            fail("expected FrameTooLargeException")
        } catch (e: FrameTooLargeException) {
            assertTrue(e.sizeBytes > MAX_FRAME_BYTES)
        }
    }

    @Test
    fun encodeOmitsNullsAndAddsIdAndType() {
        val text = ProtocolCodec.encode(DownloadRequest("r-5", "https://e.com/a", "req-0001-7b1e4c1a9f3d4e55"))
        assertEquals(
            ProtocolCodec.parseTree("""{"id":"r-5","type":"download","url":"https://e.com/a","requestId":"req-0001-7b1e4c1a9f3d4e55"}"""),
            ProtocolCodec.parseTree(text),
        )
        assertEquals(ProtocolCodec.parseTree("""{"type":"fetchAck","streamId":7,"received":2097152}"""), ProtocolCodec.parseTree(ProtocolCodec.encode(FetchAck(7, 2_097_152))))
    }

    @Test
    fun encodeRefusesRequestsThatBreakTheRules() {
        val broken = listOf<Request>(
            DownloadRequest("r-1", "file:///etc/passwd", "req-0000-aaaaaaaaaaaa"),
            DownloadRequest("r-1", "https://e.com/a", "short"),
            DownloadRequest("r-1", "https://e.com/a", "req-0000-aaaaaaaaaaaa", quality = "4320p"),
            DownloadRequest("r-1", "https://e.com/a", "req-0000-aaaaaaaaaaaa", folderId = "../x"),
            DownloadRequest("bad id!", "https://e.com/a", "req-0000-aaaaaaaaaaaa"),
            ControlRequest("r-1", 0, "pause"),
            ControlRequest("r-1", 1, "deleteFile"),
            GlobalRequest("r-1", "setSpeedLimit"),
            GlobalRequest("r-1", "setSpeedLimit", 10),
            RecentRequest("r-1", 21),
            AnalyzeRequest("r-1", "magnet:?xt=urn:btih:abc"),
            FetchRequest("r-1", 40, offset = -1),
            LinksRequest("r-1", emptyList(), "req-0000-aaaaaaaaaaaa"),
        )
        for (r in broken) {
            try {
                ProtocolCodec.encode(r)
                fail("should refuse $r")
            } catch (_: InvalidRequestException) {
            }
        }
    }

    @Test
    fun linksOver300IsTooLarge() {
        val r = LinksRequest("r-1", (1..301).map { "https://e.com/$it" }, "req-0000-aaaaaaaaaaaa")
        assertEquals(ErrorCode.TOO_LARGE, ProtocolCodec.validate(r)!!.code)
    }

    @Test
    fun urlRule() {
        assertTrue(ProtocolCodec.isValidUrl("https://user:pw@example.com:8443/a?b=c"))
        assertTrue(ProtocolCodec.isValidUrl("http://[fd7a::1]/x"))
        assertTrue(ProtocolCodec.isValidUrl("magnet:?xt=urn:btih:0123&dn=x"))
        assertFalse(ProtocolCodec.isValidUrl("magnet:?dn=x"))
        assertFalse(ProtocolCodec.isValidUrl("ftp://example.com/a"))
        assertFalse(ProtocolCodec.isValidUrl("https:///nohost"))
        assertFalse(ProtocolCodec.isValidUrl("https://exa mple.com"))
        assertFalse(ProtocolCodec.isValidUrl("https://example.com/" + "a".repeat(8200)))
    }

    @Test
    fun forbiddenFieldsAreRefusedBeforeOtherChecks() {
        // Not even a valid id or type is needed: the check runs first.
        val r = ProtocolCodec.decodeRequest("""{"saveAs":"x"}""") as RequestResult.Rejected
        assertEquals(ErrorCode.FORBIDDEN, r.code)
        for (name in ProtocolCodec.forbiddenFields) {
            val rr = ProtocolCodec.decodeRequest("""{"id":"r-1","type":"ping","$name":"x"}""") as RequestResult.Rejected
            assertEquals(name, ErrorCode.FORBIDDEN, rr.code)
        }
    }

    @Test
    fun serverHelloToleratesMissingFields() {
        val r = ProtocolCodec.decode("""{"type":"hello"}""") as ServerHello
        assertNull(r.protocolVersion)
        assertNull(r.nonceBytes())
        assertEquals(VersionVerdict.PC_TOO_OLD, VersionCheck.evaluate(r))
    }

    @Test
    fun helloNonceDecodesTo32Bytes() {
        val r = ProtocolCodec.decode("""{"type":"hello","protocolVersion":6,"minClientVersion":6,"pcId":"A3D7Za2aw26jbGofVV3ZAQ","pcName":"Study PC","nonce":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"}""") as ServerHello
        val nonce = r.nonceBytes()!!
        assertEquals(32, nonce.size)
        assertEquals(31, nonce[31].toInt())
    }

    // ------------------------------------------------------------- versions

    @Test
    fun versionRule() {
        assertEquals(VersionVerdict.SUPPORTED, VersionCheck.evaluate(6, 6))
        assertEquals(VersionVerdict.SUPPORTED, VersionCheck.evaluate(7)) // newer PC carries on
        assertEquals(VersionVerdict.SUPPORTED, VersionCheck.evaluate(6, null)) // minClientVersion absent
        assertEquals(VersionVerdict.PC_TOO_OLD, VersionCheck.evaluate(5))
        assertEquals(VersionVerdict.PC_TOO_OLD, VersionCheck.evaluate(null))
        assertEquals(VersionVerdict.PHONE_TOO_OLD, VersionCheck.evaluate(7, 7))
        assertEquals(VersionVerdict.PC_TOO_OLD, VersionCheck.evaluate(5, 9)) // phone sends nothing either way
        assertEquals("Update GrabLine on your PC", VersionCheck.message(VersionVerdict.PC_TOO_OLD))
        assertEquals("Update GrabLine on this phone", VersionCheck.message(VersionVerdict.PHONE_TOO_OLD))
        assertEquals(6, PROTOCOL_VERSION)
    }

    // ------------------------------------------------------------- errors and close codes

    @Test
    fun everyErrorCodeHasAMessageAndRoundTripsItsWireName() {
        assertEquals(15, ErrorCode.entries.size)
        for (code in ErrorCode.entries) {
            assertTrue(ErrorMessages.forCode(code).isNotBlank())
            assertEquals(code, ErrorCode.fromWire(code.wire))
        }
        assertEquals(ErrorCode.INTERNAL, ErrorCode.fromWire("somethingNew"))
        assertEquals(ErrorCode.INTERNAL, ErrorCode.fromWire(null))
    }

    @Test
    fun messagesAreThePlainWordsFromTheSpec() {
        assertEquals("PC declined", ErrorMessages.forCode(ErrorCode.PAIRING_DENIED))
        assertEquals("Your PC doesn't allow that", ErrorMessages.forCode(ErrorCode.FORBIDDEN))
        assertEquals("Your PC hit a problem. Try again", ErrorMessages.forCode(ErrorCode.INTERNAL))
        assertEquals("This is a web page, not a file", ErrorMessages.forReply(ErrorCode.FAILED, "noMedia"))
        assertEquals("Netflix protects its media with DRM. GrabLine can't download it", ErrorMessages.forReply(ErrorCode.FAILED, "drm", "Netflix"))
        assertEquals("Your PC couldn't do that. Try again", ErrorMessages.forReply(ErrorCode.FAILED, "weird"))
        assertEquals("Your PC's identity changed. If you reinstalled GrabLine, pair again", ErrorMessages.IDENTITY_CHANGED)
    }

    @Test
    fun unknownErrorCodeDecodesAndMapsToInternal() {
        val r = ProtocolCodec.decode("""{"id":"r-1","type":"error","protocolVersion":6,"code":"newCode","message":"x"}""") as Inbound.Reply
        assertEquals(ErrorCode.INTERNAL, (r.message as ErrorReply).errorCode)
    }

    @Test
    fun errorReplyWithoutIdDecodes() {
        val r = ProtocolCodec.decode("""{"type":"error","protocolVersion":6,"code":"badRequest","message":"bad"}""") as Inbound.Reply
        assertNull(r.id)
    }

    @Test
    fun closeCodes() {
        assertEquals(4401, CloseCodes.AUTH_FAILED)
        assertEquals(4403, CloseCodes.REMOVED_OR_DENIED)
        assertEquals(4429, CloseCodes.FLOODING)
        assertEquals(CloseAction.PAIRING_REMOVED, CloseCodes.actionFor(4401))
        assertEquals(CloseAction.UPDATE_PHONE, CloseCodes.actionFor(4426))
        assertEquals(CloseAction.RECONNECT_AFTER_30_SECONDS, CloseCodes.actionFor(4429))
        assertEquals(CloseAction.REPORT_BUG, CloseCodes.actionFor(1009))
        assertEquals(CloseAction.NONE, CloseCodes.actionFor(4409))
        assertEquals(CloseAction.RECONNECT_WITH_BACKOFF, CloseCodes.actionFor(1006))
    }

    @Test
    fun timeouts() {
        assertEquals(15_000L, Timeouts.forType("ping"))
        assertEquals(45_000L, Timeouts.forType("analyze"))
        assertEquals(45_000L, Timeouts.forType("fileHash"))
        assertEquals(90_000L, Timeouts.forType("pair"))
    }

    @Test
    fun jobStatusUnknownIsNotAnAction() {
        val r = ProtocolCodec.decode("""{"id":"r-1","type":"ok","protocolVersion":6,"job":${job().replace("queued", "levitating")}}""") as Inbound.Reply
        assertEquals(JobStatus.UNKNOWN, (r.message as OkReply).job!!.jobStatus)
    }

    @Test
    fun secretsAreKeptOutOfToString() {
        val pair = PairRequest("r-1", deviceName = "Pixel", publicKey = "PUBKEY-SENTINEL", codeProof = "PROOF-SENTINEL")
        assertFalse(pair.toString().contains("PROOF-SENTINEL"))
        assertFalse(pair.toString().contains("PUBKEY-SENTINEL"))
        val auth = AuthRequest("r-2", deviceId = "KsucBZHeyJguN6H0BzJQiw", signature = "SIG-SENTINEL")
        assertFalse(auth.toString().contains("SIG-SENTINEL"))
        val paired = PairedReply("d", "p", "n", "SERVERPROOF-SENTINEL")
        assertFalse(paired.toString().contains("SERVERPROOF-SENTINEL"))
    }
}
