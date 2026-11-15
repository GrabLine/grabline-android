package dev.grabline.app.ui.downloads

import dev.grabline.app.engine.PhoneAddRequest
import dev.grabline.app.engine.PhoneEngine
import dev.grabline.app.engine.PhoneJob
import dev.grabline.app.engine.PhoneJobStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakePhoneEngine : PhoneEngine {
    val jobsFlow = MutableStateFlow<List<PhoneJob>>(emptyList())
    val calls = mutableListOf<Pair<String, Long>>()
    val added = mutableListOf<PhoneAddRequest>()
    private var nextId = 100L

    override val jobs: Flow<List<PhoneJob>> = jobsFlow

    override suspend fun add(request: PhoneAddRequest): PhoneJob {
        added += request
        return phoneJob(nextId++, url = request.url).also { jobsFlow.value = listOf(it) + jobsFlow.value }
    }

    override suspend fun pause(jobId: Long) { calls += "pause" to jobId }
    override suspend fun resume(jobId: Long) { calls += "resume" to jobId }
    override suspend fun cancel(jobId: Long) { calls += "cancel" to jobId }
    override suspend fun retry(jobId: Long) { calls += "retry" to jobId }
    override suspend fun remove(jobId: Long) { calls += "remove" to jobId }
    override suspend fun pauseAll() { calls += "pauseAll" to 0L }
    override suspend fun resumeAll() { calls += "resumeAll" to 0L }
    override suspend fun allowMobileData(jobId: Long) { calls += "allowMobileData" to jobId }
}

fun phoneJob(
    id: Long,
    status: PhoneJobStatus = PhoneJobStatus.DOWNLOADING,
    url: String = "https://example.com/p$id.zip",
    notice: String? = null,
    error: String? = null,
    fileUri: String? = null,
) = PhoneJob(
    id = id, url = url, name = "phone$id.zip", status = status, downloaded = 10, total = 100, speed = 5,
    etaSeconds = null, connections = 4, notice = notice, error = error, fileUri = fileUri,
    createdAt = id, finishedAt = null,
)
