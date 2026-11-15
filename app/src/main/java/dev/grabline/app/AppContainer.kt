package dev.grabline.app

import android.content.Context
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.grabline.app.data.AppDatabase
import dev.grabline.app.data.AppSettings
import dev.grabline.app.data.PairedPc
import dev.grabline.app.notify.PcActivityNotifier
import dev.grabline.app.remote.DefaultPcRemote
import dev.grabline.app.remote.PcRemote
import dev.grabline.app.remote.connection.AuthProvider
import dev.grabline.app.remote.connection.ConnectionManager
import dev.grabline.app.remote.connection.OkHttpWireFactory
import dev.grabline.app.remote.outbox.Outbox
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
import okhttp3.OkHttpClient

/** Fallback when the PC gives no reason (PROTOCOL.md 16 `failed` default). */
private const val DEFAULT_FAILED = "Your PC couldn't do that. Try again"

/** Builds the app's long-lived objects once (no DI framework needed for this size). */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val db: AppDatabase = AppDatabase.get(app)
    val settings = AppSettings(app)
    private val keyStore = AndroidDeviceKeyStore()
    private val baseClient = OkHttpClient.Builder().build()
    private val appVersion = BuildConfig.VERSION_NAME

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
        onPairingRemoved = { pcId -> runCatching { keyStore.delete(pcId) } },
    )
    val outbox = Outbox(scope, db.outbox(), connections)
    private val defaultRemote = DefaultPcRemote(db, connections, outbox, deleteKey = { keyStore.delete(it) })
    val remote: PcRemote = defaultRemote
    val pairing: PairingController = KeystorePairingController(
        client = PairingClient(OkHttpTransport(baseClient), keyStore, deviceName(), appVersion),
        remote = remote,
        lastGood = { pcId -> pcId?.let { db.pcs().get(it)?.lastGoodAddress } },
    )
    private val notifier = PcActivityNotifier(app) { pcNotificationsOn }
    @Volatile private var pcNotificationsOn = true

    fun start() {
        outbox.start()
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
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = remote.setForeground(true)
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
                    ),
                )
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
