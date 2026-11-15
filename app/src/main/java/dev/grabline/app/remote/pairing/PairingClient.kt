package dev.grabline.app.remote.pairing

import dev.grabline.app.remote.protocol.Base64Url
import dev.grabline.app.remote.protocol.CloseCodes
import dev.grabline.app.remote.protocol.ErrorCode
import dev.grabline.app.remote.protocol.ErrorMessages
import dev.grabline.app.remote.protocol.ErrorReply
import dev.grabline.app.remote.protocol.Inbound
import dev.grabline.app.remote.protocol.ManualPairingData
import dev.grabline.app.remote.protocol.PairPendingEvent
import dev.grabline.app.remote.protocol.PairRequest
import dev.grabline.app.remote.protocol.PairedReply
import dev.grabline.app.remote.protocol.PairingData
import dev.grabline.app.remote.protocol.ProtocolCodec
import dev.grabline.app.remote.protocol.ServerHello
import dev.grabline.app.remote.protocol.Timeouts
import dev.grabline.app.remote.protocol.VersionCheck
import dev.grabline.app.remote.protocol.VersionVerdict
import dev.grabline.app.remote.security.DeviceKeyStore
import dev.grabline.app.remote.security.Pin
import dev.grabline.app.remote.security.PinMismatchException
import dev.grabline.app.remote.security.PairingProofs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Progress the pairing screen shows while [PairingClient.pair] runs. */
sealed interface PairingProgress {
    data class Connecting(val address: String) : PairingProgress

    /** The proof is on its way; nothing to show yet. */
    data object SendingProof : PairingProgress

    /** `pairPending`: the PC is showing its Allow prompt. */
    data class ConfirmOnPc(val timeoutSeconds: Int) : PairingProgress {
        val userMessage: String get() = ErrorMessages.CONFIRM_ON_PC
    }
}

/** How pairing ended. Every outcome but [Paired] leaves nothing behind: no key, no pin, no open socket. */
sealed interface PairResult {
    /** Plain words for the user; null for [Paired]. */
    val userMessage: String?

    /**
     * `serverProof` verified. The caller stores the pairing (pin = [pin]) and then logs in on [connection]
     * with [hello] (the same nonce), or closes it. The device key is in the key store under [keyAlias].
     */
    class Paired(
        val deviceId: String,
        val pcId: String,
        val pcName: String,
        /** The PC key's pin: from the QR, or the key seen during manual pairing. */
        val pin: String,
        val addresses: List<String>,
        val port: Int,
        /** The address that worked (`lastGoodAddress`). */
        val address: String,
        val protocolVersion: Int,
        val hello: ServerHello,
        val connection: Connection,
        /** Keystore alias of the device key made for this attempt; stored on the pairing row. */
        val keyAlias: String,
    ) : PairResult {
        override val userMessage: String? get() = null
        override fun toString(): String = "Paired(deviceId=$deviceId, pcId=$pcId, pcName=$pcName, address=$address, port=$port)"
    }

    /** `pairingDenied` or close 4403 (QA-04). */
    data object PcDeclined : PairResult {
        override val userMessage: String get() = ErrorMessages.forCode(ErrorCode.PAIRING_DENIED)
    }

    /** `pairingExpired`, or nothing answered and the phone's clock is past the QR's expiry (QA-02). */
    data object CodeExpired : PairResult {
        override val userMessage: String get() = ErrorMessages.forCode(ErrorCode.PAIRING_EXPIRED)
    }

    /** `pairingLocked` (QA-03). */
    data object CodeLocked : PairResult {
        override val userMessage: String get() = ErrorMessages.forCode(ErrorCode.PAIRING_LOCKED)
    }

    /** `badCode`: the PC did not accept the proof. [attemptsLeft] is null when the PC did not say. */
    data class BadCode(val attemptsLeft: Int?) : PairResult {
        override val userMessage: String get() = ErrorMessages.forCode(ErrorCode.BAD_CODE)
    }

    /** The PC's `serverProof` (or its `paired` reply) did not verify: it does not know the code. Nothing was stored. */
    data object ProofMismatch : PairResult {
        override val userMessage: String get() = ErrorMessages.forCode(ErrorCode.BAD_CODE)
    }

