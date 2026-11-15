package dev.grabline.app.remote.pairing

import dev.grabline.app.remote.protocol.CloseCodes
import dev.grabline.app.remote.protocol.MAX_FRAME_BYTES
import dev.grabline.app.remote.security.Pin
import dev.grabline.app.remote.security.ManualPairingTrustManager
import dev.grabline.app.remote.security.PinMismatchException
import dev.grabline.app.remote.security.PinnedTrustManager
import dev.grabline.app.remote.security.manualPairingOkHttpClient
import dev.grabline.app.remote.security.pinnedOkHttpClient
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** What arrives on a [Connection]. Frame text may hold proofs, so it is kept out of [toString]. */
sealed interface Frame {
    class Text(val text: String) : Frame {
        override fun toString(): String = "Frame.Text(${text.length} chars)"
    }

    /** The WebSocket closed with [code]. Terminal. */
    data class Closed(val code: Int) : Frame

    /** The connection broke. Terminal. */
    class Failed(val cause: Throwable) : Frame {
        override fun toString(): String = "Frame.Failed(${cause.javaClass.simpleName})"
    }
}

/** How the PC's TLS key is judged for one connection. */
sealed interface TrustMode {
    /** Normal case: only the key with this pin is accepted. */
    data class Pinned(val pin: String) : TrustMode

    /** Manual pairing only (PROTOCOL.md section 5.4): accept the key and report its hash as the candidate pin. */
    data object ManualPairing : TrustMode
}

/** One open WebSocket to a PC. Thread-safe. */
interface Connection {
    /** Pin of the key the PC presented in THIS connection's TLS handshake. */
    val peerPin: String

    /** The next frame. After a terminal frame ([Frame.Closed], [Frame.Failed]) it keeps returning that frame. */
    suspend fun receive(): Frame

    /** Queues a text frame; false when the socket is closing or closed. */
    fun send(text: String): Boolean

    fun close(code: Int = CloseCodes.NORMAL)
}

/** Opens `wss://address:port/remote`. Exists so the pairing state machine can be tested without TLS. */
fun interface Transport {
    /**
     * [address] is in QR notation (dotted IPv4, `[ipv6]`, or a host name).
     * @throws PinMismatchException the PC's key is not the pinned one
     * @throws IOException the PC could not be reached, or refused the WebSocket handshake
     */
    suspend fun connect(address: String, port: Int, trust: TrustMode): Connection
}

/** The real transport: OkHttp WebSocket over the pinned client (see `pinnedOkHttpClient`). */
class OkHttpTransport(private val base: OkHttpClient) : Transport {

    override suspend fun connect(address: String, port: Int, trust: TrustMode): Connection {
        val client = when (trust) {
            is TrustMode.Pinned -> pinnedOkHttpClient(base, trust.pin)
            TrustMode.ManualPairing -> manualPairingOkHttpClient(base)
        }
        val request = try {
            Request.Builder().url("https://$address:$port/remote").build()
        } catch (e: IllegalArgumentException) {
            throw IOException("not a usable address", e)
        }
        val frames = Channel<Frame>(FRAME_BUFFER)
        val terminal = AtomicReference<Frame?>(null)
        val opened = CompletableDeferred<String>()

        fun end(frame: Frame) {
            terminal.compareAndSet(null, frame)
            frames.trySend(frame)
        }

        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // The pin comes from this connection's own trust manager (one client per connection, so no
                    // TLS session is ever resumed). Handshake.peerCertificates is not used: OkHttp's chain
                    // cleaner refuses self-signed certificates.
                    val seen: String? = when (val tm = client.x509TrustManager) {
                        is PinnedTrustManager -> if (tm.acceptedPinnedKey && trust is TrustMode.Pinned) trust.pin else null
                        is ManualPairingTrustManager -> if (trust is TrustMode.ManualPairing) tm.seenPin else null
                        else -> null
                    }
                    val problem: Throwable? = when {
                        seen == null || response.handshake == null -> IOException("no verified TLS peer key")
                        // Defence in depth: the trust manager already refused a wrong key during the handshake.
                        trust is TrustMode.Pinned && !Pin.matches(trust.pin, seen) -> PinMismatchException()
                        else -> null
                    }
                    if (problem != null) {
                        opened.completeExceptionally(problem)
                        webSocket.cancel()
                    } else {
                        opened.complete(seen!!)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.length > MAX_FRAME_BYTES || !frames.trySend(Frame.Text(text)).isSuccess) {
                        // Oversized frame, or the PC is sending faster than anyone reads: give up on it.
                        end(Frame.Failed(IOException("too much data from the PC")))
                        webSocket.cancel()
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    end(Frame.Closed(code))
                    val echo = if (code in 1000..4999 && code != 1005 && code != 1006) code else CloseCodes.NORMAL
                    runCatching { webSocket.close(echo, null) }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = end(Frame.Closed(code))

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val error: Throwable = PinMismatchException.findIn(t) ?: (t as? IOException) ?: IOException("connection failed", t)
                    if (!opened.completeExceptionally(error)) end(Frame.Failed(error))
                }
            },
        )

        val peerPin = try {
            opened.await()
        } catch (t: Throwable) {
            socket.cancel()
            throw t
        }

        return object : Connection {
            override val peerPin: String = peerPin

            override suspend fun receive(): Frame {
                frames.tryReceive().getOrNull()?.let { return it }
                terminal.get()?.let { return it }
                return frames.receive()
            }

            override fun send(text: String): Boolean = terminal.get() == null && socket.send(text)

            override fun close(code: Int) {
                val closed = runCatching { socket.close(code, null) }.getOrDefault(false)
                if (!closed) socket.cancel()
            }
        }
    }

    private companion object {
        const val FRAME_BUFFER = 64
    }
}
