package dev.grabline.app.remote.transfer

import dev.grabline.app.remote.connection.RemoteException
import dev.grabline.app.remote.connection.TransferChannel
import dev.grabline.app.remote.connection.TransferConnector
import dev.grabline.app.remote.protocol.ErrorCode
import dev.grabline.app.remote.protocol.FetchAck
import dev.grabline.app.remote.protocol.FetchCancelRequest
import dev.grabline.app.remote.protocol.FetchEndEvent
import dev.grabline.app.remote.protocol.FetchEndStatus
import dev.grabline.app.remote.protocol.FetchRequest
import dev.grabline.app.remote.protocol.FetchStartReply
import dev.grabline.app.remote.protocol.FileEntry
import dev.grabline.app.remote.protocol.FileHashReply
import dev.grabline.app.remote.protocol.FileHashRequest
import dev.grabline.app.remote.protocol.FilesReply
import dev.grabline.app.remote.protocol.FilesRequest
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How a copy from the PC ended (see [FetchClient.fetch]). */
sealed interface FetchResult {
    /** All bytes are in the file and its SHA-256 matches the PC's. */
    data class Complete(val size: Long, val etag: String?, val sha256: String) : FetchResult
    /** The checksum differed: the copy was deleted (PROTOCOL.md 13 "Integrity"). */
    data object HashMismatch : FetchResult
    /** The PC no longer has that job or file. */
    data object NotFound : FetchResult
    /** The PC refused (forbidden, unsupported, the job is not finished...). */
    data class Refused(val code: ErrorCode) : FetchResult
    /** The PC could not be reached, or kept dropping; the partial file is kept so a later try resumes. */
    data object Unreachable : FetchResult
}

/** What a copy reports while it runs. */
interface FetchProgress {
    /** The PC accepted the request: [size] is the whole file. Throw an IOException to stop (for example no space). */
    suspend fun onStart(name: String, size: Long, etag: String?) {}
    /** Bytes of the file now in the temp file, counted from 0. */
    suspend fun onBytes(onDisk: Long) {}
}

/**
 * Copies a finished PC file to a local temp file over a second connection (PROTOCOL.md 13).
 *
 * Frames must arrive in order (GLF1: magic, streamId, offset, payload); anything else cancels the stream and resumes
 * from the bytes already on disk. `fetchAck` goes out only after those bytes are flushed to disk. After a drop the
 * copy resumes with `offset` and the saved `etag`; if the PC says the file changed, it starts again from 0. A finished
 * copy is checked against the PC's SHA-256 (`fileHash`).
 */
