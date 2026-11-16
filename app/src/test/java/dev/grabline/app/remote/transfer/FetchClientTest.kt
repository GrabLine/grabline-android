package dev.grabline.app.remote.transfer

import dev.grabline.app.data.PairedPc
import dev.grabline.app.remote.connection.AuthProvider
import dev.grabline.app.remote.connection.FakePc
import dev.grabline.app.remote.connection.PcSession
import dev.grabline.app.remote.connection.TransferConnector
import dev.grabline.app.remote.protocol.AuthRequest
import dev.grabline.app.remote.protocol.ErrorCode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A PC that serves one finished file over `fetch` and `fileHash`, with switches for the failures QA-39 cares about. */
class FakeFileServer(private val pc: FakePc, var content: ByteArray, var etag: String = "s-1") {
    @Volatile var dropAfter: Long? = null        // the next fetch sends bytes up to here, then the link drops
    @Volatile var skipOneChunk = false           // the next fetch leaves a gap between two frames
    @Volatile var wrongHash = false
    @Volatile var hashEtag: String? = null
    val fetches = CopyOnWriteArrayList<JsonObject>()
    private val streams = AtomicInteger(0)

    init {
        pc.connHandlers["fetch"] = { conn, id, body ->
            fetches += body
            val offset = body["offset"]?.jsonPrimitive?.long ?: 0L
            val wanted = body["etag"]?.jsonPrimitive?.content
            when {
                wanted != null && wanted != etag -> listOf(pc.error(id, "conflict"))
                offset > content.size -> listOf(pc.error(id, "conflict"))
                else -> {
                    val sid = 100L + streams.incrementAndGet()
                    conn.push(
                        """{"id":"$id","type":"fetchStart","protocolVersion":6,"streamId":$sid,"jobId":1,"name":"big.bin",""" +
                            """"size":${content.size},"offset":$offset,"length":${content.size - offset},"etag":"$etag","chunkSize":$CHUNK,"windowBytes":8388608}""",
                    )
                    val limit = dropAfter ?: content.size.toLong()
                    val gap = skipOneChunk
                    dropAfter = null
                    skipOneChunk = false
                    var pos = offset
                    var sent = 0
                    while (pos < limit) {
                        if (gap && sent == 1) pos += CHUNK // a missing frame
                        if (pos >= limit) break
                        val n = minOf(CHUNK.toLong(), limit - pos).toInt()
                        conn.pushBinary(FetchClient.frame(sid, pos, content.copyOfRange(pos.toInt(), pos.toInt() + n)))
                        pos += n
                        sent++
                    }
                    if (limit < content.size || gap) {
                        if (!gap) conn.closeFromPc(1006)
                    } else {
                        conn.push("""{"type":"fetchEnd","protocolVersion":6,"streamId":$sid,"status":"complete","nextOffset":${content.size}}""")
                    }
                    null
                }
            }
        }
        pc.handlers["fileHash"] = { id, _ ->
            val sha = if (wrongHash) "0".repeat(64) else sha256(content)
            listOf("""{"id":"$id","type":"fileHash","protocolVersion":6,"jobId":1,"size":${content.size},"etag":"${hashEtag ?: etag}","sha256":"$sha"}""")
        }
        pc.handlers["files"] = { id, _ ->
            listOf("""{"id":"$id","type":"files","protocolVersion":6,"files":[{"jobId":1,"name":"big.bin","size":${content.size},"finishedAt":5,"mime":null}],"nextCursor":null}""")
        }
    }

    fun acks(): List<Long> = pc.conns.flatMap { c ->
        c.received.map { Json.parseToJsonElement(it).jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "fetchAck" }
            .map { it["received"]!!.jsonPrimitive.long }
    }

