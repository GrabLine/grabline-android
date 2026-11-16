package dev.grabline.app.engine.cloud

import okhttp3.OkHttpClient

/** Opens the right connection for a link's scheme, with logins from the [store]. */
class DefaultCloudBackend(
    private val store: CredentialStore,
    private val hostKeys: HostKeyStore,
    private val http: OkHttpClient,
) : CloudBackend {
    override fun sealInline(password: String): String? = runCatching { store.sealInline(password) }.getOrNull()

    override suspend fun resolve(url: String, sealedSecret: String?): CloudConnection {
        val target = CloudUrl.parse(url) ?: throw CloudException(CloudText.BAD_LINK)
        val inline = sealedSecret?.let { store.openInline(it) }
        return CloudResolver.resolve(target, inline, store)
    }

    override fun create(connection: CloudConnection): RemoteConnection = when (connection.scheme) {
        "ftp" -> FtpConnection(connection, secure = false)
        "ftps" -> FtpConnection(connection, secure = true)
        "sftp", "scp" -> SftpConnection(connection, hostKeys)
        "s3" -> S3Connection(connection, http)
        "webdav", "webdavs" -> WebDavConnection(connection, http)
        else -> throw CloudException(CloudText.BAD_LINK)
    }
}
