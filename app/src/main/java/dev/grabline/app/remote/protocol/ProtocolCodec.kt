package dev.grabline.app.remote.protocol

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.JsonArray

/** `hello` (PC to phone, first frame). Every field is optional so an old PC never crashes the phone. */
@Serializable
data class ServerHello(
    val protocolVersion: Int? = null,
    val minClientVersion: Int? = null,
    val pcId: String? = null,
    val pcName: String? = null,
    val nonce: String? = null,
) : Inbound {
    /** The 32 raw nonce bytes, or null when absent or not 32 bytes of base64url. */
    fun nonceBytes(): ByteArray? = nonce?.let { Base64Url.decode(it) }?.takeIf { it.size == 32 }
}

/** What arrives from the PC. */
sealed interface Inbound {
    /** A reply to a request. [id] is null for an error reply to a request without a usable id, and for a v5 `pong`. */
    data class Reply(
        val id: String?,
        val type: String,
        val protocolVersion: Int?,
        val message: ReplyMessage,
        val body: JsonObject,
    ) : Inbound

    /** `pairPending`, `fetchEnd` and `event` frames. */
    data class Event(
        val type: String,
        val protocolVersion: Int?,
        val message: ServerEvent,
        val body: JsonObject,
    ) : Inbound

    /** Dropped and logged by the caller; never crashes. */
    data class Malformed(val reason: String) : Inbound

    /** A frame over [MAX_FRAME_BYTES]; it was not parsed. */
    data class TooLarge(val sizeBytes: Long) : Inbound
}

/** Result of parsing a request (PC-side rules, used by tests and by [ProtocolCodec.encode] checks). */
sealed interface RequestResult {
    data class Accepted(val request: Request) : RequestResult
    data class Rejected(val code: ErrorCode, val reason: String) : RequestResult
}

class FrameTooLargeException(val sizeBytes: Long) : IllegalArgumentException("frame of $sizeBytes bytes exceeds $MAX_FRAME_BYTES")

class InvalidRequestException(val code: ErrorCode, reason: String) : IllegalArgumentException(reason)

object Base64Url {
    private val chars = Regex("^[A-Za-z0-9_-]*$")

