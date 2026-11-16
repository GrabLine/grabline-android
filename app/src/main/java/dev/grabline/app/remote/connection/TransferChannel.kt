package dev.grabline.app.remote.connection

import dev.grabline.app.remote.protocol.Inbound
import dev.grabline.app.remote.protocol.Request
import dev.grabline.app.remote.protocol.ServerEvent
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.SharedFlow

/**
 * An authenticated connection used for file transfers: the PC's "second connection" so that `fetch` frames never
 * delay events on the main one (PROTOCOL.md 13). [PcSession] is the production implementation.
 */
interface TransferChannel {
    suspend fun request(build: (String) -> Request): Inbound.Reply
    /** Send a message that has no reply (`fetchAck`); false when the connection is gone. */
    fun notify(request: Request): Boolean
    val events: SharedFlow<ServerEvent>
    val frames: ReceiveChannel<ByteArray>
    val isEnded: Boolean
    suspend fun awaitEnd(): SessionEnd
    fun close()
}

/** Opens transfer connections to a paired PC, or null when it cannot be reached (or refuses this phone). */
fun interface TransferConnector {
    suspend fun open(pcId: String): TransferChannel?
}
