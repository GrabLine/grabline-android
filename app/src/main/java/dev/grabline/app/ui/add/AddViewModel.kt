package dev.grabline.app.ui.add

import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.grabline.app.data.AppSettings
import dev.grabline.app.data.Setting
import dev.grabline.app.remote.PcRemote
import dev.grabline.app.remote.RemoteFailure
import dev.grabline.app.remote.connection.PcLinkState
import dev.grabline.app.remote.outbox.EnqueueResult
import dev.grabline.app.remote.protocol.AnalysisReply
import dev.grabline.app.remote.protocol.ErrorCode
import dev.grabline.app.remote.protocol.ErrorMessages
import dev.grabline.app.remote.protocol.FolderInfo
import dev.grabline.app.remote.protocol.QueueInfo
import dev.grabline.app.ui.entry.LinkKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PcOption(val id: String, val name: String, val online: Boolean)

enum class FailureReason { NO_MEDIA, DRM, OTHER }

sealed interface AnalysisUi {
    data object Idle : AnalysisUi
    data object Loading : AnalysisUi
    data class Ready(val title: String?, val sizes: Map<String, Long?>) : AnalysisUi
    data class Failed(val message: String, val reason: FailureReason) : AnalysisUi
}

enum class SendOutcome { SENT, WILL_SEND_LATER }
data class SentResult(val outcome: SendOutcome, val pcName: String)
enum class Notice { ALREADY_SENT, SEND_FAILED }
data class AnalyzeTarget(val pcId: String, val url: String, val retry: Int)

data class AddUiState(
    val ready: Boolean = false,
    val links: List<String> = emptyList(),
    val kind: LinkKind? = null,
    val title: String? = null,
    val linksExpanded: Boolean = false,
    val pcs: List<PcOption> = emptyList(),
    val selectedPcId: String? = null,
    val pcOnline: Boolean = false,
    val showQuality: Boolean = false,
    /** Wire values of the chips to show, in display order. */
    val qualityOptions: List<String> = emptyList(),
    val quality: String = "best",
    val sizes: Map<String, Long?> = emptyMap(),
    val analysis: AnalysisUi = AnalysisUi.Idle,
    val queues: List<QueueInfo> = emptyList(),
    val folders: List<FolderInfo> = emptyList(),
    val queueId: Int? = null,
    val folderId: String? = null,
    val notDownloadable: Boolean = false,
    val canStart: Boolean = false,
    val sending: Boolean = false,
    val notice: Notice? = null,
    val sent: SentResult? = null,
    val analyzeTarget: AnalyzeTarget? = null,
    val queuesPcId: String? = null,
) {
    val selectedPc: PcOption? get() = pcs.firstOrNull { it.id == selectedPcId }
}

