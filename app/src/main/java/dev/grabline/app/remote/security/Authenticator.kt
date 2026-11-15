package dev.grabline.app.remote.security

import dev.grabline.app.data.PairedPc
import dev.grabline.app.remote.protocol.AuthOkReply
import dev.grabline.app.remote.protocol.AuthRequest
import dev.grabline.app.remote.protocol.Base64Url
import dev.grabline.app.remote.protocol.CloseCodes
import dev.grabline.app.remote.protocol.ErrorCode
import dev.grabline.app.remote.protocol.ErrorMessages
import dev.grabline.app.remote.protocol.ErrorReply
import dev.grabline.app.remote.protocol.Inbound
import dev.grabline.app.remote.protocol.ProtocolCodec
import dev.grabline.app.remote.protocol.ServerHello
import dev.grabline.app.remote.protocol.VersionCheck
import dev.grabline.app.remote.protocol.VersionVerdict
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What to do after the PC's `hello` on a connection whose TLS key matched the pin. */
sealed interface AuthStep {
    /** Send [frame] (the `auth` request JSON) and wait for the reply with id [requestId]. */
    class Send(val request: AuthRequest, val frame: String) : AuthStep {
        val requestId: String get() = request.id
        override fun toString(): String = "AuthStep.Send(id=$requestId, <redacted>)"
    }

    /** Send nothing; the outcome is already decided. */
    data class Stop(val outcome: AuthOutcome) : AuthStep
}

/** How a login attempt ended. */
sealed interface AuthOutcome {
    val userMessage: String?

    data class Ok(val reply: AuthOkReply) : AuthOutcome {
        override val userMessage: String? get() = null
    }

    /**
     * The real PC (its key matched the pin) refused this phone: `unauthorized`, close 4401 or 4403, or the
     * device key is gone. The caller deletes the pairing row and the key (QA-06).
     */
    data object PairingRemoved : AuthOutcome {
        override val userMessage: String get() = ErrorMessages.REMOVED_WHILE_CONNECTED
    }

    /** `versionTooOld`, close 4426, or `hello.minClientVersion` above ours. */
    data object PhoneTooOld : AuthOutcome {
        override val userMessage: String get() = ErrorMessages.PHONE_TOO_OLD
    }

    /** `protocolVersion` below 6 or missing (QA-17). Send nothing more, close 1000. */
    data object PcTooOld : AuthOutcome {
        override val userMessage: String get() = ErrorMessages.PC_TOO_OLD
    }

    /** A malformed `hello` or reply, or another error code. The pairing is kept; reconnect later. */
    data class Failed(val code: ErrorCode) : AuthOutcome {
        override val userMessage: String get() = ErrorMessages.forCode(code)
    }
}

/**
 * Per-connection login (PROTOCOL.md section 6). Signs
 * `"grabline-auth-v1" || 0x00 || hello.nonce || pin || deviceId` with the device key of this PC; the pin is
 * [PairedPc.fingerprint], which the TLS handshake of this very connection was checked against.
 * Nothing here logs or exposes the nonce or the signature.
 */
class Authenticator(
    private val keyStore: DeviceKeyStore,
    private val appVersion: String? = null,
    private val keyDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * The signed `auth` request for this connection's [hello].
     * @throws DeviceKeyException when the key is missing or cannot sign
     * @throws IllegalArgumentException when [hello] or [pc] is malformed (no nonce, bad pin or deviceId)
     */
    suspend fun authRequest(pc: PairedPc, hello: ServerHello, id: String = DEFAULT_REQUEST_ID): AuthRequest {
        val nonce = requireNotNull(hello.nonceBytes()) { "hello has no usable nonce" }
        val pin = requireNotNull(Pin.bytes(pc.fingerprint)) { "stored pin is malformed" }
        val transcript = AuthTranscript.build(nonce, pin, pc.deviceId)
        val signature = withContext(keyDispatcher) { keyStore.sign(pc.pcId, transcript) }
        if (signature.isEmpty() || signature.size > DeviceKeyStore.MAX_SIGNATURE_BYTES) {
            throw DeviceKeyException("unexpected signature size")
        }
        return AuthRequest(
            id = id,
            deviceId = pc.deviceId,
            signature = Base64Url.encode(signature),
            appVersion = appVersion?.take(32),
        )
    }

    /** Version gate first (nothing is sent to a PC that is too old), then the signed request as a text frame. */
    suspend fun onHello(pc: PairedPc, hello: ServerHello, id: String = DEFAULT_REQUEST_ID): AuthStep {
        when (VersionCheck.evaluate(hello)) {
            VersionVerdict.PC_TOO_OLD -> return AuthStep.Stop(AuthOutcome.PcTooOld)
            VersionVerdict.PHONE_TOO_OLD -> return AuthStep.Stop(AuthOutcome.PhoneTooOld)
            VersionVerdict.SUPPORTED -> Unit
        }
        return try {
            val request = authRequest(pc, hello, id)
            AuthStep.Send(request, ProtocolCodec.encode(request))
        } catch (_: DeviceKeyException) {
            // A key that no longer exists means this phone can never log in to that PC again: same end as a
            // removal. A key that exists but failed to sign this once keeps the pairing.
            val gone = withContext(keyDispatcher) { runCatching { keyStore.publicKeySpki(pc.pcId) }.getOrNull() } == null
            AuthStep.Stop(if (gone) AuthOutcome.PairingRemoved else AuthOutcome.Failed(ErrorCode.INTERNAL))
        } catch (_: IllegalArgumentException) {
            AuthStep.Stop(AuthOutcome.Failed(ErrorCode.INTERNAL))
        }
    }

    /**
     * Interprets a frame received after `auth` was sent. Null when the frame is not the answer to [requestId]
     * (keep waiting). An error reply without an id counts as the answer: before `authOk` nothing else is pending.
     */
    fun onReply(inbound: Inbound, requestId: String = DEFAULT_REQUEST_ID): AuthOutcome? {
        val reply = inbound as? Inbound.Reply ?: return null
        val message = reply.message
        if (message is ErrorReply && (reply.id == null || reply.id == requestId)) {
            return when (message.errorCode) {
                ErrorCode.UNAUTHORIZED -> AuthOutcome.PairingRemoved
                ErrorCode.VERSION_TOO_OLD -> AuthOutcome.PhoneTooOld
                else -> AuthOutcome.Failed(message.errorCode)
            }
        }
        if (reply.id != requestId) return null
        if (VersionCheck.evaluate(reply) == VersionVerdict.PC_TOO_OLD) return AuthOutcome.PcTooOld
        return if (message is AuthOkReply) AuthOutcome.Ok(message) else AuthOutcome.Failed(ErrorCode.INTERNAL)
    }

    /**
     * Interprets a close before `authOk`. Null means "not a verdict on this pairing": reconnect as
     * [CloseCodes.actionFor] says.
     */
    fun onClose(code: Int): AuthOutcome? = when (code) {
        CloseCodes.AUTH_FAILED, CloseCodes.REMOVED_OR_DENIED -> AuthOutcome.PairingRemoved
        CloseCodes.PHONE_TOO_OLD -> AuthOutcome.PhoneTooOld
        else -> null
    }

    companion object {
        const val DEFAULT_REQUEST_ID = "auth-1"
    }
}
