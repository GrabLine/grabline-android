package dev.grabline.app.remote.protocol

/** The protocol version this phone speaks (PROTOCOL.md section 10). */
const val PROTOCOL_VERSION = 6

/** Lowest PC protocol this phone accepts (PROTOCOL.md section 10). */
const val MIN_PC_PROTOCOL = 6

/** Frame limit in bytes (UTF-8), both directions (PROTOCOL.md section 2). */
const val MAX_FRAME_BYTES = 1_048_576

/** Frame limit before `authOk` (PROTOCOL.md section 6). */
const val PRE_AUTH_MAX_FRAME_BYTES = 4_096

const val DEFAULT_PORT = 47821
const val MAX_LINKS_PER_REQUEST = 300
const val MAX_STATUS_URLS = 50

/** Request time-outs in milliseconds (PROTOCOL.md section 2, Envelope). */
object Timeouts {
    const val DEFAULT_MS = 15_000L
    const val ANALYZE_MS = 45_000L
    const val FILE_HASH_MS = 45_000L
    const val PAIR_MS = 90_000L

    fun forType(type: String): Long = when (type) {
        "analyze" -> ANALYZE_MS
        "fileHash" -> FILE_HASH_MS
        "pair" -> PAIR_MS
        else -> DEFAULT_MS
    }
}

/** WebSocket close codes (PROTOCOL.md section 2). */
object CloseCodes {
    const val NORMAL = 1000
    const val GOING_AWAY = 1001
    const val TOO_BIG = 1009
    const val MALFORMED = 4400
    const val AUTH_FAILED = 4401
    const val REMOVED_OR_DENIED = 4403
    const val AUTH_TIMEOUT = 4408
    const val REPLACED = 4409
    const val PHONE_TOO_OLD = 4426
    const val FLOODING = 4429

    /** Flood back-off before reconnecting after [FLOODING]. */
    const val FLOOD_RETRY_MS = 30_000L

    fun actionFor(code: Int): CloseAction = when (code) {
        NORMAL, REPLACED -> CloseAction.NONE
        GOING_AWAY -> CloseAction.RECONNECT_WITH_BACKOFF
        TOO_BIG, MALFORMED -> CloseAction.REPORT_BUG
        AUTH_FAILED -> CloseAction.PAIRING_REMOVED
        REMOVED_OR_DENIED -> CloseAction.PAIRING_REMOVED_OR_DECLINED
        AUTH_TIMEOUT -> CloseAction.RECONNECT_ONCE
        PHONE_TOO_OLD -> CloseAction.UPDATE_PHONE
        FLOODING -> CloseAction.RECONNECT_AFTER_30_SECONDS
        else -> CloseAction.RECONNECT_WITH_BACKOFF
    }
}

/** What the phone does after a close code. */
enum class CloseAction {
    NONE,
    RECONNECT_WITH_BACKOFF,
    REPORT_BUG,

    /** 4401: treat the pairing as removed (PROTOCOL.md section 7). */
    PAIRING_REMOVED,

    /** 4403: "no longer accepts this phone" when authenticated, "PC declined" during pairing. */
    PAIRING_REMOVED_OR_DECLINED,
    RECONNECT_ONCE,
    UPDATE_PHONE,
    RECONNECT_AFTER_30_SECONDS,
}

/** The closed set of error codes (PROTOCOL.md section 16). */
enum class ErrorCode(val wire: String) {
    BAD_REQUEST("badRequest"),
    UNAUTHORIZED("unauthorized"),
    FORBIDDEN("forbidden"),
    NOT_FOUND("notFound"),
    CONFLICT("conflict"),
    TOO_LARGE("tooLarge"),
    RATE_LIMITED("rateLimited"),
    UNSUPPORTED("unsupported"),
    VERSION_TOO_OLD("versionTooOld"),
    PAIRING_EXPIRED("pairingExpired"),
    PAIRING_LOCKED("pairingLocked"),
    PAIRING_DENIED("pairingDenied"),
    BAD_CODE("badCode"),
    FAILED("failed"),
    INTERNAL("internal");

