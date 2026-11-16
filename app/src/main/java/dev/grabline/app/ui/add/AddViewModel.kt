package dev.grabline.app.ui.add

import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.grabline.app.data.AppSettings
import dev.grabline.app.data.Setting
import dev.grabline.app.engine.Checksum
import dev.grabline.app.engine.video.SubtitleTrack
import dev.grabline.app.engine.PhoneAddRequest
import dev.grabline.app.engine.PhoneEngine
import dev.grabline.app.engine.torrent.TorrentRouting
import dev.grabline.app.engine.video.DrmGuard
import dev.grabline.app.engine.video.VideoException
import dev.grabline.app.engine.video.VideoFailure
import dev.grabline.app.engine.video.VideoText
import dev.grabline.app.remote.PcRemote
import dev.grabline.app.remote.RemoteFailure
import dev.grabline.app.remote.connection.PcLinkState
import dev.grabline.app.remote.outbox.EnqueueResult
import dev.grabline.app.remote.protocol.AnalysisReply
import dev.grabline.app.remote.protocol.ErrorCode
import dev.grabline.app.remote.protocol.ErrorMessages
import dev.grabline.app.remote.protocol.FolderInfo
import dev.grabline.app.remote.protocol.QueueInfo
import dev.grabline.app.ui.downloads.PHONE_ID
import dev.grabline.app.ui.entry.LinkKind
import dev.grabline.app.ui.settings.RulesCodec
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

enum class FailureReason { NO_MEDIA, DRM, SITE_CHANGED, OTHER }

sealed interface AnalysisUi {
    data object Idle : AnalysisUi
    data object Loading : AnalysisUi
    data class Ready(
        val title: String?,
        val sizes: Map<String, Long?>,
        val playlistCount: Int? = null,
        /** "More formats" rows: raw formats on the phone, the PC analysis rows for a PC. */
        val formats: List<FormatRow> = emptyList(),
        val subtitles: List<SubtitleTrack> = emptyList(),
    ) : AnalysisUi
    data class Failed(val message: String, val reason: FailureReason) : AnalysisUi
}

enum class SendOutcome { SENT, WILL_SEND_LATER, ADDED_ON_PHONE }
data class SentResult(val outcome: SendOutcome, val pcName: String, val count: Int = 1)
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
    /** The phone engine exists, so "This phone" can be chosen. */
    val phoneAvailable: Boolean = false,
    val deviceIsPhone: Boolean = false,
    /** The Save folder for this add: "Downloads/GrabLine" or a picked tree URI. */
    val phoneFolder: String = "Downloads/GrabLine",
    /** Links the phone will add. */
    val phoneAddCount: Int = 0,
    /** A DRM service on this phone: the plain-words refusal, shown instead of the options. */
    val blockedMessage: String? = null,
    /** A shared .torrent file with a PC chosen: PCs only take magnet and web links, so say so. */
    val torrentFileNeedsPhone: Boolean = false,
    /** "More formats" rows to show (empty until analysis is ready). */
    val formatRows: List<FormatRow> = emptyList(),
    /** The row picked under "More formats", or null to use the quality chip. */
    val formatId: String? = null,
    val subtitleOptions: List<SubtitleTrack> = emptyList(),
    val subtitleLang: String? = null,
    /** Clip trim boxes as typed (phone video only). */
    val clipStart: String = "",
    val clipEnd: String = "",
    val clipValid: Boolean = true,
    /** "Start later" is offered for the phone only: protocol 6 has no scheduled start. */
    val canStartLater: Boolean = false,
    /** The chosen start time (epoch millis), or null for "start now". */
    val startAt: Long? = null,
    /** The pasted checksum as typed, and whether it is acceptable (blank is fine). */
    val checksum: String = "",
    val checksumValid: Boolean = true,
    val showChecksum: Boolean = false,
    val showClip: Boolean = false,
) {
    val selectedPc: PcOption? get() = pcs.firstOrNull { it.id == selectedPcId }
}

