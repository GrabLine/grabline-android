package dev.grabline.app.engine

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.grabline.app.data.AppDatabase
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer

fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

/** Deterministic, non-repeating-looking bytes so a wrong splice changes the hash. */
fun content(n: Int, seed: Int = 1) = ByteArray(n) { ((it * 31 + seed * 17) xor (it ushr 8) xor (it ushr 16)).toByte() }

class FakeSettings(var config: EngineConfig) : EngineSettings {
    override suspend fun current() = config
}

class FakeStorage(private val dir: File) : PhoneStorage {
    @Volatile var free: Long = Long.MAX_VALUE
    val published = ConcurrentHashMap<String, ByteArray>()
    val preexisting = CopyOnWriteArrayList<String>()
    override fun scratchFile(jobId: Long) = File(dir, "job-$jobId.part")
    override fun deleteScratch(jobId: Long) { scratchFile(jobId).delete(); scratchDir(jobId).deleteRecursively() }
    override fun freeBytes(folderUri: String?) = free
    override suspend fun existingNames(folderUri: String?): Set<String> = (preexisting + published.keys).toSet()
    override suspend fun publish(folderUri: String?, name: String, scratch: File): String {
        published[name] = scratch.readBytes()
        return "content://fake/$name"
    }
    fun scratchFiles() = dir.listFiles()?.filter { it.name.endsWith(".part") }.orEmpty()
}

/** A server that behaves however the test says, and records every request. */
class Misbehaving(val handler: (RecordedRequest) -> MockResponse) : Dispatcher() {
    val log = CopyOnWriteArrayList<RecordedRequest>()
    override fun dispatch(request: RecordedRequest): MockResponse {
        log += request
        return handler(request)
    }
    fun isProbe(r: RecordedRequest) = r.getHeader("Range") == "bytes=0-0"
    /** Requests after the probe(s): the ones that carry file bytes. */
    fun fetches() = log.filter { !isProbe(it) }
}

/** A well-behaved range server's answer (200, 206 or 416), honouring If-Range. */
fun ranged(req: RecordedRequest, body: ByteArray, etag: String? = "\"v1\"", ranges: Boolean = true): MockResponse {
    val r = MockResponse()
    etag?.let { r.setHeader("ETag", it) }
    val range = req.getHeader("Range")
    val ifRange = req.getHeader("If-Range")
    if (!ranges) return r.setResponseCode(200).setBody(Buffer().write(body))
    r.setHeader("Accept-Ranges", "bytes")
    if (range == null || (ifRange != null && ifRange != etag)) return r.setResponseCode(200).setBody(Buffer().write(body))
    val m = Regex("""bytes=(\d+)-(\d*)""").matchEntire(range) ?: return r.setResponseCode(400)
    val a = m.groupValues[1].toLong()
    val b = m.groupValues[2].toLongOrNull()?.coerceAtMost(body.size - 1L) ?: (body.size - 1L)
    if (a >= body.size) return r.setResponseCode(416).setHeader("Content-Range", "bytes */${body.size}")
    return r.setResponseCode(206).setHeader("Content-Range", "bytes $a-$b/${body.size}")
        .setBody(Buffer().write(body, a.toInt(), (b - a + 1).toInt()))
}

fun MockResponse.dropMidBody(): MockResponse = setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)

/** Engine + in-memory Room + fake storage + a MockWebServer, wired the way the app does. */
class Rig(
    config: EngineConfig = EngineConfig(connectionsPerFile = 4, downloadsAtOnce = 3, onlyWifi = true, autoRetry = 5),
) {
    val server = MockWebServer()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
    val dao = db.phoneJobs()
    private val dir = File(ctx.cacheDir, "rig-${System.nanoTime()}").apply { mkdirs() }
    val storage = FakeStorage(dir)
    val settings = FakeSettings(config)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val engine = DownloadEngine(
        dao, OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build(), storage, settings, scope,
        minSegmentBytes = 64 * 1024, backoffBaseMs = 5,
    )

    fun serve(d: Misbehaving): Misbehaving { server.dispatcher = d; server.start(); return d }
    fun url(path: String = "/f.bin") = server.url(path).toString()

    suspend fun job(id: Long) = engine.jobs.first().first { it.id == id }

    /** Add a link, run the queue dry, return the job as the UI would see it. */
    suspend fun download(path: String = "/f.bin", title: String? = null): PhoneJob {
        val added = engine.add(PhoneAddRequest(url(path), title))
        engine.runUntilIdle()
        return job(added.id)
    }

    suspend fun awaitUntil(what: String, check: suspend () -> Boolean) {
        withTimeout(30_000) { while (!check()) delay(20) }
    }

    fun close() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        runCatching { server.shutdown() }
        db.close()
    }
}

fun rig(config: EngineConfig = EngineConfig(connectionsPerFile = 4, downloadsAtOnce = 3, onlyWifi = true, autoRetry = 5), block: suspend Rig.() -> Unit) {
    val r = Rig(config)
    try {
        runBlocking { withTimeout(90_000) { r.block() } }
    } finally {
        r.close()
    }
}

