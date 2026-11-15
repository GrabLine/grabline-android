package dev.grabline.app.remote.connection

import dev.grabline.app.data.PairedPc
import dev.grabline.app.data.PairedPcDao
import dev.grabline.app.data.PcJob
import dev.grabline.app.data.PcJobDao
import dev.grabline.app.remote.protocol.AuthOkReply
import dev.grabline.app.remote.protocol.ErrorCode
import dev.grabline.app.remote.protocol.GlobalState
import dev.grabline.app.remote.protocol.GlobalUpdatedEvent
import dev.grabline.app.remote.protocol.Inbound
import dev.grabline.app.remote.protocol.Job
import dev.grabline.app.remote.protocol.JobAddedEvent
import dev.grabline.app.remote.protocol.JobFailedEvent
import dev.grabline.app.remote.protocol.JobFinishedEvent
import dev.grabline.app.remote.protocol.JobProgressEvent
import dev.grabline.app.remote.protocol.JobRemovedEvent
import dev.grabline.app.remote.protocol.JobUpdatedEvent
import dev.grabline.app.remote.protocol.JobsReply
import dev.grabline.app.remote.protocol.JobsRequest
import dev.grabline.app.remote.protocol.Request
import dev.grabline.app.remote.protocol.ServerEvent
import dev.grabline.app.remote.protocol.SubscribeRequest
import dev.grabline.app.remote.protocol.SubscribedReply
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** What the app shows for one paired PC. */
sealed interface PcLinkState {
    data object Connecting : PcLinkState
    data class Online(val pcName: String, val appVersion: String, val features: Set<String>, val address: String) : PcLinkState
    /** "Your PC is off or on another network"; rows greyed with "Last seen". */
    data class Offline(val lastSeenAt: Long?) : PcLinkState
    /** "Update GrabLine on your PC" (QA-17). */
    data object PcTooOld : PcLinkState
    /** "Update GrabLine on this phone". */
    data object PhoneTooOld : PcLinkState
    /** "Your PC's identity changed. If you reinstalled GrabLine, pair again" (QA-07). */
    data object IdentityChanged : PcLinkState
    /** "This PC no longer accepts this phone. Pair again to reconnect" (QA-06). */
    data object PairingRemoved : PcLinkState
}

/** Extra addresses to try when the known ones fail (PROTOCOL.md 11 step 3). */
fun interface AddressSweeper {
    suspend fun candidates(knownAddresses: List<String>): List<String>
}

/**
 * Keeps one live session per paired PC while the app wants it (foreground,
 * or links waiting in the outbox), and mirrors each PC's jobs into Room.
 *
 * Never polls in the background: when nothing wants a connection, every
 * session is closed (spec: no measurable idle drain).
 */
