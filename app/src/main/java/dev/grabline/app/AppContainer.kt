package dev.grabline.app

import android.content.Context
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.grabline.app.data.AppDatabase
import dev.grabline.app.data.AppSettings
import dev.grabline.app.data.PairedPc
import dev.grabline.app.engine.AndroidPhoneStorage
import dev.grabline.app.engine.AppEngineSettings
import dev.grabline.app.engine.DownloadEngine
import dev.grabline.app.engine.torrent.TorrentEngine
import dev.grabline.app.engine.video.YoutubeDlVideoEngine
import androidx.lifecycle.Lifecycle
import dev.grabline.app.engine.work.BatteryTip
import dev.grabline.app.engine.work.ChargingMonitor
import dev.grabline.app.engine.work.DownloadScheduler
import dev.grabline.app.engine.work.NetworkMonitor
import dev.grabline.app.notify.PcActivityNotifier
import dev.grabline.app.notify.PhoneJobNotifier
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import dev.grabline.app.widget.ActiveDownloads
import dev.grabline.app.widget.ActiveItem
import dev.grabline.app.widget.WidgetRenderer
import dev.grabline.app.widget.WidgetState
import dev.grabline.app.remote.DefaultPcRemote
import dev.grabline.app.remote.PcRemote
import dev.grabline.app.remote.connection.AuthProvider
import dev.grabline.app.remote.connection.ConnectionManager
import dev.grabline.app.remote.connection.OkHttpWireFactory
import dev.grabline.app.remote.connection.WifiSubnetSweeper
import dev.grabline.app.remote.outbox.Outbox
import dev.grabline.app.remote.connection.TransferConnector
import dev.grabline.app.remote.transfer.FetchClient
import dev.grabline.app.remote.transfer.PcOutboxPuller
import dev.grabline.app.remote.transfer.SendToPcLink
import dev.grabline.app.engine.PhoneAddRequest
import dev.grabline.app.remote.pairing.OkHttpTransport
import dev.grabline.app.remote.pairing.PairResult
import dev.grabline.app.remote.pairing.PairingClient
import dev.grabline.app.remote.protocol.ErrorMessages
import dev.grabline.app.remote.protocol.JobFailedEvent
import dev.grabline.app.remote.protocol.JobFinishedEvent
import dev.grabline.app.remote.protocol.ManualPairing
import dev.grabline.app.remote.protocol.ManualPairingResult
import dev.grabline.app.remote.protocol.PairingUri
import dev.grabline.app.remote.protocol.PairingUriResult
import dev.grabline.app.remote.security.AndroidDeviceKeyStore
import dev.grabline.app.remote.security.Authenticator
import dev.grabline.app.remote.security.PinMismatchException
import dev.grabline.app.remote.security.pinnedOkHttpClient
import dev.grabline.app.ui.devices.PairingController
import dev.grabline.app.ui.devices.PairingEntry
import dev.grabline.app.ui.devices.PairingProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Fallback when the PC gives no reason (PROTOCOL.md 16 `failed` default). */
private const val DEFAULT_FAILED = "Your PC couldn't do that. Try again"