    /** base64url without padding (RFC 4648 section 5). Standard base64 or padding gives null. */
    fun decode(text: String): ByteArray? {
        if (!chars.matches(text) || text.length % 4 == 1) return null
        return try {
            Base64.getUrlDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

object ProtocolCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = false
        coerceInputValues = false
    }

    /** Field names a phone request must never carry (PROTOCOL.md section 2). */
    val forbiddenFields: Set<String> = setOf("path", "folder", "dir", "destDir", "dest_dir", "filename", "file", "saveAs")

    /** UTF-8 size of [text] without building the byte array. */
    fun utf8Size(text: String): Long {
        var n = 0L
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.code < 0x80 -> n += 1
                c.code < 0x800 -> n += 2
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    n += 4
                    i++
                }
                else -> n += 3
            }
            i++
        }
        return n
    }

    // ----------------------------------------------------------------- encode

    /**
     * Encodes a request as one text frame.
     * @throws InvalidRequestException when the request breaks a protocol rule (the phone never sends it)
     * @throws FrameTooLargeException when the frame would exceed [MAX_FRAME_BYTES]
     */
    fun encode(request: Request): String {
        validate(request)?.let { throw InvalidRequestException(it.code, it.reason) }
        val fields = encodeFields(request)
        val obj = buildJsonObject {
            request.id?.let { put("id", it) }
            put("type", request.type)
            for ((key, value) in fields) if (key != "id" && key != "type") put(key, value)
        }
        val text = obj.toString()
        val size = utf8Size(text)
        if (size > MAX_FRAME_BYTES) throw FrameTooLargeException(size)
        return text
    }

    private inline fun <reified T : Request> enc(request: T): JsonObject = json.encodeToJsonElement(request) as JsonObject

    private fun encodeFields(request: Request): JsonObject = when (request) {
        is PairRequest -> enc(request)
        is AuthRequest -> enc(request)
        is PingRequest -> enc(request)
        is DownloadRequest -> enc(request)
        is StatusRequest -> enc(request)
        is RecentRequest -> enc(request)
        is FocusRequest -> enc(request)
        is LinksRequest -> enc(request)
        is JobsRequest -> enc(request)
        is ControlRequest -> enc(request)
        is GlobalRequest -> enc(request)
        is QueuesRequest -> enc(request)
        is AnalyzeRequest -> enc(request)
        is SubscribeRequest -> enc(request)
        is UnsubscribeRequest -> enc(request)
        is UnpairRequest -> enc(request)
        is FilesRequest -> enc(request)
        is FetchRequest -> enc(request)
        is FetchCancelRequest -> enc(request)
        is FetchAck -> enc(request)
        is FileHashRequest -> enc(request)
        is OutboxRequest -> enc(request)
        is OutboxAckRequest -> enc(request)
    }

    // ----------------------------------------------------------------- decode (PC to phone)

    /** Parses one text frame from the PC. Never throws. */
    fun decode(text: String): Inbound {
        // A cheap lower bound first: 1 char is at least 1 byte.
        if (text.length > MAX_FRAME_BYTES) return Inbound.TooLarge(utf8Size(text))
        val size = utf8Size(text)
        if (size > MAX_FRAME_BYTES) return Inbound.TooLarge(size)
        return try {
            decodeObject(text)
        } catch (e: SerializationException) {
            Inbound.Malformed("wrong shape: ${e.shortReason()}")
        } catch (e: IllegalArgumentException) {
            Inbound.Malformed("wrong shape: ${e.shortReason()}")
        } catch (_: StackOverflowError) {
            Inbound.Malformed("nested too deeply")
        }
    }

    private fun decodeObject(text: String): Inbound {
        val element = try {
            json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            return Inbound.Malformed("not JSON")
        }
        val obj = element as? JsonObject ?: return Inbound.Malformed("not a JSON object")
        val typeElement = obj["type"]
        val type = (typeElement as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return Inbound.Malformed("missing or non-string type")

        val protocolVersion = strictInt(obj, "protocolVersion")
        if (protocolVersion is Bad) return Inbound.Malformed("protocolVersion is not an integer")
        val version = (protocolVersion as Ok).value

        if (type == "hello") return dec(ServerHello.serializer(), text)

        if (type == "pairPending") {
            return Inbound.Event(type, version, dec(PairPendingEvent.serializer(), text), obj)
        }
        if (type == "fetchEnd") {
            return Inbound.Event(type, version, dec(FetchEndEvent.serializer(), text), obj)
        }
        if (type == "event") {
            val name = (obj["event"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return Inbound.Malformed("event without a name")
            return Inbound.Event(type, version, decodeEvent(name, text), obj)
        }

        val idElement = obj["id"]
        val id = when {
            idElement == null || idElement is JsonNull -> null
            idElement is JsonPrimitive && idElement.isString -> idElement.content
            else -> return Inbound.Malformed("id is not a string")
        }
        return Inbound.Reply(id, type, version, decodeReply(type, text), obj)
    }

    private fun decodeEvent(name: String, text: String): ServerEvent = when (name) {
        "job.added" -> dec(JobAddedEvent.serializer(), text)
        "job.updated" -> dec(JobUpdatedEvent.serializer(), text)
        "job.progress" -> dec(JobProgressEvent.serializer(), text)
        "job.finished" -> dec(JobFinishedEvent.serializer(), text)
        "job.failed" -> dec(JobFailedEvent.serializer(), text)
        "job.removed" -> dec(JobRemovedEvent.serializer(), text)
        "global.updated" -> dec(GlobalUpdatedEvent.serializer(), text)
        "outbox.added" -> dec(OutboxAddedEvent.serializer(), text)
        else -> UnknownEvent(name)
    }

    private fun decodeReply(type: String, text: String): ReplyMessage = when (type) {
        "paired" -> dec(PairedReply.serializer(), text)
        "authOk" -> dec(AuthOkReply.serializer(), text)
        "pong" -> dec(PongReply.serializer(), text)
        "queued" -> dec(QueuedReply.serializer(), text)
        "status" -> dec(StatusReply.serializer(), text)
        "recent" -> dec(RecentReply.serializer(), text)
        "ok" -> dec(OkReply.serializer(), text)
        "jobs" -> dec(JobsReply.serializer(), text)
        "global" -> dec(GlobalReply.serializer(), text)
        "queues" -> dec(QueuesReply.serializer(), text)
        "analysis" -> dec(AnalysisReply.serializer(), text)
        "subscribed" -> dec(SubscribedReply.serializer(), text)
        "files" -> dec(FilesReply.serializer(), text)
        "fetchStart" -> dec(FetchStartReply.serializer(), text)
        "fileHash" -> dec(FileHashReply.serializer(), text)
        "outbox" -> dec(OutboxReply.serializer(), text)
        "error" -> dec(ErrorReply.serializer(), text)
        else -> UnknownReply(type)
    }

    /**
     * Decodes [text] into [serializer]'s type with the protocol's strict types: kotlinx accepts "3" for a number and
     * 1.5 or 1e3 for some integers, but PROTOCOL.md section 2 says nothing is coerced.
     */
    private fun <T> dec(serializer: KSerializer<T>, text: String): T {
        val tree = json.parseToJsonElement(text)
        if (!shapeMatches(serializer.descriptor, tree)) throw SerializationException("a field has the wrong JSON type")
        return json.decodeFromJsonElement(serializer, tree)
    }

    private fun shapeMatches(d: SerialDescriptor, e: JsonElement): Boolean {
        if (e is JsonNull) return d.isNullable || d.kind == StructureKind.OBJECT
        return when (val kind = d.kind) {
            PrimitiveKind.STRING -> e is JsonPrimitive && e.isString
            PrimitiveKind.BOOLEAN -> e is JsonPrimitive && !e.isString && (e.content == "true" || e.content == "false")
            PrimitiveKind.INT, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> e is JsonPrimitive && !e.isString && e.content.toIntOrNull() != null
            PrimitiveKind.LONG -> e is JsonPrimitive && !e.isString && e.content.toLongOrNull() != null
            StructureKind.LIST -> e is JsonArray && e.all { shapeMatches(d.getElementDescriptor(0), it) }
            StructureKind.CLASS -> e is JsonObject && e.all { (name, value) ->
                val index = d.getElementIndex(name)
                index == CompositeDecoder.UNKNOWN_NAME || shapeMatches(d.getElementDescriptor(index), value)
            }
            else -> kind is PrimitiveKind || e !is JsonNull
        }
    }

    private sealed interface Strict
    private class Ok(val value: Int?) : Strict
    private object Bad : Strict

    /** Absent or null gives Ok(null); a JSON integer gives Ok(n); anything else is Bad. */
    private fun strictInt(obj: JsonObject, key: String): Strict {
        val e = obj[key]
        if (e == null || e is JsonNull) return Ok(null)
        val p = e as? JsonPrimitive ?: return Bad
        if (p.isString) return Bad
        val n = p.content.toIntOrNull() ?: return Bad
        return Ok(n)
    }

    // ----------------------------------------------------------------- decode requests (PC-side rules)

    /**
     * Parses a phone request the way the PC does, so tests and a fake PC can share the rules.
     * Order follows PROTOCOL.md section 2: size, JSON, forbidden fields, id and type, shape, rules.
     */
    fun decodeRequest(text: String): RequestResult {
        val size = utf8Size(text)
        if (size > MAX_FRAME_BYTES) return RequestResult.Rejected(ErrorCode.TOO_LARGE, "frame over $MAX_FRAME_BYTES bytes")
        val element = try {
            json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            return RequestResult.Rejected(ErrorCode.BAD_REQUEST, "not JSON")
        } catch (_: IllegalArgumentException) {
            return RequestResult.Rejected(ErrorCode.BAD_REQUEST, "not JSON")
        }
        val obj = element as? JsonObject ?: return RequestResult.Rejected(ErrorCode.BAD_REQUEST, "not a JSON object")
        obj.keys.firstOrNull { it in forbiddenFields }?.let {
            return RequestResult.Rejected(ErrorCode.FORBIDDEN, "field not allowed: $it")
        }
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return RequestResult.Rejected(ErrorCode.BAD_REQUEST, "missing or non-string type")
        if (type != "fetchAck") {
            val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (id == null || !REQUEST_ID.matches(id)) return RequestResult.Rejected(ErrorCode.BAD_REQUEST, "missing or invalid id")
        }
        val request: Request = try {
            when (type) {
                "pair" -> dec(PairRequest.serializer(), text)
                "auth" -> dec(AuthRequest.serializer(), text)
                "ping" -> dec(PingRequest.serializer(), text)
                "download" -> dec(DownloadRequest.serializer(), text)
                "status" -> dec(StatusRequest.serializer(), text)
                "recent" -> dec(RecentRequest.serializer(), text)
                "focus" -> dec(FocusRequest.serializer(), text)
                "links" -> dec(LinksRequest.serializer(), text)
                "jobs" -> dec(JobsRequest.serializer(), text)
                "control" -> dec(ControlRequest.serializer(), text)
                "global" -> dec(GlobalRequest.serializer(), text)
                "queues" -> dec(QueuesRequest.serializer(), text)
                "analyze" -> dec(AnalyzeRequest.serializer(), text)
                "subscribe" -> dec(SubscribeRequest.serializer(), text)
                "unsubscribe" -> dec(UnsubscribeRequest.serializer(), text)
                "unpair" -> dec(UnpairRequest.serializer(), text)
                "files" -> dec(FilesRequest.serializer(), text)
                "fetch" -> dec(FetchRequest.serializer(), text)
                "fetchCancel" -> dec(FetchCancelRequest.serializer(), text)
                "fetchAck" -> dec(FetchAck.serializer(), text)
                "fileHash" -> dec(FileHashRequest.serializer(), text)
                "outbox" -> dec(OutboxRequest.serializer(), text)
                "outboxAck" -> dec(OutboxAckRequest.serializer(), text)
                else -> return RequestResult.Rejected(ErrorCode.UNSUPPORTED, "unknown type")
            }
        } catch (e: SerializationException) {
            return RequestResult.Rejected(ErrorCode.BAD_REQUEST, "wrong shape: ${e.shortReason()}")
        } catch (e: IllegalArgumentException) {
            return RequestResult.Rejected(ErrorCode.BAD_REQUEST, "wrong shape: ${e.shortReason()}")
        }
        validate(request)?.let { return RequestResult.Rejected(it.code, it.reason) }
        return RequestResult.Accepted(request)
    }

    // ----------------------------------------------------------------- request rules

    class Problem(val code: ErrorCode, val reason: String)

    private val REQUEST_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val DEDUP_ID = Regex("^[A-Za-z0-9_-]{16,64}$")
    private val FOLDER_ID = Regex("^[A-Za-z0-9_-]{1,32}$")
    private const val MAX_URL = 8192
    private const val MAX_UINT32 = 4_294_967_295L

    private fun bad(reason: String) = Problem(ErrorCode.BAD_REQUEST, reason)

    /** Null when the request obeys PROTOCOL.md sections 2, 12 and 13. */
    fun validate(r: Request): Problem? {
        val id = r.id
        if (id != null && !REQUEST_ID.matches(id)) return bad("id")
        return when (r) {
            is PairRequest -> when {
                r.deviceName.trim().length !in 1..64 -> bad("deviceName")
                Base64Url.decode(r.publicKey)?.size != 91 -> bad("publicKey")
                Base64Url.decode(r.codeProof)?.size != 32 -> bad("codeProof")
                (r.appVersion?.length ?: 0) > 32 -> bad("appVersion")
                else -> null
            }
            is AuthRequest -> when {
                Base64Url.decode(r.deviceId)?.size != 16 || r.deviceId.length != 22 -> bad("deviceId")
                Base64Url.decode(r.signature)?.size?.let { it in 1..72 } != true -> bad("signature")
                (r.appVersion?.length ?: 0) > 32 -> bad("appVersion")
                else -> null
            }
            is PingRequest, is QueuesRequest, is UnsubscribeRequest, is UnpairRequest, is OutboxRequest -> null
            is DownloadRequest -> when {
                !isValidUrl(r.url) -> bad("url")
                !DEDUP_ID.matches(r.requestId) -> bad("requestId")
                r.quality != null && r.quality !in Qualities.all -> bad("quality")
                r.folderId != null && !FOLDER_ID.matches(r.folderId) -> bad("folderId")
                (r.title?.length ?: 0) > 512 -> bad("title")
                r.pageUrl != null && !isValidUrl(r.pageUrl) -> bad("pageUrl")
                else -> null
            }
            is StatusRequest -> when {
                r.urls.size > MAX_STATUS_URLS -> Problem(ErrorCode.TOO_LARGE, "urls")
                r.urls.any { !isValidUrl(it) } -> bad("urls")
                else -> null
            }
            is RecentRequest -> if (r.limit != null && r.limit !in 1..20) bad("limit") else null
            is FocusRequest -> if ((r.target?.length ?: 0) > 32) bad("target") else null
            is LinksRequest -> when {
                r.urls.size > MAX_LINKS_PER_REQUEST -> Problem(ErrorCode.TOO_LARGE, "urls")
                r.urls.isEmpty() || r.urls.any { !isValidUrl(it) } -> bad("urls")
                !DEDUP_ID.matches(r.requestId) -> bad("requestId")
                r.quality != null && r.quality !in Qualities.all -> bad("quality")
                r.folderId != null && !FOLDER_ID.matches(r.folderId) -> bad("folderId")
                r.pageUrl != null && !isValidUrl(r.pageUrl) -> bad("pageUrl")
                (r.pageTitle?.length ?: 0) > 512 -> bad("pageTitle")
                else -> null
            }
            is JobsRequest -> when {
                r.limit != null && r.limit !in 1..100 -> bad("limit")
                (r.cursor?.length ?: 0) > 64 -> bad("cursor")
                r.filter != null && r.filter !in JobFilters.all -> bad("filter")
                else -> null
            }
            is ControlRequest -> when {
                r.jobId < 1 -> bad("jobId")
                r.action !in ControlActions.all -> bad("action")
                else -> null
            }
            is GlobalRequest -> when {
                r.action !in GlobalActions.all -> bad("action")
                r.action == "setSpeedLimit" && (r.bytesPerSecond == null || r.bytesPerSecond !in 1_024L..10_000_000_000L) -> bad("bytesPerSecond")
                else -> null
            }
            is AnalyzeRequest -> if (!isValidUrl(r.url) || r.url.startsWith("magnet:", ignoreCase = true)) bad("url") else null
            is SubscribeRequest -> null
            is FilesRequest -> when {
                r.limit != null && r.limit !in 1..100 -> bad("limit")
                (r.cursor?.length ?: 0) > 64 -> bad("cursor")
                else -> null
            }
            is FetchRequest -> when {
                r.jobId < 1 -> bad("jobId")
                r.offset != null && r.offset < 0 -> bad("offset")
                r.length != null && r.length < 1 -> bad("length")
                (r.etag?.length ?: 0) > 64 -> bad("etag")
                else -> null
            }
            is FetchCancelRequest -> if (r.streamId !in 1..MAX_UINT32) bad("streamId") else null
            is FetchAck -> when {
                r.streamId !in 1..MAX_UINT32 -> bad("streamId")
                r.received < 0 -> bad("received")
                else -> null
            }
            is FileHashRequest -> if (r.jobId < 1) bad("jobId") else null
            is OutboxAckRequest -> when {
                r.itemIds.size > 100 -> Problem(ErrorCode.TOO_LARGE, "itemIds")
                r.itemIds.isEmpty() -> bad("itemIds")
                else -> null
            }
        }
    }

    /** The `url` rule of PROTOCOL.md section 2: http(s) with a host, or `magnet:` with `xt=`; at most 8,192 characters. */
    fun isValidUrl(url: String): Boolean {
        if (url.isEmpty() || url.length > MAX_URL) return false
        if (url.any { it == '\u0000' || it.isWhitespace() }) return false
        if (url.startsWith("magnet:", ignoreCase = true)) {
            return url.substringAfter('?', "").split('&').any { it.startsWith("xt=") }
        }
        val match = Regex("^https?://([^/?#]+)", RegexOption.IGNORE_CASE).find(url) ?: return false
        val hostAndPort = match.groupValues[1].substringAfterLast('@')
        val host = if (hostAndPort.startsWith("[")) hostAndPort.substringBefore(']').removePrefix("[") else hostAndPort.substringBefore(':')
        return host.isNotEmpty()
    }

    private fun Exception.shortReason(): String = (message ?: javaClass.simpleName).lineSequence().first().take(160)

    /** Parses [text] to a [JsonElement]; for tests that compare messages semantically. */
    fun parseTree(text: String): JsonElement = json.parseToJsonElement(text)

    /** Decodes a [JsonElement] strictly into [T]; for tests. */
    inline fun <reified T> decodeTree(element: JsonElement): T = json.decodeFromJsonElement(element)
}