class ConnectionManager(
    private val scope: CoroutineScope,
    private val pcs: PairedPcDao,
    private val jobs: PcJobDao,
    private val factory: WireFactory,
    private val auth: AuthProvider,
    private val sweeper: AddressSweeper? = null,
    /** Called when a PC removed this phone: delete its key (remote/security). The row stays, marked removed. */
    private val onPairingRemoved: suspend (pc: PairedPc) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _states = MutableStateFlow<Map<String, PcLinkState>>(emptyMap())
    val states: StateFlow<Map<String, PcLinkState>> = _states.asStateFlow()

    private val _global = MutableStateFlow<Map<String, GlobalState>>(emptyMap())
    val globalStates: StateFlow<Map<String, GlobalState>> = _global.asStateFlow()

    private val _events = MutableSharedFlow<Pair<String, ServerEvent>>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Every event from every PC, tagged with its pcId (notifications listen here). */
    val events: SharedFlow<Pair<String, ServerEvent>> = _events

    private val sessions = ConcurrentHashMap<String, PcSession>()
    private val loops = ConcurrentHashMap<String, CoroutineJob>()
    private val wakeups = ConcurrentHashMap<String, MutableSharedFlow<Unit>>()
    private val lastSweep = ConcurrentHashMap<String, Long>()
    private val lock = Mutex()
    @Volatile private var wanted = false
    private var watcher: CoroutineJob? = null

    /** Start or stop wanting connections (app foreground / outbox not empty). */
    fun setWanted(value: Boolean) {
        if (wanted == value) return
        wanted = value
        if (value) {
            watcher = scope.launch { pcs.observeAll().collectLatest { syncLoops(it) } }
        } else {
            watcher?.cancel()
            watcher = null
            loops.values.forEach { it.cancel() }
            loops.clear()
            sessions.values.forEach { it.close() }
            sessions.clear()
            _states.update { current ->
                current.mapValues { (_, s) -> if (s is PcLinkState.Online || s is PcLinkState.Connecting) PcLinkState.Offline(clock()) else s }
            }
        }
    }

    /** Ask for an immediate attempt (pull to refresh, Retry, a new outbox item). */
    fun retryNow(pcId: String? = null) {
        if (pcId == null) wakeups.values.forEach { it.tryEmit(Unit) } else wakeups[pcId]?.tryEmit(Unit)
    }

    /** True when this PC said it no longer accepts this phone (row marked removed, or just learned in this run). */
    suspend fun isRemoved(pcId: String): Boolean =
        _states.value[pcId] == PcLinkState.PairingRemoved || pcs.get(pcId)?.removedAt != null

    fun isOnline(pcId: String): Boolean = sessions[pcId]?.isEnded == false && _states.value[pcId] is PcLinkState.Online

    /** Send one request to a PC. Throws [RemoteException] (code INTERNAL "offline" when not connected). */
    suspend fun request(pcId: String, build: (String) -> Request): Inbound.Reply {
        val session = sessions[pcId]?.takeIf { !it.isEnded }
            ?: throw RemoteException(ErrorCode.INTERNAL, message = "offline")
        return session.request(build)
    }

    /** The PC was unpaired or removed locally: stop its loop. */
    fun forget(pcId: String) {
        loops.remove(pcId)?.cancel()
        sessions.remove(pcId)?.close()
        _states.update { it - pcId }
        _global.update { it - pcId }
    }

    // ------------------------------------------------------------------ loops

    private suspend fun syncLoops(list: List<PairedPc>) = lock.withLock {
        val ids = list.map { it.pcId }.toSet()
        (loops.keys - ids).forEach { forget(it) }
        for (pc in list) {
            if (pc.removedAt != null) {
                // The PC removed this phone: keep the row, show "Pair again", never connect.
                loops.remove(pc.pcId)?.cancel()
                sessions.remove(pc.pcId)?.close()
                _global.update { it - pc.pcId }
                setState(pc.pcId, PcLinkState.PairingRemoved)
                continue
            }
            if (loops[pc.pcId]?.isActive == true) continue
            val wake = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            wakeups[pc.pcId] = wake
            loops[pc.pcId] = scope.launch { runLoop(pc.pcId, wake) }
        }
    }

    private suspend fun runLoop(pcId: String, wake: MutableSharedFlow<Unit>) {
        var failures = 0
        while (wanted) {
            val pc = pcs.get(pcId) ?: return
            if (pc.removedAt != null) { setState(pcId, PcLinkState.PairingRemoved); return }
            setState(pcId, PcLinkState.Connecting)
            val session = connect(pc)
            if (session == null) {
                if (terminal(pcId)) return
                setState(pcId, PcLinkState.Offline(pc.lastSeenAt))
                failures++
                waitOrWake(wake, backoff(failures))
                continue
            }
            failures = 0
            val end = runSession(pc, session)
            sessions.remove(pcId, session)
            when (end) {
                is SessionEnd.PairingRemoved -> { pairingRemoved(pc); return }
                is SessionEnd.IdentityChanged -> { setState(pcId, PcLinkState.IdentityChanged); return }
                is SessionEnd.PcTooOld -> { setState(pcId, PcLinkState.PcTooOld); return }
                is SessionEnd.PhoneTooOld -> { setState(pcId, PcLinkState.PhoneTooOld); return }
                is SessionEnd.Closed -> {
                    setState(pcId, PcLinkState.Offline(clock()))
                    waitOrWake(wake, maxOf(end.retryAfterMs, 500L))
                }
                is SessionEnd.Unreachable -> {
                    setState(pcId, PcLinkState.Offline(clock()))
                    waitOrWake(wake, 1_000L)
                }
            }
        }
    }

    private fun terminal(pcId: String): Boolean = when (_states.value[pcId]) {
        PcLinkState.PairingRemoved, PcLinkState.IdentityChanged, PcLinkState.PcTooOld, PcLinkState.PhoneTooOld -> true
        else -> false
    }

    private fun backoff(failures: Int): Long {
        val base = (1_000L shl (failures - 1).coerceIn(0, 5)).coerceAtMost(30_000L)
        return base + Random.nextLong(0, base / 4 + 1)
    }

    private suspend fun waitOrWake(wake: SharedFlow<Unit>, millis: Long) {
        withTimeoutOrNull(millis) { wake.first() }
    }

    // -------------------------------------------------------------- connecting

    /**
     * PROTOCOL.md section 11: last good first, then the others in parallel, then a sweep of the Wi-Fi subnet.
     * A different key at the last good address is remembered and becomes [PcLinkState.IdentityChanged] only when
     * no other candidate connected; a different key at any other address (a swept stranger) is just a miss.
     */
    private suspend fun connect(pc: PairedPc): PcSession? {
        val known = (listOfNotNull(pc.lastGoodAddress) + pc.addresses).distinct()
        var lastGoodMismatch = false
        known.firstOrNull()?.let { first ->
            val isLastGood = pc.lastGoodAddress == first
            tryOne(pc, first, onMismatch = { if (isLastGood) lastGoodMismatch = true })?.let { return it }
        }
        if (terminal(pc.pcId)) return null
        tryMany(pc, known.drop(1))?.let { return it }
        if (terminal(pc.pcId)) return null
        val now = clock()
        val sweepDue = sweeper != null && now - (lastSweep[pc.pcId] ?: (Long.MIN_VALUE / 2)) >= SWEEP_INTERVAL_MS
        if (sweepDue) {
            lastSweep[pc.pcId] = now
            val swept = sweeper?.candidates(known)?.filterNot { it in known }.orEmpty()
            swept.chunked(32).firstNotNullOfOrNull { batch -> tryMany(pc, batch, timeoutMs = 400) }?.let { return it }
            if (terminal(pc.pcId)) return null
        }
        if (lastGoodMismatch) setState(pc.pcId, PcLinkState.IdentityChanged)
        return null
    }

    private suspend fun tryMany(pc: PairedPc, addresses: List<String>, timeoutMs: Long = 2_000): PcSession? {
        if (addresses.isEmpty()) return null
        return coroutineScope {
            val attempts = addresses.map { address -> async { tryOne(pc, address, timeoutMs) } }
            val results = attempts.awaitAll()
            val winner = results.firstOrNull { it != null }
            results.filterNotNull().filter { it !== winner }.forEach { it.close() }
            winner
        }
    }

    private suspend fun tryOne(
        pc: PairedPc,
        address: String,
        timeoutMs: Long = 2_000,
        onMismatch: () -> Unit = {},
    ): PcSession? {
        val session = PcSession(pc, address, factory, auth)
        val result = session.open(timeoutMs)
        val ok = result.getOrNull()
        if (ok != null) {
            onAuthenticated(pc, session, ok)
            return session
        }
        when ((result.exceptionOrNull() as? PcSession.EndException)?.end) {
            SessionEnd.PairingRemoved -> pairingRemoved(pc)
            SessionEnd.IdentityChanged -> onMismatch()
            SessionEnd.PcTooOld -> setState(pc.pcId, PcLinkState.PcTooOld)
            SessionEnd.PhoneTooOld -> setState(pc.pcId, PcLinkState.PhoneTooOld)
            else -> Unit
        }
        return null
    }

    private suspend fun onAuthenticated(pc: PairedPc, session: PcSession, ok: AuthOkReply) {
        sessions.put(pc.pcId, session)?.takeIf { it !== session }?.close()
        val now = clock()
        // Merge into the current row, not the snapshot this attempt started from (a re-pair may have replaced it).
        val current = pcs.get(pc.pcId) ?: return
        pcs.upsert(
            current.copy(
                name = ok.pcName.ifBlank { current.name },
                addresses = ok.addresses.ifEmpty { current.addresses },
                port = ok.port.takeIf { it > 0 } ?: current.port,
                lastGoodAddress = session.address,
                lastSeenAt = now,
                pcAppVersion = ok.appVersion,
            ),
        )
        setState(pc.pcId, PcLinkState.Online(ok.pcName.ifBlank { pc.name }, ok.appVersion.orEmpty(), ok.features.toSet(), session.address))
    }

    // ------------------------------------------------------- live session work

    private suspend fun runSession(pc: PairedPc, session: PcSession): SessionEnd = coroutineScope {
        val cache = ConcurrentHashMap<Long, PcJob>()
        var lastSeq = 0L
        val collector = launch {
            session.events.collect { event ->
                val seq = seqOf(event)
                if (seq != null) {
                    if (lastSeq != 0L && seq != lastSeq + 1) launch { reload(pc.pcId, session, cache) }
                    lastSeq = seq
                }
                apply(pc.pcId, event, cache)
                _events.tryEmit(pc.pcId to event)
            }
        }
        try {
            val subscribed = session.request { id -> SubscribeRequest(id, listOf("jobs", "global")) }
            (subscribed.message as? SubscribedReply)?.state?.let { state -> _global.update { it + (pc.pcId to state) } }
            reload(pc.pcId, session, cache)
        } catch (_: RemoteException) {
            // The session ends below if the connection dropped; otherwise carry on with events.
        }
        val end = session.awaitEnd()
        collector.cancel()
        pcs.touchSeen(pc.pcId, clock())
        end
    }

    /** Page through `jobs` and replace this PC's cached rows (snapshot after subscribe). */
    private suspend fun reload(pcId: String, session: PcSession, cache: ConcurrentHashMap<Long, PcJob>) {
        val all = mutableListOf<PcJob>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = session.request { id -> JobsRequest(id, limit = 100, cursor = cursor) }.message as? JobsReply ?: break
            all += page.jobs.map { it.toRow(pcId) }
            cursor = page.nextCursor
            pages++
        } while (cursor != null && pages < 50)
        cache.clear()
        all.forEach { cache[it.jobId] = it }
        jobs.replaceAllForPc(pcId, all)
    }

    private suspend fun apply(pcId: String, event: ServerEvent, cache: ConcurrentHashMap<Long, PcJob>) {
        when (event) {
            is JobAddedEvent -> upsert(pcId, event.job, cache)
            is JobUpdatedEvent -> upsert(pcId, event.job, cache)
            is JobFinishedEvent -> upsert(pcId, event.job, cache)
            is JobFailedEvent -> upsert(pcId, event.job, cache)
            is JobRemovedEvent -> {
                cache.remove(event.jobId)
                jobs.delete(pcId, event.jobId)
            }
            is JobProgressEvent -> {
                val row = cache[event.jobId] ?: return
                val updated = row.copy(
                    status = event.status,
                    downloaded = event.downloaded,
                    total = event.total ?: row.total,
                    speed = event.speed,
                    etaSeconds = event.eta,
                    updatedAt = clock(),
                )
                cache[event.jobId] = updated
                jobs.upsertAll(listOf(updated))
            }
            is GlobalUpdatedEvent -> _global.update { it + (pcId to event.state) }
            else -> Unit
        }
    }

    private suspend fun upsert(pcId: String, job: Job, cache: ConcurrentHashMap<Long, PcJob>) {
        val row = job.toRow(pcId)
        cache[job.id] = row
        jobs.upsertAll(listOf(row))
    }

    private fun Job.toRow(pcId: String) = PcJob(
        pcId = pcId,
        jobId = id,
        url = url,
        name = name,
        status = status,
        downloaded = downloaded,
        total = total,
        speed = speed,
        etaSeconds = eta,
        queueId = queueId?.toInt(),
        createdAt = createdAt,
        finishedAt = finishedAt,
        error = error,
        updatedAt = clock(),
    )

    private fun seqOf(event: ServerEvent): Long? = when (event) {
        is JobAddedEvent -> event.seq
        is JobUpdatedEvent -> event.seq
        is JobProgressEvent -> event.seq
        is JobFinishedEvent -> event.seq
        is JobFailedEvent -> event.seq
        is JobRemovedEvent -> event.seq
        is GlobalUpdatedEvent -> event.seq
        else -> null
    }

    /**
     * The PC refused this phone for good (QA-06): show "Pair again", drop the key and cached jobs, and keep the row
     * marked removed so the state survives a restart. Ignored when a re-pair already replaced the pairing this
     * session was opened for (its old device record being revoked is expected).
     */
    private suspend fun pairingRemoved(pc: PairedPc) {
        val current = pcs.get(pc.pcId) ?: return
        if (current.deviceId != pc.deviceId || current.keyAlias != pc.keyAlias) return
        setState(pc.pcId, PcLinkState.PairingRemoved)
        sessions.remove(pc.pcId)?.close()
        runCatching { onPairingRemoved(current) }
        jobs.deleteForPc(pc.pcId)
        pcs.markRemoved(pc.pcId, clock())
    }

    private fun setState(pcId: String, state: PcLinkState) {
        _states.update { it + (pcId to state) }
    }

    private companion object {
        const val SWEEP_INTERVAL_MS = 60_000L
    }
}
