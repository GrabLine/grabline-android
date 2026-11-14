package dev.grabline.app.remote.connection

import dev.grabline.app.data.PairedPc
import dev.grabline.app.remote.protocol.AuthOkReply
import dev.grabline.app.remote.protocol.AuthRequest
import dev.grabline.app.remote.protocol.CloseCodes
import dev.grabline.app.remote.protocol.ErrorCode
import dev.grabline.app.remote.protocol.ErrorReply
import dev.grabline.app.remote.protocol.Inbound
import dev.grabline.app.remote.protocol.ProtocolCodec
import dev.grabline.app.remote.protocol.Request
import dev.grabline.app.remote.protocol.ServerEvent
import dev.grabline.app.remote.protocol.ServerHello
import dev.grabline.app.remote.protocol.Timeouts
import dev.grabline.app.remote.protocol.VersionCheck
import dev.grabline.app.remote.protocol.VersionVerdict
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Builds the signed `auth` request for this connection's `hello` (remote/security). */
fun interface AuthProvider {
    suspend fun authRequest(pc: PairedPc, hello: ServerHello, id: String): AuthRequest
}

/** Why a session could not be opened, or why it ended. */
sealed interface SessionEnd {
    /** Network failure or timeout: try another address / reconnect later. */
    data class Unreachable(val reason: String) : SessionEnd
    /** The TLS key is not the pinned one (QA-07). */
    data object IdentityChanged : SessionEnd
    /** The PC refused our key or removed this phone (QA-06). */
    data object PairingRemoved : SessionEnd
    /** The PC speaks an older protocol (QA-17). */
    data object PcTooOld : SessionEnd
    /** The PC needs a newer phone app. */
    data object PhoneTooOld : SessionEnd
    /** Closed by the PC for a reason that allows reconnecting (1001, 4408, 4409, 4429). */
    data class Closed(val code: Int, val retryAfterMs: Long) : SessionEnd
}

/** An error reply from the PC, or the connection dropping before the reply. */
class RemoteException(
    val code: ErrorCode,
    val reply: ErrorReply? = null,
    message: String = code.wire,
) : Exception(message)

/**
 * One authenticated WebSocket to one PC address. Created by [ConnectionManager];
 * [open] returns once `authOk` arrived (or the session failed).
 */