/** Builds the app's long-lived objects once (no DI framework needed for this size). */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val db: AppDatabase = AppDatabase.get(app)
    val settings = AppSettings(app)
    // StrongBox when the phone has it; AndroidDeviceKeyStore falls back to the TEE on StrongBoxUnavailableException.
    private val keyStore = AndroidDeviceKeyStore(preferStrongBox = true)
    private val baseClient = OkHttpClient.Builder().build()
    private val appVersion = BuildConfig.VERSION_NAME
    // Downloads read slowly from some servers: 60 s read timeout for the engine (the shared client keeps 10 s).
    private val engineClient = baseClient.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
    val video = YoutubeDlVideoEngine(app)
    val phone: DownloadEngine = DownloadEngine(
        db.phoneJobs(), engineClient, AndroidPhoneStorage(app), AppEngineSettings(settings), scope, video = video,
        transfers = FetchClient(TransferConnector { pcId -> connections.openTransfer(pcId) }),
        torrents = TorrentEngine(),
        torrentFiles = { uri -> readTorrentFile(app, uri) },
    )
    val batteryTip = BatteryTip.create(app)
    /** Active downloads across the phone and every reachable PC, for the widget and the Quick Settings tile. */
    val activeDownloads: StateFlow<List<ActiveItem>> by lazy {
        ActiveDownloads.flow(phone, remote).stateIn(scope, SharingStarted.Eagerly, emptyList())
    }
    val scheduler = DownloadScheduler(app) { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
    private val phoneNotifier = PhoneJobNotifier(app)

    val connections = ConnectionManager(
        scope = scope,
        pcs = db.pcs(),
        jobs = db.jobs(),
        factory = OkHttpWireFactory(
            clientForPin = { pin -> pinnedOkHttpClient(baseClient, pin) },
            isPinMismatch = { PinMismatchException.isCauseOf(it) },
        ),
        auth = run {
            val authenticator = Authenticator(keyStore, appVersion)
            AuthProvider { pc, hello, id -> authenticator.authRequest(pc, hello, id) }
        },
        sweeper = WifiSubnetSweeper(app),
        onPairingRemoved = { pc -> runCatching { keyStore.delete(Authenticator.keyAliasOf(pc)) } },
    )
    val outbox = Outbox(scope, db.outbox(), connections)
    private val sendLink = SendToPcLink(db.phoneJobs(), { requestId -> db.outbox().countByRequestId(requestId) > 0 }) { phoneJobId -> phone.cancel(phoneJobId) }
    /** The folder new phone jobs from a PC land in: a picked tree, or null for Downloads/GrabLine. */
    suspend fun saveFolderUri(): String? = settings.observe(AppSettings.SAVE_FOLDER).first().takeIf { it.startsWith("content:") }
    private val pcOutbox = PcOutboxPuller(
        scope, connections,
        held = { pcId, itemId -> db.phoneJobs().byOutboxItem(pcId, itemId) != null },
        save = { pcId, item ->
            phone.add(
                PhoneAddRequest(
                    url = item.url, title = item.title, folderUri = saveFolderUri(), quality = item.quality,
                    pcId = pcId, outboxItemId = item.itemId,
                ),
            )
        },
    )
    private val defaultRemote = DefaultPcRemote(db, connections, outbox, deleteKey = { pc -> keyStore.delete(Authenticator.keyAliasOf(pc)) })
    val remote: PcRemote = defaultRemote
    val pairing: PairingController = KeystorePairingController(
        client = PairingClient(OkHttpTransport(baseClient), keyStore, deviceName(), appVersion),
        remote = remote,
        lastGood = { pcId -> pcId?.let { db.pcs().get(it)?.lastGoodAddress } },
        previous = { pcId -> db.pcs().get(pcId) },
        deleteKey = { alias -> keyStore.delete(alias) },
    )
    private val notifier = PcActivityNotifier(app) { pcNotificationsOn }
    @Volatile private var pcNotificationsOn = true

    fun start() {
        outbox.deliveredListener = sendLink::onDelivered
        // QA-38: a "Send to PC" the PC already took, whose phone job survived a process death, is cancelled now.
        scope.launch { runCatching { sendLink.recover() } }
        pcOutbox.start()
        outbox.start()
        // yt-dlp, Python and FFmpeg unpack once, off the main thread; video downloads wait for it.
        scope.launch { runCatching { video.ensureReady() } }
        scope.launch { outbox.pendingCount.collect { defaultRemote.onPendingCount(it) } }
        scope.launch { settings.observe(AppSettings.NOTIFY_PC_DOWNLOADS).collect { pcNotificationsOn = it } }
        // PC activity notifications: only while connected (no push service).
        scope.launch {
            connections.events.collect { (pcId, event) ->
                val pc = db.pcs().get(pcId) ?: return@collect
                if (!pc.notifyActivity) return@collect
                when (event) {
                    is JobFinishedEvent -> notifier.jobFinished(pcId, pc.name, event.job.id.toString(), event.job.name)
                    is JobFailedEvent -> notifier.jobFailed(
                        pcId, pc.name, event.job.id.toString(), event.job.name, event.job.error ?: DEFAULT_FAILED,
                    )
                    else -> Unit
                }
            }
        }
        // Phone downloads: constraints in, scheduling out, finished/failed notifications.
        NetworkMonitor(app, phone).start()
        ChargingMonitor(app, phone).start()
        scope.launch { phone.hasRunnableWork().collect { if (it) scheduler.ensureRunning() } }
        scope.launch { phone.jobs.collect { phoneNotifier.onJobs(it) } }
        // "Start later": wake the app when the earliest waiting job is due.
        scope.launch { phone.nextStartAt().collect { at -> if (at != null) scheduler.scheduleAt(at) } }
        // Home-screen widget: at most every 30 s while the app is closed, closely while it is open.
        scope.launch {
            activeDownloads.collect { items ->
                WidgetState.items = items
                WidgetRenderer.push(app, items)
                delay(ActiveDownloads.updateIntervalMs(ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)))
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                remote.setForeground(true)
                // Back on screen with a silent job running: upgrade it to the visible kind.
                scope.launch { if (phone.hasRunnableWork().first()) scheduler.ensureRunning() }
            }
            override fun onStop(owner: LifecycleOwner) = remote.setForeground(false)
        })
    }

    private fun deviceName(): String =
        listOfNotNull(Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }, Build.MODEL)
            .distinct().joinToString(" ").ifBlank { "Android phone" }.take(64)
}

