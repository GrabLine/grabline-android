package dev.grabline.app.engine.cloud

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import javax.net.ssl.SSLContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient

/** FTP and FTPS (explicit TLS, certificate and host name checked). Resume uses REST. */
class FtpConnection(
    private val c: CloudConnection,
    secure: Boolean,
    sslContext: SSLContext? = null,
) : RemoteConnection {
    private val client: FTPClient = if (secure) {
        if (sslContext != null) FTPSClient(false, sslContext) else FTPSClient("TLS", false)
    } else {
        FTPClient()
    }
    private val secure = secure
    @Volatile private var closed = false

    override fun connect() {
        client.connectTimeout = CONNECT_MS
        client.defaultTimeout = READ_MS
        client.controlEncoding = "UTF-8"
        client.connect(c.host, c.port)
        client.soTimeout = READ_MS
        if (!FTPReply.isPositiveCompletion(client.replyCode)) throw CloudException(CloudText.UNREACHABLE)
        val user = c.user.ifEmpty { "anonymous" }
        val pass = if (c.user.isEmpty() && c.password.isEmpty()) "anonymous@" else c.password
        if (!client.login(user, pass)) throw CloudException(CloudText.AUTH)
        if (secure && client is FTPSClient) {
            client.execPBSZ(0)
            client.execPROT("P") // encrypt the data channel too, not just the command one
        }
        client.enterLocalPassiveMode()
        if (!client.setFileType(FTP.BINARY_FILE_TYPE)) throw IOException("TYPE I refused")
    }

    override fun stat(): RemoteStat {
        val size = runCatching { client.getSize(c.path)?.trim()?.toLongOrNull() }.getOrNull()
        val modified = runCatching { client.getModificationTime(c.path)?.trim() }.getOrNull().orEmpty()
        return RemoteStat(size, if (size == null && modified.isEmpty()) null else "ftp:$size:$modified")
    }

    override fun open(offset: Long, identity: String?): RemoteStream {
        var start = offset
        var stream: InputStream? = null
        if (offset > 0) {
            client.restartOffset = offset
            stream = client.retrieveFileStream(c.path)
            if (stream == null) { // the server would not resume: begin again from zero
                client.restartOffset = 0
                start = 0
            }
        }
        if (stream == null) stream = client.retrieveFileStream(c.path)
        if (stream == null) {
            if (client.replyCode in 500..559) throw CloudException(CloudText.NOT_FOUND)
            throw IOException("RETR refused (${client.replyCode})")
        }
        return RemoteStream(FinishingStream(stream), start, null, identity)
    }

    /** Closing the data stream ends the transfer politely (reads the server's final reply). */
    private inner class FinishingStream(input: InputStream) : FilterInputStream(input) {
        override fun close() {
            super.close()
            if (!closed) runCatching { client.completePendingCommand() }
        }
    }

    override fun list(): List<RemoteEntry> {
        val dir = c.path.ifEmpty { "/" }
        val mlsd = runCatching { client.mlistDir(dir) }.getOrNull()
        if (mlsd != null && mlsd.isNotEmpty()) {
            return mlsd.filter { it.isFile }.map { RemoteEntry(it.name, it.size.takeIf { s -> s >= 0 }) }
        }
        val plain = runCatching { client.listFiles(dir) }.getOrNull().orEmpty()
        if (plain.isNotEmpty()) return plain.filter { it.isFile }.map { RemoteEntry(it.name, it.size.takeIf { s -> s >= 0 }) }
        if (!FTPReply.isPositiveCompletion(client.replyCode) && client.replyCode in 500..559) throw CloudException(CloudText.NOT_FOUND)
        return emptyList()
    }

    override fun close() {
        closed = true
        runCatching { client.disconnect() }
    }

    private companion object {
        const val CONNECT_MS = 20_000
        const val READ_MS = 30_000
    }
}