    /** QR pairing only: something answered at the PC's address with another key. */
    data object IdentityMismatch : PairResult {
        override val userMessage: String get() = PinMismatchException.USER_MESSAGE
    }

    data object PcTooOld : PairResult {
        override val userMessage: String get() = ErrorMessages.PC_TOO_OLD
    }

    /** `hello.minClientVersion` above ours, `versionTooOld`, or close 4426. */
    data object PhoneTooOld : PairResult {
        override val userMessage: String get() = ErrorMessages.PHONE_TOO_OLD
    }

    /** No address answered. */
    data object PcNotFound : PairResult {
        override val userMessage: String get() = ErrorMessages.PC_NOT_FOUND
    }

    /** No reply in time (90 s for `pair`), or no `hello`. */
    data object TimedOut : PairResult {
        override val userMessage: String get() = "Your PC took too long to answer. Try again"
    }

    /** The connection dropped mid-pairing. */
    data object ConnectionLost : PairResult {
        override val userMessage: String get() = "The connection to your PC dropped. Try again"
    }

    /** Any other error reply (for example `conflict`: 8 devices already paired), or a malformed exchange (`internal`). */
    data class Rejected(val code: ErrorCode) : PairResult {
        override val userMessage: String get() = ErrorMessages.forCode(code)
    }

    /** This phone could not create its device key. */
    data object KeyUnavailable : PairResult {
        override val userMessage: String get() = "This phone couldn't create a secure key. Try again"
    }
}

/**
 * The phone side of pairing (PROTOCOL.md section 5.5):
 * connect (pin from the QR; or accept-and-record for manual entry), read `hello`, create the device key,
 * send `pair` with `codeProof`, wait for `pairPending` / `paired`, verify `serverProof`.
 *
 * Fail closed: the key is deleted and the socket closed on every path except a verified `paired`,
 * including cancellation. The code, proofs, nonce and keys are never logged.
 */