class AddViewModel(
    private val remote: PcRemote,
    private val settings: AppSettings,
    private val handle: SavedStateHandle,
    private val phone: PhoneEngine? = null,
) : ViewModel() {

    private data class Prefs(
        val quality: String,
        val audio: String,
        val device: String,
        val lastPc: String,
        val rules: String = "",
        val folder: String = "Downloads/GrabLine",
    )
    private data class Env(val pcs: List<dev.grabline.app.data.PairedPc>, val links: Map<String, PcLinkState>, val prefs: Prefs)
    private data class Local(
        val links: List<String>,
        val sourceTitle: String?,
        val pc: String?,
        val quality: String?,
        val queue: Int,
        val folder: String?,
        val expanded: Boolean,
        val phoneFolder: String?,
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
        combine(
            settings.observe(AppSettings.VIDEO_QUALITY),
            settings.observe(AppSettings.AUDIO_FORMAT),
            settings.observe(AppSettings.DEFAULT_DEVICE),
            settings.observe(LAST_PC),
        ) { q, a, d, l -> Prefs(q, a, d, l) },
        settings.observe(AppSettings.RULES),
        settings.observe(AppSettings.SAVE_FOLDER),
    ) { p, rules, folder -> p.copy(rules = rules, folder = folder) }

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
            handle.getStateFlow<String?>(PHONE_FOLDER, null),
        ) { q, f, e, pf -> listOf(q, f, e, pf) },
    ) { links, title, pc, quality, t ->
        Local(links, title, pc, quality, t[0] as Int, t[1] as String?, t[2] as Boolean, t[3] as String?)
    }

    private data class Extras(
        val formatId: String?,
        val subLang: String?,
        val clipStart: String,
        val clipEnd: String,
        val startAt: Long?,
        val checksum: String,
    )

    private val extras = combine(
        handle.getStateFlow<String?>(FORMAT, null),
        handle.getStateFlow<String?>(SUBS, null),
        combine(handle.getStateFlow(CLIP_START, ""), handle.getStateFlow(CLIP_END, "")) { a, b -> a to b },
        handle.getStateFlow(LATER, -1L),
        handle.getStateFlow(SHA, ""),
    ) { f, sub, clip, later, sha -> Extras(f, sub, clip.first, clip.second, later.takeIf { it > 0 }, sha) }

    val state: StateFlow<AddUiState> = combine(env, local, transient, extras) { e, l, t, x -> build(e, l, t, x) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AddUiState())

    init {
        viewModelScope.launch {
            state.map { it.analyzeTarget }.distinctUntilChanged().collectLatest { target ->
                if (target == null) {
                    transient.update { it.copy(analysis = AnalysisUi.Idle) }
                    return@collectLatest
                }
                transient.update { it.copy(analysis = AnalysisUi.Loading) }
                if (target.pcId == PHONE_ID) {
                    val ui = try {
                        val a = phone?.video?.analyze(target.url) ?: return@collectLatest
                        AnalysisUi.Ready(
                            a.title, a.qualities.associate { it.wire to it.sizeBytes }, a.playlistCount.takeIf { a.isPlaylist },
                            formats = FormatMapping.fromVideo(a.formats), subtitles = a.subtitles,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: VideoException) {
                        AnalysisUi.Failed(e.message.orEmpty(), videoReason(e.failure))
                    } catch (e: Exception) {
                        AnalysisUi.Failed(VideoText.UNEXPECTED, FailureReason.OTHER)
                    }
                    transient.update { it.copy(analysis = ui) }
                    return@collectLatest
                }
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
    fun init(links: List<String>, sourceTitle: String?, torrentFileUri: String? = null) {
        if (!handle.get<ArrayList<String>>(LINKS).isNullOrEmpty()) return
        handle[TORRENT_URI] = torrentFileUri
        handle[LINKS] = ArrayList(links)
        handle[TITLE] = sourceTitle
    }

    fun selectPc(id: String) {
        if (handle.get<String?>(PC) == id) return
        handle[PC] = id
        handle[QUEUE] = -1
        handle[FOLDER] = null
    }

    fun selectPhone() {
        if (phone != null) handle[PC] = PHONE_ID
    }

    /** A picked folder (SAF tree URI string) for this add only. */
    fun selectPhoneFolder(uri: String) { handle[PHONE_FOLDER] = uri }

    /** "Update the video engine" after "This site changed", then analyse again. */
    fun updateEngine() {
        val engine = phone?.video ?: return
        transient.update { it.copy(analysis = AnalysisUi.Loading) }
        viewModelScope.launch {
            engine.update()
            retryAnalyze()
        }
    }

    fun selectQuality(wire: String) { handle[QUALITY] = wire; handle[FORMAT] = null }

    /** A row under "More formats"; picking the same row again goes back to the chips. */
    fun selectFormat(id: String?) {
        val row = (state.value.analysis as? AnalysisUi.Ready)?.formats?.firstOrNull { it.id == id }
        handle[FORMAT] = if (row == null || handle.get<String?>(FORMAT) == id) null else id
        row?.presetWire?.takeIf { it in state.value.qualityOptions }?.let { handle[QUALITY] = it }
    }
    fun selectSubtitles(lang: String?) { handle[SUBS] = lang }
    fun setClipStart(text: String) { handle[CLIP_START] = text.take(10) }
    fun setClipEnd(text: String) { handle[CLIP_END] = text.take(10) }
    /** Epoch millis from StartLater.nextOccurrence, or null for "start now". */
    fun setStartLater(startAt: Long?) { handle[LATER] = startAt ?: -1L }
    fun setChecksum(text: String) { handle[SHA] = text.take(200) }
    fun selectQueue(id: Int?) { handle[QUEUE] = id ?: -1 }
    fun selectFolder(id: String?) { handle[FOLDER] = id }
    fun toggleLinks() { handle[EXPANDED] = !(handle.get<Boolean>(EXPANDED) ?: false) }
    fun retryAnalyze() = transient.update { it.copy(retry = it.retry + 1) }
    fun consumeSent() = transient.update { it.copy(sent = null) }

    fun startNow() {
        val s = state.value
        if (s.deviceIsPhone) return startOnPhone(s)
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
                    // A .torrent file can only be downloaded on this phone; PCs get magnet and web links.
                    if (link.startsWith(TorrentRouting.FILE_PREFIX)) continue
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

    private fun startOnPhone(s: AddUiState) {
        val engine = phone ?: return
        if (!s.canStart || s.sending) return
        transient.update { it.copy(sending = true, notice = null) }
        viewModelScope.launch {
            var added = 0
            var failed = false
            val single = s.links.size == 1
            val title = if (single) (handle.get<String?>(TITLE) ?: (s.analysis as? AnalysisUi.Ready)?.title) else null
            val folderUri = s.phoneFolder.takeIf { it.startsWith("content:") }
            try {
                for (link in s.links) {
                    val kind = LinkKind.classify(link)
                    if (kind == LinkKind.PAGE) continue
                    val video = kind == LinkKind.VIDEO
                    val quality = if (video) s.quality else null
                    val torrentFile = link.startsWith(TorrentRouting.FILE_PREFIX)
                    val spec = if (video) s.formatId?.let { id -> s.formatRows.firstOrNull { it.id == id }?.spec } else null
                    engine.add(
                        PhoneAddRequest(
                            url = link, title = if (torrentFile) null else title, folderUri = folderUri, quality = quality,
                            torrentFileUri = if (torrentFile) handle.get<String?>(TORRENT_URI) else null,
                            torrentFileName = if (torrentFile) link.removePrefix(TorrentRouting.FILE_PREFIX) else null,
                            startAt = s.startAt,
                            sha256 = if (single) Checksum.normalize(s.checksum) else null,
                            formatSpec = spec,
                            subtitleLang = if (video) s.subtitleLang else null,
                            clipStartSec = if (video) ClipTime.parse(s.clipStart) else null,
                            clipEndSec = if (video) ClipTime.parse(s.clipEnd) else null,
                        ),
                    )
                    added++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
            }
            if (added > 0) runCatching { settings.set(LAST_PC, PHONE_ID) }
            transient.update {
                when {
                    added > 0 -> it.copy(sending = false, sent = SentResult(SendOutcome.ADDED_ON_PHONE, "", added))
                    else -> it.copy(sending = false, notice = Notice.SEND_FAILED)
                }
            }
            if (added == 0) {
                delay(2_500)
                transient.update { it.copy(notice = null) }
            }
        }
    }

    private fun analysisReady(reply: AnalysisReply): AnalysisUi {
        val sizes = reply.qualities.associate { it.label.lowercase() to it.sizeBytes }
        return AnalysisUi.Ready(reply.title, sizes, formats = FormatMapping.fromPc(reply.qualities, ALL_QUALITIES))
    }

    private fun videoReason(f: VideoFailure) = when (f) {
        VideoFailure.NO_MEDIA -> FailureReason.NO_MEDIA
        VideoFailure.DRM -> FailureReason.DRM
        VideoFailure.SITE_CHANGED -> FailureReason.SITE_CHANGED
        else -> FailureReason.OTHER
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

    private fun build(e: Env, l: Local, t: Transient, x: Extras): AddUiState {
        val pcs = e.pcs.filter { it.removedAt == null }.map { PcOption(it.pcId, it.name, e.links[it.pcId] is PcLinkState.Online) }
        val phoneOk = phone != null
        val valid = pcs.map { it.id }.toSet() + listOfNotNull(PHONE_ID.takeIf { phoneOk })
        val rule = l.links.firstOrNull()?.let { RulesCodec.decode(e.prefs.rules)[RuleRouting.typeOf(it)] }
        val hasTorrentFile = l.links.any { it.startsWith(TorrentRouting.FILE_PREFIX) }
        val chosen = l.pc?.takeIf { it in valid }
            ?: PHONE_ID.takeIf { phoneOk && hasTorrentFile }
            ?: rule?.takeIf { it in valid }
            ?: e.prefs.device.takeIf { it in valid }
            ?: e.prefs.lastPc.takeIf { it in valid }
            ?: pcs.firstOrNull()?.id
            ?: PHONE_ID.takeIf { phoneOk }
        val isPhone = chosen == PHONE_ID
        val pcId = chosen.takeIf { !isPhone }
        val pc = pcs.firstOrNull { it.id == pcId }
        val online = pc?.online == true
        val kinds = l.links.map { LinkKind.classify(it) }
        val allPages = l.links.isNotEmpty() && kinds.all { it == LinkKind.PAGE }
        val hasVideo = kinds.any { it == LinkKind.VIDEO }
        val analysis = if (online || isPhone) t.analysis else AnalysisUi.Idle
        val blocked = if (isPhone) {
            l.links.firstNotNullOfOrNull { DrmGuard.refusal(it) }
                ?: (analysis as? AnalysisUi.Failed)?.takeIf { it.reason == FailureReason.DRM }?.message
        } else {
            null
        }
        val noMedia = (analysis as? AnalysisUi.Failed)?.reason == FailureReason.NO_MEDIA
        val notDownloadable = allPages || noMedia || blocked != null
        val phoneAdds = if (isPhone) kinds.count { it != LinkKind.PAGE } else 0

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
        val ready = analysis as? AnalysisUi.Ready
        val formatRows = ready?.formats.orEmpty()
        val formatId = x.formatId?.takeIf { id -> formatRows.any { it.id == id } }
        val subtitleLang = x.subLang?.takeIf { lang -> ready?.subtitles.orEmpty().any { it.lang == lang } }
        val phoneVideo = isPhone && hasVideo && !notDownloadable
        val clipValid = ClipTime.isValid(x.clipStart, x.clipEnd)
        val checksumValid = Checksum.isAcceptable(x.checksum)
        val showChecksum = isPhone && single && !allPages && !notDownloadable
        val target = when {
            pcId != null && online && single && kinds[0] == LinkKind.VIDEO -> AnalyzeTarget(pcId, l.links[0], t.retry)
            isPhone && phone?.video != null && single && kinds[0] == LinkKind.VIDEO && blocked == null ->
                AnalyzeTarget(PHONE_ID, l.links[0], t.retry)
            else -> null
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
            canStart = if (isPhone) phoneAdds > 0 && !notDownloadable && (!phoneVideo || clipValid) && (!showChecksum || checksumValid)
            else pc != null && !notDownloadable && l.links.any { !it.startsWith(TorrentRouting.FILE_PREFIX) },
            torrentFileNeedsPhone = !isPhone && hasTorrentFile,
            sending = t.sending,
            notice = t.notice,
            sent = t.sent,
            analyzeTarget = target,
            queuesPcId = if (pcId != null && online && !allPages) pcId else null,
            phoneAvailable = phoneOk,
            deviceIsPhone = isPhone,
            phoneFolder = l.phoneFolder ?: e.prefs.folder,
            phoneAddCount = phoneAdds,
            blockedMessage = blocked,
            formatRows = formatRows,
            formatId = formatId,
            subtitleOptions = if (isPhone) ready?.subtitles.orEmpty() else emptyList(),
            subtitleLang = if (isPhone) subtitleLang else null,
            clipStart = x.clipStart,
            clipEnd = x.clipEnd,
            clipValid = clipValid,
            canStartLater = isPhone && phoneAdds > 0 && !notDownloadable,
            startAt = if (isPhone) x.startAt else null,
            checksum = x.checksum,
            checksumValid = checksumValid,
            showChecksum = showChecksum,
            showClip = phoneVideo,
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
        const val PHONE_FOLDER = "add_phone_folder"
        const val FORMAT = "add_format"
        const val SUBS = "add_subs"
        const val CLIP_START = "add_clip_start"
        const val CLIP_END = "add_clip_end"
        const val LATER = "add_later"
        const val TORRENT_URI = "add_torrent_uri"
        const val SHA = "add_sha"

        /** The last PC a link was sent to ("" = none yet). */
        val LAST_PC = Setting(stringPreferencesKey("add_last_pc"), "")

        /** Wire values (lower case, PROTOCOL.md `download.quality`), in chip order. */
        val ALL_QUALITIES = listOf("best", "2160p", "1440p", "1080p", "720p", "480p", "360p", "mp3", "m4a", "flac")
    }
}
