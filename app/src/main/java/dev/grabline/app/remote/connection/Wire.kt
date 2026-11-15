package dev.grabline.app.remote.connection

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** One WebSocket to one PC address. Implementations must be thread-safe. */
interface WireSocket {
    /** Queue a text frame; false if the socket is closing or closed. */
    fun send(text: String): Boolean
    fun close(code: Int, reason: String)
}

/** Callbacks from a [WireSocket]; called on a background thread. */
interface WireListener {
    fun onOpen() {}
    fun onText(text: String)
    fun onBinary(bytes: ByteArray) {}
    /** The PC closed (or we did): [code] is the WebSocket close code. */
    fun onClosed(code: Int, reason: String)
    /** The connection failed before or after opening. [pinMismatch] = the PC's key is not the pinned one. */
    fun onFailure(error: Throwable, pinMismatch: Boolean)
}

/** Opens sockets to `wss://address:port/remote` with the PC's pin enforced. */
fun interface WireFactory {
    fun connect(address: String, port: Int, pin: String, listener: WireListener): WireSocket
}

/** Formats an address for a URL: IPv6 in brackets, scope id percent-encoded. */
fun urlHost(address: String): String {
    val bare = address.removePrefix("[").removeSuffix("]")
    return if (bare.contains(':')) "[" + bare.replace("%25", "%").replace("%", "%25") + "]" else bare
}

/**
 * OkHttp transport. [clientForPin] returns a client whose TLS accepts only the
 * PC key with that pin (see remote/security). [isPinMismatch] recognises the
 * trust manager's mismatch error anywhere in a failure's cause chain.
 */
class OkHttpWireFactory(
    private val clientForPin: (String) -> OkHttpClient,
    private val isPinMismatch: (Throwable) -> Boolean,
) : WireFactory {
    override fun connect(address: String, port: Int, pin: String, listener: WireListener): WireSocket {
        val request = Request.Builder().url("https://${urlHost(address)}:$port/remote").build()
        val socket = clientForPin(pin).newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onBinary(bytes.toByteArray())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code.coerceIn(1000, 4999).let { if (it == 1005 || it == 1006) 1000 else it }, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed(code, reason)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                listener.onFailure(t, causeChain(t).any(isPinMismatch))
        })
        return object : WireSocket {
            override fun send(text: String): Boolean = socket.send(text)
            override fun close(code: Int, reason: String) {
                if (!socket.close(code, reason)) socket.cancel()
            }
        }
    }

    private fun causeChain(t: Throwable): Sequence<Throwable> = generateSequence(t) { it.cause }.take(16)
}