class PairingClient(
    private val transport: Transport,
    private val keyStore: DeviceKeyStore,
    deviceName: String,
    appVersion: String? = null,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val keyDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val helloTimeoutMs: Long = HELLO_TIMEOUT_MS,
    private val pairTimeoutMs: Long = Timeouts.PAIR_MS,
) {
    private val deviceName: String =
        deviceName.filter { !it.isISOControl() }.trim().take(64).trim().ifEmpty { "Android phone" }
    private val appVersion: String? = appVersion?.take(32)

    /** Pair from a scanned QR. Addresses are tried one by one, [lastGoodAddress] first. */
    suspend fun pair(
        data: PairingData,
        lastGoodAddress: String? = null,
        onProgress: (PairingProgress) -> Unit = {},
    ): PairResult {
        val ordered = (listOfNotNull(lastGoodAddress?.takeIf { it in data.addresses }) + data.addresses).distinct()
        return run(
            addresses = ordered,
            knownAddresses = data.addresses,
            port = data.port,
            code = data.code,
            expectedPin = data.pin,
            expectedPcId = data.pcId,
            expiry = data.expiry,
            onProgress = onProgress,
        )
    }

    /** Pair from a typed address and code: the key the PC presents becomes the pin only if the proofs verify. */
    suspend fun pair(data: ManualPairingData, onProgress: (PairingProgress) -> Unit = {}): PairResult = run(
        addresses = listOf(data.address),
        knownAddresses = listOf(data.address),
        port = data.port,
        code = data.code,
        expectedPin = null,
        expectedPcId = null,
        expiry = null,
        onProgress = onProgress,
    )

    private suspend fun run(
        addresses: List<String>,
        knownAddresses: List<String>,
        port: Int,
        code: String,
        expectedPin: String?,
        expectedPcId: String?,
        expiry: Long?,
        onProgress: (PairingProgress) -> Unit,
    ): PairResult {
        val trust = if (expectedPin != null) TrustMode.Pinned(expectedPin) else TrustMode.ManualPairing
        var wrongKey = false
        for (address in addresses) {
            onProgress(PairingProgress.Connecting(address))
            val connection = try {
                transport.connect(address, port, trust)
            } catch (e: CancellationException) {
                throw e
            } catch (_: PinMismatchException) {
                wrongKey = true
                continue
            } catch (_: Exception) {
                continue
            }
            // Something with the right key answered: this is the PC, and its answer is final.
            return exchange(connection, address, knownAddresses, port, code, expectedPin, expectedPcId, onProgress)
        }
        return when {
            wrongKey -> PairResult.IdentityMismatch
            expiry != null && nowSeconds() >= expiry -> PairResult.CodeExpired
            else -> PairResult.PcNotFound
        }
    }

    private suspend fun exchange(
        connection: Connection,
        address: String,
        knownAddresses: List<String>,
        port: Int,
        code: String,
        expectedPin: String?,
        expectedPcId: String?,
        onProgress: (PairingProgress) -> Unit,
    ): PairResult {
        var keyFor: String? = null
        var keep = false
        try {
            // In QR mode the transport already enforced the pin; check again here so a transport bug fails closed.
            val pin = connection.peerPin
            if (expectedPin != null && !Pin.matches(expectedPin, pin)) return PairResult.IdentityMismatch
            val pinBytes = Pin.bytes(pin) ?: return PairResult.Rejected(ErrorCode.INTERNAL)

            val hello = when (val first = withTimeoutOrNull(helloTimeoutMs) { connection.receive() }) {
                null -> return PairResult.TimedOut
                is Frame.Closed -> return closed(first.code)
                is Frame.Failed -> return PairResult.ConnectionLost
                is Frame.Text -> ProtocolCodec.decode(first.text) as? ServerHello ?: return PairResult.Rejected(ErrorCode.INTERNAL)
            }
            when (VersionCheck.evaluate(hello)) {
                VersionVerdict.PC_TOO_OLD -> return PairResult.PcTooOld // nothing is sent; closed 1000 below
                VersionVerdict.PHONE_TOO_OLD -> return PairResult.PhoneTooOld
                VersionVerdict.SUPPORTED -> Unit
            }
            val nonce = hello.nonceBytes() ?: return PairResult.Rejected(ErrorCode.INTERNAL)
            val pcId = hello.pcId?.takeIf { isId(it) } ?: return PairResult.Rejected(ErrorCode.INTERNAL)
            if (expectedPcId != null && pcId != expectedPcId) return PairResult.Rejected(ErrorCode.INTERNAL)

            // A fresh alias per attempt: a re-pair never touches the key of a pairing that still works.
            val alias = DeviceKeyStore.newAlias(pcId)
            keyFor = alias
            val spki = try {
                withContext(keyDispatcher) { keyStore.create(alias) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return PairResult.KeyUnavailable
            }

            val frame = try {
                val proof = PairingProofs.codeProof(code, nonce, pinBytes, spki)
                ProtocolCodec.encode(
                    PairRequest(
                        id = REQUEST_ID,
                        deviceName = deviceName,
                        publicKey = Base64Url.encode(spki),
                        codeProof = Base64Url.encode(proof),
                        appVersion = appVersion,
                    ),
                )
            } catch (_: IllegalArgumentException) {
                return PairResult.Rejected(ErrorCode.INTERNAL)
            }
            onProgress(PairingProgress.SendingProof)
            if (!connection.send(frame)) return PairResult.ConnectionLost

            val result = withTimeoutOrNull(pairTimeoutMs) {
                awaitVerdict(connection, hello, pcId, alias, code, nonce, pinBytes, spki, pin, address, knownAddresses, port, onProgress)
            } ?: PairResult.TimedOut
            keep = result is PairResult.Paired
            return result
        } finally {
            if (!keep) {
                keyFor?.let { runCatching { keyStore.delete(it) } }
                runCatching { connection.close(CloseCodes.NORMAL) }
            }
        }
    }

    private suspend fun awaitVerdict(
        connection: Connection,
        hello: ServerHello,
        pcId: String,
        keyAlias: String,
        code: String,
        nonce: ByteArray,
        pinBytes: ByteArray,
        spki: ByteArray,
        pin: String,
        address: String,
        knownAddresses: List<String>,
        port: Int,
        onProgress: (PairingProgress) -> Unit,
    ): PairResult {
        var ignored = 0
        while (true) {
            val text = when (val frame = connection.receive()) {
                is Frame.Closed -> return closed(frame.code)
                is Frame.Failed -> return PairResult.ConnectionLost
                is Frame.Text -> frame.text
            }
            when (val inbound = ProtocolCodec.decode(text)) {
                is Inbound.Event -> {
                    val event = inbound.message
                    if (event is PairPendingEvent) onProgress(PairingProgress.ConfirmOnPc(event.timeoutSeconds)) else ignored++
                }
                is Inbound.Reply -> {
                    val message = inbound.message
                    when {
                        // Before authOk only `pair` is pending, so an error without an id is its answer too.
                        message is ErrorReply && (inbound.id == null || inbound.id == REQUEST_ID) -> return refused(message)
                        message is PairedReply && inbound.id == REQUEST_ID -> {
                            if (VersionCheck.evaluate(inbound) == VersionVerdict.PC_TOO_OLD) return PairResult.PcTooOld
                            return verify(message, connection, hello, pcId, keyAlias, code, nonce, pinBytes, spki, pin, address, knownAddresses, port, inbound.protocolVersion)
                        }
                        else -> ignored++
                    }
                }
                else -> ignored++ // malformed, oversize or a second hello: dropped
            }
            if (ignored > MAX_IGNORED_FRAMES) return PairResult.Rejected(ErrorCode.INTERNAL)
        }
    }

    /** Checks `paired`. `serverProof` is verified (constant time) before anything from the reply is used. */
    private fun verify(
        reply: PairedReply,
        connection: Connection,
        hello: ServerHello,
        pcId: String,
        keyAlias: String,
        code: String,
        nonce: ByteArray,
        pinBytes: ByteArray,
        spki: ByteArray,
        pin: String,
        address: String,
        knownAddresses: List<String>,
        port: Int,
        protocolVersion: Int?,
    ): PairResult {
        val proof = Base64Url.decode(reply.serverProof)
        if (!isId(reply.deviceId) || !PairingProofs.verifyServerProof(proof, code, nonce, pinBytes, spki, reply.deviceId)) {
            return PairResult.ProofMismatch
        }
        if (reply.pcId != pcId) return PairResult.ProofMismatch
        val name = cleanName(reply.pcName) ?: cleanName(hello.pcName) ?: "PC"
        return PairResult.Paired(
            deviceId = reply.deviceId,
            pcId = pcId,
            pcName = name,
            pin = pin,
            addresses = knownAddresses,
            port = port,
            address = address,
            protocolVersion = protocolVersion ?: hello.protocolVersion ?: 0,
            hello = hello,
            connection = connection,
            keyAlias = keyAlias,
        )
    }

    private fun refused(error: ErrorReply): PairResult = when (error.errorCode) {
        ErrorCode.PAIRING_EXPIRED -> PairResult.CodeExpired
        ErrorCode.PAIRING_LOCKED -> PairResult.CodeLocked
        ErrorCode.PAIRING_DENIED -> PairResult.PcDeclined
        ErrorCode.BAD_CODE -> PairResult.BadCode(error.attemptsLeft?.takeIf { it >= 0 })
        ErrorCode.VERSION_TOO_OLD -> PairResult.PhoneTooOld
        else -> PairResult.Rejected(error.errorCode)
    }

    private fun closed(code: Int): PairResult = when (code) {
        CloseCodes.REMOVED_OR_DENIED -> PairResult.PcDeclined
        CloseCodes.PHONE_TOO_OLD -> PairResult.PhoneTooOld
        else -> PairResult.ConnectionLost
    }

    private fun cleanName(name: String?): String? =
        name?.filter { !it.isISOControl() }?.trim()?.take(64)?.trim()?.takeIf { it.isNotEmpty() }

    private fun isId(id: String): Boolean = id.length == 22 && Base64Url.decode(id)?.size == 16

    companion object {
        const val REQUEST_ID = "pair-1"
        const val HELLO_TIMEOUT_MS = 10_000L
        private const val MAX_IGNORED_FRAMES = 8
    }
}
