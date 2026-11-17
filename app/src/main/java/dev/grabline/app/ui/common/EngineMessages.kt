package dev.grabline.app.ui.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.grabline.app.R
import dev.grabline.app.engine.Checksum
import dev.grabline.app.engine.EngineText
import dev.grabline.app.engine.TransferText
import dev.grabline.app.engine.cloud.CloudText
import dev.grabline.app.engine.torrent.TorrentText
import dev.grabline.app.engine.video.VideoText
import dev.grabline.app.remote.protocol.ErrorCode
import dev.grabline.app.remote.protocol.ErrorMessages

/**
 * The engines store their plain-words failure and notice messages in English (the database and the PC
 * protocol depend on that). This turns a stored message into the user's language at display time.
 * Text that is not one of the known messages (a PC's own wording, a file name) comes back unchanged.
 */
fun localizeEngineMessage(context: Context, text: String): String {
    val found = EngineMessages.resolve(text) ?: return text
    return context.getString(found.res, *found.args.toTypedArray())
}

@Composable
fun localizedEngineMessage(text: String): String = localizeEngineMessage(LocalContext.current, text)

internal class ResolvedMessage(@StringRes val res: Int, val args: List<String>)

internal object EngineMessages {
    /** Every fixed message, keyed by the constant itself so the two cannot drift apart. */
    val exact: Map<String, Int> = buildMap {
        fun add(text: String, res: Int) {
            val old = put(text, res)
            check(old == null || old == res) { "Two translations for: $text" }
        }
        add(EngineText.NO_RESUME, R.string.msg_no_resume)
        add(EngineText.FILE_CHANGED, R.string.msg_file_changed)
        add(EngineText.REFUSED, R.string.msg_site_refused)
        add(EngineText.COMPRESSED, R.string.msg_compressed)
        add(EngineText.WRONG_RANGE, R.string.msg_wrong_range)
        add(EngineText.PUSHBACK, R.string.msg_pushback)
        add(EngineText.CONNECTION, R.string.msg_connection)
        add(EngineText.NOT_FOUND, R.string.msg_server_not_found)
        add(EngineText.BAD_LINK, R.string.msg_bad_link)
        add(EngineText.FOLDER, R.string.msg_folder)
        add(EngineText.OUT_OF_SPACE, R.string.msg_no_space)
        add(EngineText.CHANGING, R.string.msg_changing)
        add(EngineText.UNEXPECTED, R.string.msg_unexpected)

        add(VideoText.DRM, R.string.msg_video_drm)
        add(VideoText.NO_MEDIA, R.string.msg_no_media)
        add(VideoText.SITE_CHANGED, R.string.msg_site_changed)
        add(VideoText.SITE_CHANGED_MANUAL, R.string.msg_site_changed_manual)
        add(VideoText.REFUSED, R.string.msg_site_refused)
        add(VideoText.NOT_FOUND, R.string.msg_video_not_found)
        add(VideoText.NO_SPACE, R.string.msg_no_space)
        add(VideoText.CONNECTION, R.string.msg_connection)
        add(VideoText.TIMEOUT, R.string.msg_site_timeout)
        add(VideoText.UNEXPECTED, R.string.msg_video_unexpected)
        add(VideoText.ENGINE_START, R.string.msg_engine_start)
        add(VideoText.UPDATE_FAILED, R.string.msg_update_failed)

        add(TorrentText.NOT_A_TORRENT, R.string.msg_not_a_torrent)
        add(TorrentText.UNAVAILABLE, R.string.msg_torrents_unavailable)
        add(TorrentText.NO_PEERS_FILE, R.string.msg_torrent_not_saved)

        add(CloudText.UNAVAILABLE, R.string.msg_cloud_unavailable)
        add(CloudText.BAD_LINK, R.string.msg_cloud_bad_link)
        add(CloudText.AUTH, R.string.msg_cloud_auth)
        add(CloudText.NOT_FOUND, R.string.msg_cloud_not_found)
        add(CloudText.UNREACHABLE, R.string.msg_cloud_unreachable)
        add(CloudText.TLS, R.string.msg_cloud_tls)
        add(CloudText.EMPTY_FOLDER, R.string.msg_cloud_empty_folder)
        add(CloudText.NO_DAV_FOLDERS, R.string.msg_cloud_no_dav_folders)
        add(CloudText.PLAIN_HTTP, R.string.msg_cloud_plain_http)
        add(CloudText.DROPPED, R.string.msg_cloud_dropped)
        add(CloudText.SHORT, R.string.msg_cloud_short)
        add(CloudText.NO_KEY, R.string.msg_cloud_no_key)

        add(TransferText.HASH_MISMATCH, R.string.msg_transfer_hash)
        add(TransferText.NOT_ON_PC, R.string.msg_pc_not_found)
        add(TransferText.REFUSED, R.string.msg_transfer_refused)
        add(TransferText.PC_OFFLINE, R.string.msg_pc_offline)

        add(Checksum.NOTICE_VERIFIED, R.string.msg_checksum_verified)
        add(Checksum.NOTICE_MISMATCH, R.string.msg_checksum_mismatch)
        add(Checksum.INVALID, R.string.msg_checksum_invalid)

        // Messages the PC protocol maps from error codes.
        val pc = mapOf(
            ErrorCode.BAD_REQUEST to R.string.msg_pc_bad_request,
            ErrorCode.UNAUTHORIZED to R.string.msg_pc_unauthorized,
            ErrorCode.FORBIDDEN to R.string.msg_pc_forbidden,
            ErrorCode.NOT_FOUND to R.string.msg_pc_not_found,
            ErrorCode.CONFLICT to R.string.msg_pc_conflict,
            ErrorCode.TOO_LARGE to R.string.msg_pc_too_large,
            ErrorCode.RATE_LIMITED to R.string.msg_pc_busy,
            ErrorCode.UNSUPPORTED to R.string.msg_pc_unsupported,
            ErrorCode.VERSION_TOO_OLD to R.string.msg_phone_too_old,
            ErrorCode.PAIRING_EXPIRED to R.string.msg_pair_expired,
            ErrorCode.PAIRING_LOCKED to R.string.msg_pair_locked,
            ErrorCode.PAIRING_DENIED to R.string.msg_pair_denied,
            ErrorCode.BAD_CODE to R.string.msg_pair_bad_code,
            ErrorCode.FAILED to R.string.msg_pc_failed,
            ErrorCode.INTERNAL to R.string.msg_pc_internal,
        )
        for (code in ErrorCode.entries) add(ErrorMessages.forCode(code), pc.getValue(code))
        add(ErrorMessages.failedByReason.getValue("noMedia"), R.string.msg_no_media)
        add(ErrorMessages.failedByReason.getValue("drm"), R.string.msg_pc_drm)
        add(ErrorMessages.failedByReason.getValue("siteError"), R.string.msg_site_refused)
        add(ErrorMessages.failedByReason.getValue("timeout"), R.string.msg_pc_timeout)
        add(ErrorMessages.failedByReason.getValue("io"), R.string.msg_pc_io)
        add(ErrorMessages.PC_TOO_OLD, R.string.msg_pc_too_old)
        add(ErrorMessages.PHONE_TOO_OLD, R.string.msg_phone_too_old)
        add(ErrorMessages.IDENTITY_CHANGED, R.string.msg_pc_identity)
        add(ErrorMessages.PC_NOT_FOUND, R.string.msg_pc_missing)
        add(ErrorMessages.WILL_SEND_WHEN_BACK, R.string.msg_send_when_back)
        add(ErrorMessages.REMOVED_WHILE_CONNECTED, R.string.msg_pc_unauthorized)
        add(ErrorMessages.CONFIRM_ON_PC, R.string.msg_confirm_on_pc)
        add(ErrorMessages.NOT_A_PAIRING_CODE, R.string.msg_not_pairing_code)
        add(ErrorMessages.CODE_DOES_NOT_LOOK_RIGHT, R.string.msg_code_not_right)
        add(ErrorMessages.UPDATE_PC_FOR_FEATURE, R.string.msg_pc_unsupported)
        add(ErrorMessages.PAIR_DROPPED, R.string.msg_pair_dropped)
        add(ErrorMessages.NO_SECURE_KEY, R.string.msg_no_secure_key)
        add(ErrorMessages.PC_NO_ANSWER, R.string.msg_pc_no_answer)
    }

