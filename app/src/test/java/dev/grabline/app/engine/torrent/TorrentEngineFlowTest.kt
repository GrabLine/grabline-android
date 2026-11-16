package dev.grabline.app.engine.torrent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.grabline.app.data.AppDatabase
import dev.grabline.app.engine.DownloadEngine
import dev.grabline.app.engine.EngineConfig
import dev.grabline.app.engine.FakeSettings
import dev.grabline.app.engine.FakeStorage
import dev.grabline.app.engine.PhoneAddRequest
import dev.grabline.app.engine.PhoneJobStatus
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A library that behaves as the test says: files appear in the save folder, no network anywhere. */
class FakeTorrents(private val files: List<Pair<String, ByteArray>> = listOf("Movie.bin" to ByteArray(4096) { it.toByte() })) : TorrentBackend {
    @Volatile var finished = true
    @Volatile var uploaded = 0L
    @Volatile var onSnapshot: (Int) -> Unit = {}
    val adds = ConcurrentHashMap<String, Int>()
    val resumeSeen = ArrayList<ByteArray?>()
    val removed = ArrayList<Pair<String, Boolean>>()
    private val calls = AtomicInteger()

    override fun libraryVersion() = "fake"

    override suspend fun add(source: TorrentSource, saveDir: File, resumeData: ByteArray?, sequential: Boolean, wanted: List<Boolean>?): String {
        resumeSeen += resumeData
        adds.merge("hash", 1, Int::plus)
        if (finished) files.forEach { (path, bytes) -> File(saveDir, path).apply { parentFile?.mkdirs(); writeBytes(bytes) } }
        return "hash"
    }

    override fun snapshot(hash: String): TorrentSnapshot? {
        onSnapshot(calls.incrementAndGet())
        val size = files.sumOf { it.second.size.toLong() }
        return TorrentSnapshot(
            infoHash = hash, hasMetadata = true, name = "Movie", files = files.map { TorrentFileInfo(it.first, it.second.size.toLong()) },
            wantedBytes = size, doneBytes = if (finished) size else size / 2, downRate = 100, upRate = 0, peers = 3, seeds = 1,
            uploaded = uploaded, finished = finished, error = null,
        )
    }

    override fun setSequential(hash: String, on: Boolean) {}
    override fun setWanted(hash: String, wanted: List<Boolean>) {}
    override suspend fun saveResume(hash: String): ByteArray? = "resume-v1".toByteArray()
    override fun remove(hash: String, deleteFiles: Boolean) { removed += hash to deleteFiles }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TorrentEngineFlowTest {
    private val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Movie"
    private lateinit var db: AppDatabase
    private lateinit var storage: FakeStorage
    private lateinit var scope: CoroutineScope
    private lateinit var settings: FakeSettings
    private val backend = FakeTorrents()
    private val clockOffset = AtomicLong(0)
    private lateinit var engine: DownloadEngine

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        storage = FakeStorage(File(ctx.cacheDir, "torrent-${System.nanoTime()}").apply { mkdirs() })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = FakeSettings(EngineConfig(onlyWifi = false, torrentsOnMobile = false, seedMode = SeedRules.OFF))
        engine = DownloadEngine(
            db.phoneJobs(), OkHttpClient(), storage, settings, scope,
            clock = { System.currentTimeMillis() + clockOffset.get() }, torrents = backend,
            torrentFiles = { uri -> if (uri == "content://shared/a.torrent") "d4:infod4:name1:aee".toByteArray() else null },
        )
    }

