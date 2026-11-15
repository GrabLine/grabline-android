package dev.grabline.app.ui.downloads

import dev.grabline.app.engine.PhoneEngine
import dev.grabline.app.remote.PcRemote
import dev.grabline.app.remote.RemoteFailure
import dev.grabline.app.remote.protocol.ErrorCode
import dev.grabline.app.remote.protocol.ErrorMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [pcId] is null for a phone job. */
data class Target(val pcId: String?, val jobId: Long, val name: String) {
    val key: String get() = if (pcId == null) phoneRowKey(jobId) else rowKey(pcId, jobId)
}

/** An undoable action that has been shown as done but not yet sent. */
data class PendingAction(val id: Long, val action: RowAction, val targets: List<Target>)

const val UNDO_MILLIS = 5_000L

/**
 * Sends control requests. Pause, resume and retry go out at once. Cancel and
 * remove are shown as done at once and sent only when the Undo window closes
 * without Undo (SPEC: the partial file is deleted only then).
 */
class PendingControls(
    private val remote: PcRemote,
    private val scope: CoroutineScope,
    private val undoMillis: Long = UNDO_MILLIS,
    private val phone: PhoneEngine? = null,
    private val onFailure: (String) -> Unit,
) {
    private val _pending = MutableStateFlow<List<PendingAction>>(emptyList())
    val pending: StateFlow<List<PendingAction>> = _pending.asStateFlow()

    private val timers = HashMap<Long, Job>()
    private var nextId = 1L

    /** Returns the pending id for undoable actions, null for immediate ones. */
    fun request(action: RowAction, targets: List<Target>): Long? {
        if (targets.isEmpty()) return null
        if (!action.undoable) {
            scope.launch { targets.forEach { send(it, action) } }
            return null
        }
        val id = nextId++
        val p = PendingAction(id, action, targets)
        _pending.update { it + p }
        timers[id] = scope.launch {
            delay(undoMillis)
            commit(p)
        }
        return id
    }

    fun undo(id: Long) {
        timers.remove(id)?.cancel() ?: return
        _pending.update { list -> list.filterNot { it.id == id } }
    }

    /** Send everything waiting now (the screen is going away, so the Undo is gone). */
    fun flush(flushScope: CoroutineScope) {
        val waiting = _pending.value
        timers.values.forEach { it.cancel() }
        timers.clear()
        waiting.forEach { p -> flushScope.launch { p.targets.forEach { send(it, p.action) } } }
    }

    private suspend fun commit(p: PendingAction) {
        timers.remove(p.id)
        p.targets.forEach { send(it, p.action) }
        _pending.update { list -> list.filterNot { it.id == p.id } }
    }

    private suspend fun send(t: Target, action: RowAction) {
        if (t.pcId == null) return sendPhone(t, action)
        val result = remote.control(t.pcId, t.jobId, action.wire)
        result.exceptionOrNull()?.let { e ->
            onFailure((e as? RemoteFailure)?.userMessage ?: ErrorMessages.forCode(ErrorCode.INTERNAL))
        }
    }

    private suspend fun sendPhone(t: Target, action: RowAction) {
        val engine = phone ?: return
        try {
            when (action) {
                RowAction.Pause -> engine.pause(t.jobId)
                RowAction.Resume -> engine.resume(t.jobId)
                RowAction.Cancel -> engine.cancel(t.jobId)
                RowAction.Retry -> engine.retry(t.jobId)
                RowAction.Remove -> engine.remove(t.jobId)
                RowAction.AllowMobileData -> engine.allowMobileData(t.jobId)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailure(ErrorMessages.forCode(ErrorCode.INTERNAL))
        }
    }
}