class FetchClient(
    private val connector: TransferConnector,
    private val maxFailures: Int = 5,
    private val backoffMs: (failures: Int) -> Long = { it * 1_000L },
    private val stallMs: Long = 30_000,
    private val ackEveryBytes: Long = ACK_EVERY,
) {
    /** The PC's finished single files, newest first (`files`), or null when it cannot be reached. */
    suspend fun list(pcId: String, limit: Int = 100): List<FileEntry>? {
        val ch = connector.open(pcId) ?: return null
        try {
            val all = mutableListOf<FileEntry>()
            var cursor: String? = null
            repeat(10) {
                val message = try {
                    ch.request { id -> FilesRequest(id, limit = limit, cursor = cursor) }.message
                } catch (e: RemoteException) {
                    return if (all.isEmpty()) null else all
                } catch (e: TimeoutCancellationException) {
                    return if (all.isEmpty()) null else all
                }
                val reply = message as? FilesReply ?: return all
                all += reply.files
                cursor = reply.nextCursor ?: return all
            }
            return all
        } finally {
            ch.close()
        }
    }

    private class Held(var etag: String?, var size: Long?)

    private sealed interface Attempt {
        data class Done(val result: FetchResult) : Attempt
        /** Dropped or stalled; [progressed] = some bytes arrived this time. */
        data class Retry(val progressed: Boolean) : Attempt
        /** A frame was wrong: cancelled the stream, resume from disk. */
        data object OutOfOrder : Attempt
        /** The file changed on the PC: start again from 0. */
        data object Restart : Attempt
        data object Offline : Attempt
    }

    /**
     * Copies PC job [jobId] into [file] (appending to a partial copy when [savedEtag] says it is the same file).
     * [knownSize] is the whole size seen before; with all of it on disk only the checksum is checked.
     */
    suspend fun fetch(
        pcId: String,
        jobId: Long,
        file: File,
        savedEtag: String?,
        knownSize: Long?,
        progress: FetchProgress,
    ): FetchResult {
        val held = Held(savedEtag, knownSize)
        var failures = 0
        var restarts = 0
        var disorder = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            when (val a = attempt(pcId, jobId, file, held, progress)) {
                is Attempt.Done -> return a.result
                Attempt.Restart -> {
                    file.delete()
                    held.etag = null
                    held.size = null
                    if (++restarts > MAX_RESTARTS) return FetchResult.Refused(ErrorCode.CONFLICT)
                }
                Attempt.OutOfOrder -> if (++disorder > MAX_DISORDER) return FetchResult.Unreachable
                is Attempt.Retry -> {
                    if (a.progressed) failures = 0
                    if (++failures > maxFailures) return FetchResult.Unreachable
                    delay(backoffMs(failures))
                }
                Attempt.Offline -> {
                    if (++failures > maxFailures) return FetchResult.Unreachable
                    delay(backoffMs(failures))
                }
            }
        }
    }

    private suspend fun attempt(pcId: String, jobId: Long, file: File, held: Held, progress: FetchProgress): Attempt {
        val ch = connector.open(pcId) ?: return Attempt.Offline
        try {
            var have = if (file.exists()) file.length() else 0L
            if (have > 0 && held.etag == null) { // a partial copy of unknown origin cannot be trusted
                file.delete()
                have = 0
            }
            val size = held.size
            if (have > 0 && size != null && have == size) return verify(ch, jobId, file, held)
            return coroutineScope {
                val end = CompletableDeferred<FetchEndEvent>()
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    ch.events.collect { if (it is FetchEndEvent) end.complete(it) }
                }
                try {
                    stream(ch, jobId, file, held, have, end, progress)
                } finally {
                    watcher.cancel()
                }
            }
        } finally {
            ch.close()
        }
    }

    private suspend fun stream(
        ch: TransferChannel,
        jobId: Long,
        file: File,
        held: Held,
        have: Long,
        end: CompletableDeferred<FetchEndEvent>,
        progress: FetchProgress,
    ): Attempt {
        val resumed = have > 0
        val startReply = try {
            ch.request { id -> FetchRequest(id, jobId, offset = have.takeIf { resumed }, etag = held.etag.takeIf { resumed }) }.message
        } catch (e: RemoteException) {
            return failed(e, resumed)
        } catch (e: TimeoutCancellationException) {
            return Attempt.Retry(false)
        }
        val start = startReply as? FetchStartReply ?: return Attempt.Retry(false)
        if (resumed && start.offset != have) return Attempt.Restart
        held.etag = start.etag
        held.size = start.size
        progress.onStart(start.name, start.size, start.etag)

        val streamId = start.streamId
        val last = start.offset + start.length
        var expected = start.offset
        var sinceAck = 0L
        var arrived = false
        var outOfOrder = false

        FileOutputStream(file, true).use { out ->
            suspend fun accept(bytes: ByteArray): Boolean {
                val payload = bytes.size - HEADER
                if (payload < 1 || payload > MAX_PAYLOAD || !isFrameHeader(bytes) || streamIdOf(bytes) != streamId ||
                    offsetOf(bytes) != expected || expected + payload > last
                ) return false
                out.write(bytes, HEADER, payload)
                expected += payload
                sinceAck += payload
                arrived = true
                if (sinceAck >= ackEveryBytes) {
                    out.flush()
                    out.fd.sync() // the ack promises these bytes are on disk
                    ch.notify(FetchAck(streamId, expected))
                    sinceAck = 0
                }
                progress.onBytes(expected)
                return true
            }

            while (expected < last && !outOfOrder) {
                val next = withTimeoutOrNull(stallMs) {
                    select<Any> {
                        ch.frames.onReceiveCatching { r -> r.getOrNull() ?: Closed }
                        end.onAwait { it }
                    }
                } ?: Stalled
                when (next) {
                    is ByteArray -> if (!accept(next)) outOfOrder = true
                    is FetchEndEvent -> {
                        // Frames sent before the end event may still be queued here.
                        while (!outOfOrder) {
                            val queued = ch.frames.tryReceive().getOrNull() ?: break
                            if (!accept(queued)) outOfOrder = true
                        }
                        break
                    }
                    else -> break // Closed or Stalled
                }
            }
            out.flush()
            out.fd.sync()
        }
        if (outOfOrder) {
            ch.notify(FetchCancelRequest("c0", streamId))
            return Attempt.OutOfOrder
        }
        if (expected < last) return Attempt.Retry(arrived)
        ch.notify(FetchAck(streamId, expected))
        if (last != start.size) return Attempt.Done(FetchResult.Refused(ErrorCode.BAD_REQUEST)) // we never ask for part ranges
        return verify(ch, jobId, file, held)
    }

    private suspend fun verify(ch: TransferChannel, jobId: Long, file: File, held: Held): Attempt {
        val hashReply = try {
            ch.request { id -> FileHashRequest(id, jobId) }.message
        } catch (e: RemoteException) {
            return failed(e, resumed = false)
        } catch (e: TimeoutCancellationException) {
            return Attempt.Retry(false)
        }
        val reply = hashReply as? FileHashReply ?: return Attempt.Retry(false)
        if (reply.size != file.length() || (reply.etag != null && held.etag != null && reply.etag != held.etag)) {
            return Attempt.Restart // the file changed while it was copied
        }
        val local = withContext(Dispatchers.IO) { sha256Hex(file) }
        if (!local.equals(reply.sha256, ignoreCase = true)) {
            file.delete()
            return Attempt.Done(FetchResult.HashMismatch)
        }
        return Attempt.Done(FetchResult.Complete(reply.size, held.etag, local))
    }

    private fun failed(e: RemoteException, resumed: Boolean): Attempt = when (e.code) {
        ErrorCode.NOT_FOUND -> Attempt.Done(FetchResult.NotFound)
        ErrorCode.CONFLICT -> if (resumed) Attempt.Restart else Attempt.Done(FetchResult.Refused(ErrorCode.CONFLICT))
        ErrorCode.FORBIDDEN, ErrorCode.UNSUPPORTED, ErrorCode.BAD_REQUEST, ErrorCode.VERSION_TOO_OLD, ErrorCode.UNAUTHORIZED ->
            Attempt.Done(FetchResult.Refused(e.code))
        else -> Attempt.Retry(false)
    }

    private object Closed
    private object Stalled

    companion object {
        const val ACK_EVERY = 1L shl 20
        const val HEADER = 16
        const val MAX_PAYLOAD = 262_144
        private const val MAX_RESTARTS = 3
        private const val MAX_DISORDER = 5
        private val MAGIC = byteArrayOf('G'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte())

        fun isFrameHeader(b: ByteArray) = b.size > HEADER && b[0] == MAGIC[0] && b[1] == MAGIC[1] && b[2] == MAGIC[2] && b[3] == MAGIC[3]
        fun streamIdOf(b: ByteArray): Long = ByteBuffer.wrap(b, 4, 4).int.toLong() and 0xFFFFFFFFL
        fun offsetOf(b: ByteArray): Long = ByteBuffer.wrap(b, 8, 8).long

        /** Builds one frame (used by tests and fakes). */
        fun frame(streamId: Long, offset: Long, payload: ByteArray): ByteArray =
            ByteBuffer.allocate(HEADER + payload.size).put(MAGIC).putInt(streamId.toInt()).putLong(offset).put(payload).array()

        fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}