    private class Pattern(val regex: Regex, val res: Int)

    /**
     * A regex for a message built from [sample] (the real function called with recognisable [tokens]);
     * each token becomes the matching entry of [groups].
     */
    private fun pattern(sample: String, tokens: List<String>, groups: List<String>, @StringRes res: Int): Pattern {
        val out = StringBuilder("^")
        var at = 0
        tokens.forEachIndexed { i, token ->
            val start = sample.indexOf(token, at)
            check(start >= 0) { "Token $token is not in: $sample" }
            out.append(Regex.escape(sample.substring(at, start))).append(groups[i])
            at = start + token.length
        }
        out.append(Regex.escape(sample.substring(at))).append("$")
        return Pattern(Regex(out.toString()), res)
    }

    private const val ANY = "(.+?)"
    private const val NUMBER = "(\\d+)"
    private const val SIZE = "(\\d+(?:\\.\\d+)? (?:GB|MB|KB|B))"

    private val patterns: List<Pattern> = listOf(
        pattern(EngineText.server(7654321), listOf("7654321"), listOf(NUMBER), R.string.msg_server_error),
        pattern(EngineText.needsSpace(1, 2), listOf("1 B", "2 B"), listOf(SIZE, SIZE), R.string.msg_needs_space),
        pattern(CloudText.tooMany(7654321), listOf("7654321"), listOf(NUMBER), R.string.msg_cloud_too_many),
        pattern(
            CloudText.hostKeyChanged("HOSTTOKEN", "EXPECTEDTOKEN", "GOTTOKEN"),
            listOf("HOSTTOKEN", "EXPECTEDTOKEN", "GOTTOKEN"), listOf(ANY, ANY, ANY), R.string.msg_cloud_host_key,
        ),
        pattern(
            ErrorMessages.forReply(ErrorCode.FAILED, "drm", "SITETOKEN"),
            listOf("SITETOKEN"), listOf(ANY), R.string.msg_pc_drm_site,
        ),
        // "Starts at 6:30 PM" / "Starts tomorrow at 6:30 PM" (StartLater.notice); the time is already in the phone's format.
        Pattern(Regex("^Starts tomorrow at (.+)$"), R.string.msg_starts_tomorrow),
        Pattern(Regex("^Starts at (.+)$"), R.string.msg_starts_at),
    )

    /** Every string resource a message can use; the tests check each one in every language. */
    val allResources: Set<Int> get() = exact.values.toSet() + patterns.map { it.res }

    fun resolve(text: String): ResolvedMessage? {
        exact[text]?.let { return ResolvedMessage(it, emptyList()) }
        for (p in patterns) {
            val m = p.regex.matchEntire(text) ?: continue
            return ResolvedMessage(p.res, m.groupValues.drop(1))
        }
        return null
    }
}
