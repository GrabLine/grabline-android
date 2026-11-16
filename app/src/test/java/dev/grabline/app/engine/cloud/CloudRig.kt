package dev.grabline.app.engine.cloud

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.grabline.app.data.AppDatabase
import dev.grabline.app.engine.DownloadEngine
import dev.grabline.app.engine.EngineConfig
import dev.grabline.app.engine.FakeSettings
import dev.grabline.app.engine.FakeStorage
import dev.grabline.app.engine.PhoneAddRequest
import dev.grabline.app.engine.PhoneJob
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient

class MemHostKeys : HostKeyStore {
    val keys = ConcurrentHashMap<String, StoredHostKey>()
    override fun find(host: String, port: Int) = keys["$host:$port"]
    override fun put(host: String, port: Int, keyType: String, sha256: String) { keys["$host:$port"] = StoredHostKey(keyType, sha256) }
}

/** The real engine with a real cloud backend, an in-memory Room, fake storage and a cipher with a software AES key. */
class CloudRig(
    config: EngineConfig = EngineConfig(onlyWifi = false, autoRetry = 0),
    backend: ((CredentialStore, MemHostKeys, OkHttpClient) -> CloudBackend)? = null,
) {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
    val dao = db.phoneJobs()
    private val dir = File(ctx.cacheDir, "cloud-rig-${System.nanoTime()}").apply { mkdirs() }
    val storage = FakeStorage(dir)
    val hostKeys = MemHostKeys()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    val store = CredentialStore(db.cloudAccounts(), AesGcmSecretCipher { key })
    private val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val engine = DownloadEngine(
        dao, client, storage, FakeSettings(config), scope, backoffBaseMs = 5,
        cloud = backend?.invoke(store, hostKeys, client) ?: DefaultCloudBackend(store, hostKeys, client),
    )

    suspend fun job(id: Long): PhoneJob = engine.jobs.first().first { it.id == id }

    /** Adds a link, runs the queue dry and returns the job as the UI sees it. */
    suspend fun download(url: String, afterAdd: suspend (PhoneJob) -> Unit = {}): PhoneJob {
        val added = engine.add(PhoneAddRequest(url))
        afterAdd(added)
        engine.runUntilIdle()
        return job(added.id)
    }

    fun close() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        db.close()
    }
}

fun cloudRig(
    config: EngineConfig = EngineConfig(onlyWifi = false, autoRetry = 0),
    backend: ((CredentialStore, MemHostKeys, OkHttpClient) -> CloudBackend)? = null,
    block: suspend CloudRig.() -> Unit,
) {
    val r = CloudRig(config, backend)
    try {
        runBlocking { withTimeout(120_000) { r.block() } }
    } finally {
        r.close()
    }
}