/** Adapts the Devices screen's [PairingController] to [PairingClient]. */
class KeystorePairingController(
    private val client: PairingClient,
    private val remote: PcRemote,
    private val lastGood: suspend (String?) -> String?,
    /** The stored pairing for a PC, to retire its key after a re-pair. */
    private val previous: suspend (String) -> PairedPc? = { null },
    /** Deletes a device key by alias. */
    private val deleteKey: suspend (alias: String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : PairingController {
    override fun pair(entry: PairingEntry, deviceName: String): Flow<PairingProgress> = callbackFlow {
        trySend(PairingProgress.Connecting)
        val progress: (dev.grabline.app.remote.pairing.PairingProgress) -> Unit = { step ->
            if (step is dev.grabline.app.remote.pairing.PairingProgress.ConfirmOnPc) trySend(PairingProgress.ConfirmOnPc)
        }
        val result = when (entry) {
            is PairingEntry.Scanned -> when (val parsed = PairingUri.parse(entry.text)) {
                is PairingUriResult.Valid -> client.pair(parsed.data, lastGood(parsed.data.pcId), progress)
                is PairingUriResult.Invalid -> null
            }
            is PairingEntry.Manual -> when (val parsed = ManualPairing.parse(entry.text)) {
                is ManualPairingResult.Valid -> client.pair(parsed.data, progress)
                is ManualPairingResult.Invalid -> null
            }
        }
        when (result) {
            null -> trySend(PairingProgress.Failed(ErrorMessages.NOT_A_PAIRING_CODE, canRetry = false))
            is PairResult.Paired -> {
                val old = previous(result.pcId)
                remote.addPairing(
                    PairedPc(
                        pcId = result.pcId,
                        name = result.pcName,
                        fingerprint = result.pin,
                        deviceId = result.deviceId,
                        addresses = result.addresses,
                        port = result.port,
                        lastGoodAddress = result.address,
                        protocolVersion = result.protocolVersion,
                        pairedAt = clock(),
                        lastSeenAt = clock(),
                        keyAlias = result.keyAlias,
                        removedAt = null, // a fresh pairing clears "this PC removed the phone"
                    ),
                )
                // The new row is saved: retire the key of the pairing it replaced.
                old?.let { Authenticator.keyAliasOf(it) }?.takeIf { it != result.keyAlias }?.let { runCatching { deleteKey(it) } }
                // The connection manager logs in on its own connection.
                runCatching { result.connection.close() }
                trySend(PairingProgress.Paired(result.pcName))
            }
            else -> trySend(PairingProgress.Failed(result.userMessage ?: DEFAULT_FAILED, canRetry(result)))
        }
        close()
        awaitClose { }
    }

    private fun canRetry(result: PairResult): Boolean = when (result) {
        is PairResult.BadCode, PairResult.PcNotFound, PairResult.TimedOut, PairResult.ConnectionLost -> true
        else -> false
    }
}

private const val MAX_TORRENT_FILE_BYTES = 8 * 1024 * 1024

/** The bytes of a .torrent file the app was handed (content:// or file://); null when unreadable or too large. */
private suspend fun readTorrentFile(context: Context, uri: String): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_TORRENT_FILE_BYTES) return@runCatching null
            }
            out.toByteArray()
        }
    }.getOrNull()
}
