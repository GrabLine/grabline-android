package dev.grabline.app.engine.cloud

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.grabline.app.data.CloudAccountDao
import dev.grabline.app.data.CloudAccountRow
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Seals and opens small secrets. The real one uses the Android Keystore; tests use a software key. */
interface SecretCipher {
    /** Base64 text safe to store. */
    fun seal(plain: String): String

    /** The plain text, or null when the text is damaged or its key is gone (the user then types the secret again). */
    fun open(sealed: String): String?
}

/** AES-256-GCM with a fresh random 12-byte IV per message; the IV leads the stored bytes. */
class AesGcmSecretCipher(private val key: () -> SecretKey) : SecretCipher {
    private val random = SecureRandom()

    override fun seal(plain: String): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    override fun open(sealed: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(sealed)
        require(bytes.size > IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)))
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** The app's cipher: a non-exportable AES key that lives in the Android Keystore. */
object KeystoreSecretCipher {
    private const val ALIAS = "grabline-cloud-secrets"

    fun create(): SecretCipher = AesGcmSecretCipher { key() }

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(false) // we supply our own random IV (the IV leads the stored bytes)
                .build(),
        )
        return gen.generateKey()
    }
}

/** What an account keeps secret. */
@Serializable
data class AccountSecrets(
    /** Password (SFTP, FTP, FTPS, WebDAV) or the S3 secret key. */
    val password: String = "",
    /** SFTP private key in PEM/OpenSSH text. */
    val privateKey: String = "",
    /** Passphrase of that key. */
    val passphrase: String = "",
) {
    val isEmpty: Boolean get() = password.isEmpty() && privateKey.isEmpty() && passphrase.isEmpty()

    // Never printed: a stray log of this object must not leak anything.
    override fun toString() = "AccountSecrets(***)"
}

/** A saved server login without its secrets. */
data class CloudAccount(
    val id: Long = 0,
    val service: String,
    val host: String,
    val port: Int = 0,
    val username: String = "",
    val label: String = "",
    val endpoint: String = "",
    val region: String = "",
    val hasSecrets: Boolean = false,
) {
    companion object {
        val SERVICES = listOf("sftp", "ftp", "ftps", "webdav", "s3")
    }
}

/** Accounts in Room with secrets sealed by [cipher]. */
class CredentialStore(private val dao: CloudAccountDao, private val cipher: SecretCipher) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun list(): List<CloudAccount> = dao.all().map { it.toAccount() }

    /** Adds the account, or updates the one with the same service, host and user. A null [secrets] keeps the saved ones. */
    suspend fun save(account: CloudAccount, secrets: AccountSecrets?): Long {
        val existing = dao.all().firstOrNull {
            it.id == account.id && account.id != 0L ||
                it.service == account.service && it.host == account.host && it.username == account.username
        }
        val sealed = when {
            secrets == null -> existing?.secrets
            secrets.isEmpty -> null
            else -> cipher.seal(json.encodeToString(AccountSecrets.serializer(), secrets))
        }
        val row = CloudAccountRow(
            id = existing?.id ?: 0, service = account.service, host = account.host, port = account.port,
            username = account.username, label = account.label, endpoint = account.endpoint, region = account.region, secrets = sealed,
        )
        return if (existing != null) { dao.update(row); existing.id } else dao.insert(row)
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun secretsFor(account: CloudAccount): AccountSecrets? {
        val sealed = dao.get(account.id)?.secrets ?: return null
        val plain = cipher.open(sealed) ?: return null
        return runCatching { json.decodeFromString(AccountSecrets.serializer(), plain) }.getOrNull()
    }

    /**
     * The saved account that best fits a link: same service and host, an exact user match first, otherwise the first.
     * webdavs shares webdav accounts, scp shares sftp accounts, and an S3 account named "*" fits any bucket.
     */
    suspend fun accountFor(service: String, host: String, user: String = ""): CloudAccount? {
        val families = when (service) {
            "webdavs" -> listOf("webdavs", "webdav")
            "scp" -> listOf("scp", "sftp")
            else -> listOf(service)
        }
        val all = list()
        for (family in families) {
            val hostMatches = all.filter { it.service == family && it.host.equals(host, ignoreCase = true) }
                .ifEmpty { if (family == "s3") all.filter { it.service == "s3" && it.host == "*" } else emptyList() }
            if (hostMatches.isEmpty()) continue
            if (user.isNotEmpty()) hostMatches.firstOrNull { it.username == user }?.let { return it }
            return hostMatches.first()
        }
        return null
    }

    /** Seals a password typed inside a link so the job row never holds it in plain text. */
    fun sealInline(password: String): String = cipher.seal(password)

    fun openInline(sealed: String): String? = cipher.open(sealed)

    private fun CloudAccountRow.toAccount() = CloudAccount(id, service, host, port, username, label, endpoint, region, secrets != null)
}