    companion object {
        /** An unknown or missing code is handled as [INTERNAL] (PROTOCOL.md section 16). */
        fun fromWire(wire: String?): ErrorCode = entries.firstOrNull { it.wire == wire } ?: INTERNAL

        fun isKnown(wire: String?): Boolean = entries.any { it.wire == wire }
    }
}

/**
 * Plain-words texts. English only for now; the UI moves them to strings.xml.
 * Sources: PROTOCOL.md section 16 and SPEC.md "Error states and edge cases".
 */
object ErrorMessages {
    val byCode: Map<ErrorCode, String> = mapOf(
        ErrorCode.BAD_REQUEST to "Something went wrong sending that. Update GrabLine on this phone if it keeps happening",
        ErrorCode.UNAUTHORIZED to "This PC no longer accepts this phone. Pair again to reconnect",
        ErrorCode.FORBIDDEN to "Your PC doesn't allow that",
        ErrorCode.NOT_FOUND to "That's no longer on your PC",
        ErrorCode.CONFLICT to "That can't be done right now",
        ErrorCode.TOO_LARGE to "That's too much to send at once",
        ErrorCode.RATE_LIMITED to "Your PC is busy. Try again in a moment",
        ErrorCode.UNSUPPORTED to "Update GrabLine on your PC to use this",
        ErrorCode.VERSION_TOO_OLD to "Update GrabLine on this phone",
        ErrorCode.PAIRING_EXPIRED to "Code expired. Show a new code on your PC",
        ErrorCode.PAIRING_LOCKED to "Too many wrong codes. Show a new code on your PC",
        ErrorCode.PAIRING_DENIED to "PC declined",
        ErrorCode.BAD_CODE to "That code didn't match. Check it and try again",
        ErrorCode.FAILED to "Your PC couldn't do that. Try again",
        ErrorCode.INTERNAL to "Your PC hit a problem. Try again",
    )

    /** `failed` replies by `reason` (PROTOCOL.md section 12 `analyze`, SPEC.md). */
    val failedByReason: Map<String, String> = mapOf(
        "noMedia" to "This is a web page, not a file",
        "drm" to "This site protects its media with DRM. GrabLine can't download it",
        "siteError" to "The site refused this download. Open it in your browser and try again from there",
        "timeout" to "Your PC took too long to answer. Try again",
        "io" to "Your PC couldn't read or write that file. Try again",
    )

    /** Conditions that are not error replies (PROTOCOL.md section 16, second table). */
    const val PC_TOO_OLD = "Update GrabLine on your PC"
    const val PHONE_TOO_OLD = "Update GrabLine on this phone"
    const val IDENTITY_CHANGED = "Your PC's identity changed. If you reinstalled GrabLine, pair again"
    const val PC_NOT_FOUND = "PC not found"
    const val WILL_SEND_WHEN_BACK = "Will send when your PC is back"
    const val REMOVED_WHILE_CONNECTED = "This PC no longer accepts this phone. Pair again to reconnect"
    const val CONFIRM_ON_PC = "Confirm on your PC"
    const val NOT_A_PAIRING_CODE = "This isn't a GrabLine pairing code"
    const val CODE_DOES_NOT_LOOK_RIGHT = "That code doesn't look right"
    const val UPDATE_PC_FOR_FEATURE = "Update GrabLine on your PC to use this"
    const val PAIR_DROPPED = "The connection to your PC dropped. Try again"
    const val NO_SECURE_KEY = "This phone couldn't create a secure key. Try again"
    const val PC_NO_ANSWER = "Your PC didn't answer. Check it is on the same Wi-Fi and try again"

    fun forCode(code: ErrorCode): String = byCode.getValue(code)

    /** `drm` text names the site when the caller knows it, as SPEC.md does ("Netflix protects ..."). */
    fun forReply(code: ErrorCode, reason: String? = null, siteName: String? = null): String {
        if (code == ErrorCode.FAILED && reason != null) {
            val text = failedByReason[reason]
            if (text != null) {
                return if (reason == "drm" && !siteName.isNullOrBlank()) {
                    "$siteName protects its media with DRM. GrabLine can't download it"
                } else {
                    text
                }
            }
        }
        return forCode(code)
    }
}