    companion object {
        const val CHUNK = 65_536
        fun sha256(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}

class FetchClientTest {
    @get:Rule val tmp = TemporaryFolder()

    private val pc = FakePc()
    private val pcRow = PairedPc(
        pcId = "pcA", name = "Desk", fingerprint = "ab", deviceId = "KsucBZHeyJguN6H0BzJQiw",
        addresses = listOf("10.0.0.1"), port = 47821, lastGoodAddress = "10.0.0.1", protocolVersion = 6, pairedAt = 1L,
    )
    private val auth = AuthProvider { p, _, id ->
        AuthRequest(id, deviceId = p.deviceId, signature = "MEUCIQDEkNVli0ImyofhN_Wu6-4n3CBTJwbf9PLA_GpHelIhsAIgGE-UGdJQPUyd2T_zsX1zDXFD6yn-mSsGqKBL67H4TCM")
    }
    private val connector = TransferConnector { _ ->
        PcSession(pcRow, "10.0.0.1", pc, auth).takeIf { it.open().isSuccess }
    }
    private val data = ByteArray(700_000) { (it * 31 + it / 7).toByte() }
    private val server = FakeFileServer(pc, data)

    private fun client() = FetchClient(connector, maxFailures = 3, backoffMs = { 1 }, stallMs = 2_000, ackEveryBytes = 128 * 1024)

    private class Recorder : FetchProgress {
        var name: String? = null
        var etag: String? = null
        var size: Long? = null
        var last = 0L
        override suspend fun onStart(name: String, size: Long, etag: String?) { this.name = name; this.size = size; this.etag = etag }
        override suspend fun onBytes(onDisk: Long) { last = onDisk }
    }

    @Test fun listsFiles() = runBlocking {
        val files = client().list("pcA")
        assertEquals(listOf(1L), files!!.map { it.jobId })
        assertEquals(data.size.toLong(), files.single().size)
    }

    @Test fun fullCopyIsVerifiedAndAckedAfterEveryWindowStep() = runBlocking {
        val file = File(tmp.root, "a.part")
        val rec = Recorder()
        val result = client().fetch("pcA", 1, file, null, null, rec)
        assertTrue(result.toString(), result is FetchResult.Complete)
        assertEquals(FakeFileServer.sha256(data), (result as FetchResult.Complete).sha256)
        assertTrue(data.contentEquals(file.readBytes()))
        assertEquals("s-1", rec.etag)
        assertEquals(data.size.toLong(), rec.last)
        val acks = server.acks()
        assertTrue("acks: $acks", acks.size >= 4 && acks.zipWithNext().all { (a, b) -> b > a } && acks.last() == data.size.toLong())
        assertTrue("an ack is sent at least every 128 KiB step", acks.zipWithNext().all { (a, b) -> b - a <= 128 * 1024 + FakeFileServer.CHUNK })
    }

    @Test fun dropHalfwayResumesWithOffsetAndEtagAndChecksumMatches() = runBlocking {
        val file = File(tmp.root, "b.part")
        server.dropAfter = 327_680L // 5 chunks
        val result = client().fetch("pcA", 1, file, null, null, Recorder())
        assertTrue(result.toString(), result is FetchResult.Complete)
        assertTrue(data.contentEquals(file.readBytes()))
        assertEquals(2, server.fetches.size)
        val resume = server.fetches[1]
        assertEquals(327_680L, resume["offset"]!!.jsonPrimitive.long)
        assertEquals("s-1", resume["etag"]!!.jsonPrimitive.content)
        assertNull("the first request starts at 0 with no etag", server.fetches[0]["offset"])
    }

    @Test fun resumesAPartialFileFromAnEarlierRunWithTheSavedEtag() = runBlocking {
        val file = File(tmp.root, "c.part")
        file.writeBytes(data.copyOf(200_000))
        val result = client().fetch("pcA", 1, file, "s-1", data.size.toLong(), Recorder())
        assertTrue(result is FetchResult.Complete)
        assertEquals(200_000L, server.fetches.single()["offset"]!!.jsonPrimitive.long)
        assertTrue(data.contentEquals(file.readBytes()))
    }

    @Test fun anOverlongPartialWithoutEtagStartsAgain() = runBlocking {
        val file = File(tmp.root, "d.part")
        file.writeBytes(ByteArray(1000) { 9 })
        val result = client().fetch("pcA", 1, file, null, null, Recorder())
        assertTrue(result is FetchResult.Complete)
        assertNull(server.fetches.single()["offset"])
    }

    @Test fun etagChangeRestartsFromZero() = runBlocking {
        val file = File(tmp.root, "e.part")
        file.writeBytes(data.copyOf(200_000))
        server.etag = "s-2" // the file changed on the PC since the partial copy
        val result = client().fetch("pcA", 1, file, "s-1", data.size.toLong(), Recorder())
        assertTrue(result.toString(), result is FetchResult.Complete)
        assertEquals(2, server.fetches.size)
        assertEquals("s-1", server.fetches[0]["etag"]!!.jsonPrimitive.content)
        assertNull("restart asks for the whole file", server.fetches[1]["offset"])
        assertNull(server.fetches[1]["etag"])
        assertTrue(data.contentEquals(file.readBytes()))
        assertEquals("s-2", (result as FetchResult.Complete).etag)
    }

    @Test fun checksumMismatchDeletesTheCopy() = runBlocking {
        val file = File(tmp.root, "f.part")
        server.wrongHash = true
        val result = client().fetch("pcA", 1, file, null, null, Recorder())
        assertEquals(FetchResult.HashMismatch, result)
        assertFalse("the copy is deleted", file.exists())
    }

    @Test fun outOfOrderFrameCancelsAndResumesFromDisk() = runBlocking {
        val file = File(tmp.root, "g.part")
        server.skipOneChunk = true
        val result = client().fetch("pcA", 1, file, null, null, Recorder())
        assertTrue(result.toString(), result is FetchResult.Complete)
        assertTrue(data.contentEquals(file.readBytes()))
        assertEquals(2, server.fetches.size)
        // Only the first frame was accepted before the gap, so the resume starts right after it.
        assertEquals(FakeFileServer.CHUNK.toLong(), server.fetches[1]["offset"]!!.jsonPrimitive.long)
        val cancelled = pc.conns.any { c -> c.received.any { it.contains("\"fetchCancel\"") } }
        assertTrue("the phone cancels the broken stream", cancelled)
    }

    @Test fun unknownJobIsNotFoundAndUnfinishedOnesAreRefused() = runBlocking {
        pc.connHandlers["fetch"] = { _, id, _ -> listOf(pc.error(id, "notFound")) }
        assertEquals(FetchResult.NotFound, client().fetch("pcA", 9, File(tmp.root, "h.part"), null, null, Recorder()))
        pc.connHandlers["fetch"] = { _, id, _ -> listOf(pc.error(id, "conflict")) }
        assertEquals(FetchResult.Refused(ErrorCode.CONFLICT), client().fetch("pcA", 9, File(tmp.root, "i.part"), null, null, Recorder()))
        pc.connHandlers["fetch"] = { _, id, _ -> listOf(pc.error(id, "forbidden")) }
        assertEquals(FetchResult.Refused(ErrorCode.FORBIDDEN), client().fetch("pcA", 9, File(tmp.root, "j.part"), null, null, Recorder()))
    }

    @Test fun unreachablePcKeepsThePartialFile() = runBlocking {
        pc.defaultMode = FakePc.Mode.Unreachable()
        val file = File(tmp.root, "k.part")
        file.writeBytes(data.copyOf(1000))
        val result = client().fetch("pcA", 1, file, "s-1", data.size.toLong(), Recorder())
        assertEquals(FetchResult.Unreachable, result)
        assertEquals(1000L, file.length())
    }
}