    @After fun tearDown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        db.close()
    }

    private suspend fun job(id: Long) = engine.jobs.first().first { it.id == id }
    private suspend fun until(what: String, check: suspend () -> Boolean) = withTimeout(20_000) { while (!check()) delay(20) }

    @Test fun magnetBecomesATorrentJobNamedFromItsDisplayName() = runBlocking {
        val j = engine.add(PhoneAddRequest(magnet))
        val row = db.phoneJobs().get(j.id)!!
        assertEquals("torrent", row.kind)
        assertEquals("Movie", row.name)
        assertEquals(PhoneJobStatus.QUEUED, j.status)
        assertNotNull(j.torrent)
    }

    @Test fun aTorrentFileIsReadOnceAndKeptWithTheJob() = runBlocking {
        val j = engine.add(PhoneAddRequest("torrent-file:a.torrent", torrentFileUri = "content://shared/a.torrent", torrentFileName = "a.torrent"))
        assertEquals(PhoneJobStatus.QUEUED, j.status)
        assertEquals("a", j.name)
        assertTrue(File(storage.scratchDir(j.id), "source.torrent").isFile)
        val bad = engine.add(PhoneAddRequest("torrent-file:b.torrent", torrentFileUri = "content://shared/b.torrent", torrentFileName = "b.torrent"))
        assertEquals(PhoneJobStatus.FAILED, bad.status)
        assertEquals(TorrentText.NOT_A_TORRENT, bad.error)
    }

    @Test fun torrentsWaitForWifiOnMobileDataButWebFilesDoNot() = runBlocking { // QA-37
        engine.setNetworkState(wifi = false, metered = true, connected = true)
        val t = engine.add(PhoneAddRequest(magnet))
        val f = engine.add(PhoneAddRequest("https://example.com/a.zip"))
        assertEquals(PhoneJobStatus.WAITING_WIFI, job(t.id).status)
        assertEquals(PhoneJobStatus.QUEUED, job(f.id).status)
        settings.config = settings.config.copy(torrentsOnMobile = true)
        engine.setNetworkState(wifi = false, metered = true, connected = true)
        until("released") { job(t.id).status == PhoneJobStatus.QUEUED }
        settings.config = settings.config.copy(torrentsOnMobile = false)
        engine.setNetworkState(wifi = false, metered = true, connected = true)
        until("held again") { job(t.id).status == PhoneJobStatus.WAITING_WIFI }
        engine.allowMobileData(t.id) // "Download now anyway"
        until("queued") { job(t.id).status == PhoneJobStatus.QUEUED }
    }

    @Test fun aFinishedTorrentIsPublishedAndItsScratchRemovedWhenNotSeeding() = runBlocking {
        val id = engine.add(PhoneAddRequest(magnet)).id
        engine.runUntilIdle()
        val j = job(id)
        assertEquals(j.error, PhoneJobStatus.COMPLETED, j.status)
        assertEquals("Movie.bin", j.name)
        assertEquals(4096L, j.total)
        assertTrue(storage.published.containsKey("Movie.bin"))
        assertFalse(j.torrent!!.seeding)
        assertFalse(storage.scratchDir(id).exists())
        assertTrue("left the session with its files", backend.removed.contains("hash" to true))
    }

    @Test fun aFolderTorrentPublishesOnlyTheSelectedFiles() = runBlocking {
        val files = listOf("Show/a.mkv" to ByteArray(10), "Show/b.mkv" to ByteArray(20), "Show/c.srt" to ByteArray(5))
        val e = DownloadEngine(db.phoneJobs(), OkHttpClient(), storage, settings, scope, torrents = FakeTorrents(files))
        val id = e.add(PhoneAddRequest(magnet)).id
        val meta = StoredFiles.encode(files.map { TorrentFileInfo(it.first, it.second.size.toLong()) })
        db.phoneJobs().saveTorrentMeta(id, "hash", meta, "Show")
        e.setTorrentFileSelected(id, 1, false)
        assertEquals("101", db.phoneJobs().get(id)!!.fileMask)
        e.runUntilIdle()
        assertEquals(setOf("Show/a.mkv", "Show/c.srt"), storage.published.keys)
        assertEquals(15L, db.phoneJobs().get(id)!!.total)
    }

    @Test fun seedingStopsAtRatioOne() = runBlocking {
        settings.config = settings.config.copy(seedMode = SeedRules.RATIO)
        backend.uploaded = 4096 // the swarm already took one full copy
        val id = engine.add(PhoneAddRequest(magnet)).id
        engine.runUntilIdle()
        val row = db.phoneJobs().get(id)!!
        assertEquals(PhoneJobStatus.COMPLETED.name, row.status)
        assertFalse("seeding ended at ratio 1.0", row.seeding)
        assertFalse(storage.scratchDir(id).exists())
        assertTrue(backend.removed.contains("hash" to true))
    }

    @Test fun seedingStopsAfterOneHour() = runBlocking {
        settings.config = settings.config.copy(seedMode = SeedRules.HOUR)
        // Snapshots: download loop, publish, then the seeder's first look; the hour has passed by that look.
        backend.onSnapshot = { n -> if (n >= 3) clockOffset.set(SeedRules.HOUR_MS + 1) }
        val id = engine.add(PhoneAddRequest(magnet)).id
        engine.runUntilIdle()
        val row = db.phoneJobs().get(id)!!
        assertEquals(PhoneJobStatus.COMPLETED.name, row.status)
        assertFalse(row.seeding)
        assertNotNull(row.seedStartedAt)
    }

    @Test fun seedingKeepsGoingBelowTheRatioUntilStopped() = runBlocking {
        settings.config = settings.config.copy(seedMode = SeedRules.RATIO)
        backend.uploaded = 100
        val id = engine.add(PhoneAddRequest(magnet)).id
        val run = async(Dispatchers.IO) { engine.runUntilIdle() }
        until("seeding") { db.phoneJobs().get(id)?.seeding == true }
        assertNull(db.phoneJobs().get(id)!!.error)
        assertTrue("scratch kept for seeding", storage.scratchDir(id).exists())
        engine.stopSeeding(id)
        until("stopped") { db.phoneJobs().get(id)?.seeding == false }
        run.await()
        assertFalse(storage.scratchDir(id).exists())
    }

    @Test fun pauseSavesResumeDataAndResumeStartsFromIt() = runBlocking { // a restart does not re-check from zero
        backend.finished = false
        val id = engine.add(PhoneAddRequest(magnet)).id
        val run = async(Dispatchers.IO) { engine.runUntilIdle() }
        until("downloading") { db.phoneJobs().get(id)?.status == "DOWNLOADING" && backend.adds.isNotEmpty() }
        engine.pause(id)
        assertEquals("PAUSED", db.phoneJobs().get(id)!!.status)
        assertTrue(File(storage.scratchDir(id), "resume.dat").isFile)
        assertTrue("the session let go of it", backend.removed.contains("hash" to false))
        run.cancel()
        engine.resume(id)
        backend.finished = true
        engine.runUntilIdle()
        assertEquals("resume-v1", String(backend.resumeSeen.last()!!))
        assertEquals(PhoneJobStatus.COMPLETED, job(id).status)
    }

    @Test fun cancelRemovesThePartialFiles() = runBlocking {
        backend.finished = false
        val id = engine.add(PhoneAddRequest(magnet)).id
        val run = async(Dispatchers.IO) { engine.runUntilIdle() }
        until("downloading") { db.phoneJobs().get(id)?.status == "DOWNLOADING" && backend.adds.isNotEmpty() }
        engine.cancel(id)
        run.cancel()
        assertEquals("CANCELLED", db.phoneJobs().get(id)!!.status)
        assertFalse(storage.scratchDir(id).exists())
        assertTrue(storage.published.isEmpty())
    }
}
