package dev.grabline.app.engine.cloud

import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/** A failure with a message that is already in plain words (shown to the user as is). */
class CloudException(text: String, cause: Throwable? = null) : IOException(text, cause)

/** Plain-words messages of server downloads (SPEC "Error states and edge cases"). */
object CloudText {
    const val UNAVAILABLE = "Server downloads aren't available on this phone"
    const val BAD_LINK = "That doesn't look like a link to a server file"
    const val AUTH = "The server refused the login. Check the user name and password under Settings > Servers"
    const val NOT_FOUND = "That file isn't on the server"
    const val UNREACHABLE = "Couldn't reach the server. Check the address and your network"
    const val TLS = "The server's security certificate isn't trusted, so the download was stopped"
    const val EMPTY_FOLDER = "There are no files in that folder"
    const val PLAIN_HTTP = "This phone doesn't allow plain webdav:// connections. Use webdavs:// instead"
    const val DROPPED = "The server closed the connection before the file was complete"
    const val SHORT = "The server sent less than the full file, so it wasn't saved. Try again"
    const val NO_KEY = "That saved private key can't be read. Check the key and its passphrase under Settings > Servers"

    fun hostKeyChanged(host: String, expected: String, got: String) =
        "The security key of $host has changed (was $expected, now $got). Someone may be pretending to be that server. " +
            "If you changed it yourself, remove it under Settings > Servers and try again"

    fun tooMany(max: Int) = "Only the first $max files of that folder were added"
}

/** Everything needed to open one connection: the link's parts merged with the saved account. Secrets are never printed. */
class CloudConnection(
    val scheme: String,
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
    val privateKey: String,
    val passphrase: String,
    val endpoint: String,
    val region: String,
    val path: String,
    val rawPath: String,
    val query: String?,
) {
    override fun toString() = "CloudConnection($scheme://$host:$port)"
}

/** Merges a link with the saved accounts: an explicit port in the link beats the saved one, a typed password beats the saved one. */
object CloudResolver {
    suspend fun resolve(target: CloudTarget, inlineSecret: String?, store: CredentialStore?): CloudConnection {
        val inline = inlineSecret?.takeIf { it.isNotEmpty() } ?: target.password.orEmpty()
        val account = store?.accountFor(target.scheme, target.host, target.user)
        val secrets = if (account != null && store != null) store.secretsFor(account) else null
        val user = account?.username?.takeIf { it.isNotEmpty() } ?: target.user
        val port = target.port ?: account?.port?.takeIf { it > 0 } ?: CloudSchemes.defaultPort(target.scheme)
        val usesKey = inline.isEmpty() && secrets?.privateKey?.isNotEmpty() == true && target.scheme in setOf("sftp", "scp")
        return CloudConnection(
            scheme = target.scheme, host = target.host, port = port, user = user,
            password = inline.ifEmpty { if (usesKey) "" else secrets?.password.orEmpty() },
            privateKey = if (usesKey) secrets?.privateKey.orEmpty() else "",
            passphrase = if (usesKey) secrets?.passphrase.orEmpty() else "",
            endpoint = account?.endpoint.orEmpty(), region = account?.region.orEmpty(),
            path = target.path, rawPath = target.rawPath, query = target.query,
        )
    }
}

class RemoteStat(val size: Long?, val identity: String?)

/** A file stream that starts at [startOffset] (0 when the server could not resume). [total] and [identity] are what the server said. */
class RemoteStream(val input: InputStream, val startOffset: Long, val total: Long?, val identity: String?)

class RemoteEntry(val name: String, val size: Long?, val fullPath: String? = null)

/**
 * One server connection. Every method blocks; [close] may be called from any thread and aborts whatever is in flight
 * (that is how pause and cancel stop a transfer).
 */
interface RemoteConnection : Closeable {
    fun connect()
    fun stat(): RemoteStat
    /** Opens the file at [offset]; [identity] is what the first run saw (used to notice a replaced file). */
    fun open(offset: Long, identity: String?): RemoteStream
    fun list(): List<RemoteEntry>
}

/** Seam between the engine and the protocols (tests swap it). */
interface CloudBackend {
    /** Seals the password typed in a link so it can be stored with the job. */
    fun sealInline(password: String): String?
    suspend fun resolve(url: String, sealedSecret: String?): CloudConnection
    fun create(connection: CloudConnection): RemoteConnection
}
