package dev.grabline.app.engine.cloud

import dev.grabline.app.data.SshHostKeyDao
import dev.grabline.app.data.SshHostKeyRow
import java.io.IOException
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Security
import java.util.Base64
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import org.bouncycastle.jce.provider.BouncyCastleProvider

/** Where trusted SSH host keys live. */
interface HostKeyStore {
    fun find(host: String, port: Int): StoredHostKey?
    fun put(host: String, port: Int, keyType: String, sha256: String)
}

data class StoredHostKey(val keyType: String, val sha256: String)

class RoomHostKeyStore(private val dao: SshHostKeyDao, private val clock: () -> Long = System::currentTimeMillis) : HostKeyStore {
    override fun find(host: String, port: Int) = dao.find(host, port)?.let { StoredHostKey(it.keyType, it.sha256) }
    override fun put(host: String, port: Int, keyType: String, sha256: String) =
        dao.put(SshHostKeyRow(host, port, keyType, sha256, clock()))
}

/**
 * Trust on first use: the first key a server shows is recorded (SHA-256 fingerprint); a different key afterwards is
 * refused and reported through [changed]. Nothing is ever replaced silently.
 */
class TofuHostKeyVerifier(private val store: HostKeyStore) : HostKeyVerifier {
    /** Set when a stored key did not match: (stored, offered). */
    @Volatile var changed: Pair<String, String>? = null

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val offered = fingerprint(key)
        val stored = store.find(hostname, port)
        if (stored == null) {
            store.put(hostname, port, KeyType.fromKey(key).toString(), offered)
            return true
        }
        if (stored.sha256 == offered) return true
        changed = stored.sha256 to offered
        return false
    }

    /** Ask the server for the key type we already trust first. */
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
        store.find(hostname, port)?.let { listOf(it.keyType) }.orEmpty()

    companion object {
        /** OpenSSH-style "SHA256:..." of the key. */
        fun fingerprint(key: PublicKey): String {
            val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
        }
    }
}

/** SFTP (and scp:// links, which use the same SFTP channel as the desktop app). Resume seeks to the saved offset. */
class SftpConnection(private val c: CloudConnection, hostKeys: HostKeyStore) : RemoteConnection {
    private val verifier = TofuHostKeyVerifier(hostKeys)
    private val ssh: SSHClient
    private var sftp: SFTPClient? = null

    init {
        ensureBouncyCastle()
        ssh = SSHClient()
    }

    override fun connect() {
        ssh.connectTimeout = CONNECT_MS
        ssh.timeout = READ_MS
        ssh.addHostKeyVerifier(verifier)
        try {
            ssh.connect(c.host, c.port)
            val user = c.user.ifEmpty { "anonymous" }
            if (c.privateKey.isNotEmpty()) {
                val finder = object : PasswordFinder {
                    override fun reqPassword(resource: Resource<*>?) = c.passphrase.toCharArray()
                    override fun shouldRetry(resource: Resource<*>?) = false
                }
                val keys = try {
                    ssh.loadKeys(c.privateKey, null, if (c.passphrase.isEmpty()) null else finder)
                } catch (e: IOException) {
                    throw CloudException(CloudText.NO_KEY, e)
                }
                ssh.authPublickey(user, keys)
            } else {
                ssh.authPassword(user, c.password)
            }
            sftp = ssh.newSFTPClient()
        } catch (e: UserAuthException) {
            throw CloudException(CloudText.AUTH, e)
        } catch (e: IOException) {
            verifier.changed?.let { (was, now) -> throw CloudException(CloudText.hostKeyChanged(c.host, was, now), e) }
            throw e
        }
    }

    private fun client() = sftp ?: throw IOException("not connected")

    override fun stat(): RemoteStat {
        val attrs = try {
            client().stat(c.path)
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            throw CloudException(CloudText.NOT_FOUND, e)
        }
        val mtime = attrs.mtime
        return RemoteStat(attrs.size, "sftp:${attrs.size}:$mtime")
    }

    override fun open(offset: Long, identity: String?): RemoteStream {
        val file: RemoteFile = client().open(c.path)
        val stream = file.ReadAheadRemoteFileInputStream(16, offset)
        return RemoteStream(
            object : java.io.FilterInputStream(stream) {
                override fun close() { super.close(); runCatching { file.close() } }
            },
            offset, null, identity,
        )
    }

    override fun list(): List<RemoteEntry> {
        val dir = c.path.ifEmpty { "/" }
        return try {
            client().ls(dir).filter { it.attributes.type == FileMode.Type.REGULAR }.map { RemoteEntry(it.name, it.attributes.size) }
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            throw CloudException(CloudText.NOT_FOUND, e)
        }
    }

    override fun close() {
        runCatching { ssh.disconnect() }
    }

    companion object {
        private const val CONNECT_MS = 20_000
        private const val READ_MS = 30_000

        /** Android ships a cut-down "BC"; sshj needs the full BouncyCastle, so ours replaces it once. */
        @Synchronized
        fun ensureBouncyCastle() {
            val current = Security.getProvider("BC")
            if (current is BouncyCastleProvider) return
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }
}
