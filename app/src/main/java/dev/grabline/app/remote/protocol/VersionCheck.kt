package dev.grabline.app.remote.protocol

enum class VersionVerdict {
    /** Carry on. */
    SUPPORTED,

    /** `protocolVersion` below [MIN_PC_PROTOCOL] or missing: send nothing, close 1000, "Update GrabLine on your PC" (QA-17). */
    PC_TOO_OLD,

    /** `hello.minClientVersion` above [PROTOCOL_VERSION]: send nothing, "Update GrabLine on this phone". */
    PHONE_TOO_OLD,
}

/** The minimum-version rule of PROTOCOL.md section 10. */
object VersionCheck {
    /**
     * [protocolVersion] is the PC's number from `hello` or any reply; a lower or missing number means the PC is too old.
     * [minClientVersion] is only in `hello`. A newer PC (higher protocolVersion) is fine: the phone ignores what it does not know.
     */
    fun evaluate(protocolVersion: Int?, minClientVersion: Int? = null): VersionVerdict = when {
        protocolVersion == null || protocolVersion < MIN_PC_PROTOCOL -> VersionVerdict.PC_TOO_OLD
        minClientVersion != null && minClientVersion > PROTOCOL_VERSION -> VersionVerdict.PHONE_TOO_OLD
        else -> VersionVerdict.SUPPORTED
    }

    fun evaluate(hello: ServerHello): VersionVerdict = evaluate(hello.protocolVersion, hello.minClientVersion)

    /** A `pong` (or any other reply) carries only `protocolVersion`. */
    fun evaluate(reply: Inbound.Reply): VersionVerdict = evaluate(reply.protocolVersion)

    fun evaluate(event: Inbound.Event): VersionVerdict = evaluate(event.protocolVersion)

    /** Hello, reply and event are judged; a malformed or oversize frame says nothing about versions. */
    fun evaluate(inbound: Inbound): VersionVerdict = when (inbound) {
        is ServerHello -> evaluate(inbound)
        is Inbound.Reply -> evaluate(inbound)
        is Inbound.Event -> evaluate(inbound)
        is Inbound.Malformed, is Inbound.TooLarge -> VersionVerdict.SUPPORTED
    }

    fun message(verdict: VersionVerdict): String? = when (verdict) {
        VersionVerdict.SUPPORTED -> null
        VersionVerdict.PC_TOO_OLD -> ErrorMessages.PC_TOO_OLD
        VersionVerdict.PHONE_TOO_OLD -> ErrorMessages.PHONE_TOO_OLD
    }
}