class PcSession(
    private val pc: PairedPc,
    val address: String,
    private val factory: WireFactory,
    private val auth: AuthProvider,
) {
    private val ids = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Inbound.Reply>>()
    private val opened = CompletableDeferred<Result<AuthOkReply>>()
    private val ended = CompletableDeferred<SessionEnd>()
    private val authSent = AtomicBoolean(false)
    @Volatile private var socket: WireSocket? = null
    @Volatile private var authId: String? = null
    @Volatile private var helloDeferred = CompletableDeferred<ServerHello>()

    private val _events = MutableSharedFlow<ServerEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<ServerEvent> = _events

    /** Completes when the session ends, with the reason. */
    suspend fun awaitEnd(): SessionEnd = ended.await()
    val isEnded: Boolean get() = ended.isCompleted

    private fun nextId(): String = "r" + ids.incrementAndGet()

    /** Connect, receive `hello`, authenticate. [connectTimeoutMs] covers TCP+TLS+hello. */
    suspend fun open(connectTimeoutMs: Long = 2_000): Result<AuthOkReply> {
        socket = factory.connect(address, pc.port, pc.fingerprint, listener)
        val hello = withTimeoutOrNull(connectTimeoutMs) { helloDeferred.await() }
        if (hello == null) {
            finish(SessionEnd.Unreachable("no hello"))
            return Result.failure(EndException(ended.await()))
        }
        when (VersionCheck.evaluate(hello)) {
            VersionVerdict.PC_TOO_OLD -> finish(SessionEnd.PcTooOld)
            VersionVerdict.PHONE_TOO_OLD -> finish(SessionEnd.PhoneTooOld)
            else -> Unit
        }
        if (ended.isCompleted) return Result.failure(EndException(ended.await()))
        if (hello.pcId != null && hello.pcId != pc.pcId) {
            // Same key, different PC id: treat as a changed identity rather than guess.
            finish(SessionEnd.IdentityChanged)
            return Result.failure(EndException(ended.await()))
        }
        val id = nextId()
        authId = id
        val request = runCatching { auth.authRequest(pc, hello, id) }.getOrElse {
            finish(SessionEnd.Unreachable("auth signing failed"))
            return Result.failure(EndException(ended.await()))
        }
        authSent.set(true)
        if (socket?.send(ProtocolCodec.encode(request)) != true) {
            finish(SessionEnd.Unreachable("send failed"))
        }
        return withTimeoutOrNull(Timeouts.forType("auth")) { opened.await() }
            ?: run {
                finish(SessionEnd.Unreachable("auth timeout"))
                Result.failure(EndException(ended.await()))
            }
    }

    /** Send a request and wait for its reply. Error replies throw [RemoteException]. */
    suspend fun request(build: (String) -> Request): Inbound.Reply {
        if (ended.isCompleted) throw RemoteException(ErrorCode.INTERNAL, message = "offline")
        val id = nextId()
        val request = build(id)
        val deferred = CompletableDeferred<Inbound.Reply>()
        pending[id] = deferred
        try {
            if (socket?.send(ProtocolCodec.encode(request)) != true) {
                throw RemoteException(ErrorCode.INTERNAL, message = "offline")
            }
            val reply = withTimeout(Timeouts.forType(request.type)) { deferred.await() }
            val message = reply.message
            if (message is ErrorReply) {
                throw RemoteException(ErrorCode.fromWire(message.code), message)
            }
            if (VersionCheck.evaluate(reply) == VersionVerdict.PC_TOO_OLD) {
                finish(SessionEnd.PcTooOld)
                throw RemoteException(ErrorCode.UNSUPPORTED, message = "pc too old")
            }
            return reply
        } finally {
            pending.remove(id)
        }
    }

    fun close(code: Int = 1000) {
        socket?.close(code, "")
        finish(SessionEnd.Closed(code, 0))
    }

    private fun finish(end: SessionEnd) {
        if (!ended.complete(end)) return
        if (!opened.isCompleted) opened.complete(Result.failure(EndException(end)))
        helloDeferred.cancel()
        val error = RemoteException(ErrorCode.INTERNAL, message = "connection lost")
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        socket?.close(1000, "")
    }

    private val listener = object : WireListener {
        override fun onText(text: String) {
            when (val inbound = ProtocolCodec.decode(text)) {
                is ServerHello -> helloDeferred.complete(inbound)
                is Inbound.Reply -> onReply(inbound)
                is Inbound.Event -> _events.tryEmit(inbound.message)
                is Inbound.Malformed, is Inbound.TooLarge -> Unit // dropped; never crash
            }
        }

        override fun onClosed(code: Int, reason: String) {
            finish(endFor(code))
        }

        override fun onFailure(error: Throwable, pinMismatch: Boolean) {
            finish(if (pinMismatch) SessionEnd.IdentityChanged else SessionEnd.Unreachable(error.javaClass.simpleName))
        }
    }

    private fun onReply(reply: Inbound.Reply) {
        val id = reply.id
        if (id != null && id == authId && !opened.isCompleted) {
            when (val message = reply.message) {
                is AuthOkReply -> opened.complete(Result.success(message))
                is ErrorReply -> finish(
                    when (ErrorCode.fromWire(message.code)) {
                        ErrorCode.UNAUTHORIZED -> SessionEnd.PairingRemoved
                        ErrorCode.VERSION_TOO_OLD -> SessionEnd.PhoneTooOld
                        else -> SessionEnd.Unreachable("auth refused")
                    },
                )
                else -> finish(SessionEnd.Unreachable("unexpected auth reply"))
            }
            return
        }
        if (id == null) return
        pending[id]?.complete(reply)
    }

    private fun endFor(code: Int): SessionEnd = when (code) {
        CloseCodes.AUTH_FAILED, CloseCodes.REMOVED_OR_DENIED -> SessionEnd.PairingRemoved
        CloseCodes.PHONE_TOO_OLD -> SessionEnd.PhoneTooOld
        CloseCodes.FLOODING -> SessionEnd.Closed(code, CloseCodes.FLOOD_RETRY_MS)
        else -> SessionEnd.Closed(code, 0)
    }

    class EndException(val end: SessionEnd) : Exception(end.toString())
}