class AddViewModel(
    private val remote: PcRemote,
    private val settings: AppSettings,
    private val handle: SavedStateHandle,
) : ViewModel() {

    private data class Prefs(val quality: String, val audio: String, val device: String, val lastPc: String)
    private data class Env(val pcs: List<dev.grabline.app.data.PairedPc>, val links: Map<String, PcLinkState>, val prefs: Prefs)
    private data class Local(
        val links: List<String>,
        val sourceTitle: String?,
        val pc: String?,
        val quality: String?,
        val queue: Int,
        val folder: String?,
        val expanded: Boolean,
    )
    private data class Transient(
        val analysis: AnalysisUi = AnalysisUi.Idle,
        val queues: List<QueueInfo> = emptyList(),
        val folders: List<FolderInfo> = emptyList(),
        val retry: Int = 0,
        val sending: Boolean = false,
        val notice: Notice? = null,
        val sent: SentResult? = null,
    )

    private val transient = MutableStateFlow(Transient())

    private val prefs = combine(
        settings.observe(AppSettings.VIDEO_QUALITY),
        settings.observe(AppSettings.AUDIO_FORMAT),
        settings.observe(AppSettings.DEFAULT_DEVICE),
        settings.observe(LAST_PC),
    ) { q, a, d, l -> Prefs(q, a, d, l) }

    private val env = combine(remote.pcs, remote.linkStates, prefs) { pcs, links, p -> Env(pcs, links, p) }

    private val local = combine(
        handle.getStateFlow<ArrayList<String>>(LINKS, arrayListOf()),
        handle.getStateFlow<String?>(TITLE, null),
        handle.getStateFlow<String?>(PC, null),
        handle.getStateFlow<String?>(QUALITY, null),
        combine(
            handle.getStateFlow(QUEUE, -1),
            handle.getStateFlow<String?>(FOLDER, null),
            handle.getStateFlow(EXPANDED, false),
        ) { q, f, e -> Triple(q, f, e) },
    ) { links, title, pc, quality, t -> Local(links, title, pc, quality, t.first, t.second, t.third) }

    val state: StateFlow<AddUiState> = combine(env, local, transient) { e, l, t -> build(e, l, t) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AddUiState())

    init {
        viewModelScope.launch {
            state.map { it.analyzeTarget }.distinctUntilChanged().collectLatest { target ->
                if (target == null) {
                    transient.update { it.copy(analysis = AnalysisUi.Idle) }
                    return@collectLatest
                }
                transient.update { it.copy(analysis = AnalysisUi.Loading) }
                val result = try {
                    remote.analyze(target.pcId, target.url)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
                transient.update { it.copy(analysis = result.fold({ analysisReady(it) }, ::analysisFailed)) }
            }
        }
        viewModelScope.launch {
            state.map { it.queuesPcId }.distinctUntilChanged().collectLatest { pcId ->
                if (pcId == null) {
                    transient.update { it.copy(queues = emptyList(), folders = emptyList()) }
                    return@collectLatest
                }
                val reply = try {
                    remote.queues(pcId).getOrNull()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                transient.update {
                    it.copy(
                        queues = reply?.queues.orEmpty(),
                        folders = reply?.folders.orEmpty().sortedByDescending { f -> f.isDefault },
                    )
                }
            }
        }
    }

    /** Set the shared links once; a restored instance keeps its own. */
    fun init(links: List<String>, sourceTitle: String?) {
        if (!handle.get<ArrayList<String>>(LINKS).isNullOrEmpty()) return
        handle[LINKS] = ArrayList(links)
        handle[TITLE] = sourceTitle
    }

    fun selectPc(id: String) {
        if (handle.get<String?>(PC) == id) return
        handle[PC] = id
        handle[QUEUE] = -1
        handle[FOLDER] = null
    }

    fun selectQuality(wire: String) { handle[QUALITY] = wire }
    fun selectQueue(id: Int?) { handle[QUEUE] = id ?: -1 }
    fun selectFolder(id: String?) { handle[FOLDER] = id }
    fun toggleLinks() { handle[EXPANDED] = !(handle.get<Boolean>(EXPANDED) ?: false) }
    fun retryAnalyze() = transient.update { it.copy(retry = it.retry + 1) }
    fun consumeSent() = transient.update { it.copy(sent = null) }

    fun startNow() {
        val s = state.value
        val pc = s.selectedPc ?: return
        if (!s.canStart || s.sending) return
        transient.update { it.copy(sending = true, notice = null) }
        viewModelScope.launch {
            var queued = 0
            var duplicates = 0
            var failed = false
            val single = s.links.size == 1
            val title = if (single) (handle.get<String?>(TITLE) ?: (s.analysis as? AnalysisUi.Ready)?.title) else null
            try {
                for (link in s.links) {
                    val kind = LinkKind.classify(link)
                    if (kind == LinkKind.PAGE) continue
                    val result = remote.send(
                        pcId = pc.id,
                        url = link,
                        title = title,
                        quality = if (kind == LinkKind.VIDEO) s.quality else null,
                        queueId = s.queueId,
                        folderId = s.folderId,
                    )
                    when (result) {
                        is EnqueueResult.Queued -> queued++
                        EnqueueResult.DuplicateShare -> duplicates++
                        EnqueueResult.PcRemoved -> failed = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
            }
            if (queued > 0) runCatching { settings.set(LAST_PC, pc.id) }
            transient.update {
                when {
                    queued > 0 -> it.copy(
                        sending = false,
                        sent = SentResult(if (pc.online) SendOutcome.SENT else SendOutcome.WILL_SEND_LATER, pc.name),
                    )
                    failed -> it.copy(sending = false, notice = Notice.SEND_FAILED)
                    else -> it.copy(sending = false, notice = Notice.ALREADY_SENT)
                }
            }
            if (queued == 0) {
                delay(2_500)
                transient.update { it.copy(notice = null) }
            }
        }
    }

    private fun analysisReady(reply: AnalysisReply): AnalysisUi {
        val sizes = reply.qualities.associate { it.label.lowercase() to it.sizeBytes }
        return AnalysisUi.Ready(reply.title, sizes)
    }

    private fun analysisFailed(e: Throwable): AnalysisUi {
        val failure = e as? RemoteFailure
        val message = failure?.userMessage ?: ErrorMessages.forCode(ErrorCode.INTERNAL)
        val reason = when {
            failure?.reason == "noMedia" -> FailureReason.NO_MEDIA
            failure?.reason == "drm" -> FailureReason.DRM
            // Older fakes and messages without a reason: fall back to the text.
            message == ErrorMessages.failedByReason["noMedia"] -> FailureReason.NO_MEDIA
            message.contains("DRM") -> FailureReason.DRM
            else -> FailureReason.OTHER
        }
        return AnalysisUi.Failed(message, reason)
    }

    private fun build(e: Env, l: Local, t: Transient): AddUiState {
        val pcs = e.pcs.filter { it.removedAt == null }.map { PcOption(it.pcId, it.name, e.links[it.pcId] is PcLinkState.Online) }
        val ids = pcs.map { it.id }.toSet()
        val pcId = l.pc?.takeIf { it in ids }
            ?: e.prefs.device.takeIf { it in ids }
            ?: e.prefs.lastPc.takeIf { it in ids }
            ?: pcs.firstOrNull()?.id
        val pc = pcs.firstOrNull { it.id == pcId }
        val online = pc?.online == true
        val kinds = l.links.map { LinkKind.classify(it) }
        val allPages = l.links.isNotEmpty() && kinds.all { it == LinkKind.PAGE }
        val hasVideo = kinds.any { it == LinkKind.VIDEO }
        val analysis = if (online) t.analysis else AnalysisUi.Idle
        val noMedia = (analysis as? AnalysisUi.Failed)?.reason == FailureReason.NO_MEDIA
        val notDownloadable = allPages || noMedia

        val options = when (analysis) {
            is AnalysisUi.Ready -> ALL_QUALITIES.filter { it == "best" || it in analysis.sizes }
            is AnalysisUi.Failed -> listOf("best")
            else -> ALL_QUALITIES
        }
        val quality = l.quality?.takeIf { it in options }
            ?: defaultQuality(e.prefs).takeIf { it in options }
            ?: "best"

        val queues = if (online) t.queues else emptyList()
        val folders = if (online) t.folders else emptyList()
        val queueId = l.queue.takeIf { id -> id >= 0 && queues.any { it.id == id.toLong() } }
        val folderId = l.folder?.takeIf { id -> folders.any { it.id == id } }

        val single = l.links.size == 1
        val target = if (pcId != null && online && single && kinds[0] == LinkKind.VIDEO) {
            AnalyzeTarget(pcId, l.links[0], t.retry)
        } else {
            null
        }
        return AddUiState(
            ready = true,
            links = l.links,
            kind = kinds.firstOrNull(),
            title = l.sourceTitle ?: (analysis as? AnalysisUi.Ready)?.title,
            linksExpanded = l.expanded,
            pcs = pcs,
            selectedPcId = pcId,
            pcOnline = online,
            showQuality = hasVideo && !notDownloadable,
            qualityOptions = options,
            quality = quality,
            sizes = (analysis as? AnalysisUi.Ready)?.sizes.orEmpty(),
            analysis = analysis,
            queues = queues,
            folders = folders,
            queueId = queueId,
            folderId = folderId,
            notDownloadable = notDownloadable,
            canStart = pc != null && !notDownloadable && l.links.isNotEmpty(),
            sending = t.sending,
            notice = t.notice,
            sent = t.sent,
            analyzeTarget = target,
            queuesPcId = if (pcId != null && online && !allPages) pcId else null,
        )
    }

    private fun defaultQuality(p: Prefs): String = when (p.quality) {
        "1080p" -> "1080p"
        "720p" -> "720p"
        "audio" -> p.audio.takeIf { it in ALL_QUALITIES } ?: "mp3"
        else -> "best" // "ask" and "best"
    }

    companion object {
        const val LINKS = "add_links"
        const val TITLE = "add_title"
        const val PC = "add_pc"
        const val QUALITY = "add_quality"
        const val QUEUE = "add_queue"
        const val FOLDER = "add_folder"
        const val EXPANDED = "add_expanded"

        /** The last PC a link was sent to ("" = none yet). */
        val LAST_PC = Setting(stringPreferencesKey("add_last_pc"), "")

        /** Wire values (lower case, PROTOCOL.md `download.quality`), in chip order. */
        val ALL_QUALITIES = listOf("best", "2160p", "1440p", "1080p", "720p", "480p", "360p", "mp3", "m4a", "flac")
    }
}
