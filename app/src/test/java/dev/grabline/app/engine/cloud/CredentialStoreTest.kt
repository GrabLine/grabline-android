package dev.grabline.app.engine.cloud

import dev.grabline.app.data.CloudAccountDao
import dev.grabline.app.data.CloudAccountRow
import javax.crypto.KeyGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeCloudDao : CloudAccountDao {
    val rows = mutableListOf<CloudAccountRow>()
    private var next = 1L
    override fun observeAll(): Flow<List<CloudAccountRow>> = MutableStateFlow(rows.toList())
    override suspend fun all() = rows.toList()
    override suspend fun get(id: Long) = rows.firstOrNull { it.id == id }
    override suspend fun insert(row: CloudAccountRow): Long {
        require(rows.none { it.service == row.service && it.host == row.host && it.username == row.username }) { "unique" }
        val id = next++
        rows += row.copy(id = id)
        return id
    }
    override suspend fun update(row: CloudAccountRow) { rows.replaceAll { if (it.id == row.id) row else it } }
    override suspend fun delete(id: Long) { rows.removeAll { it.id == id } }
}

class CredentialStoreTest {
    private fun newKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val key = newKey()
    private val dao = FakeCloudDao()
    private val cipher = AesGcmSecretCipher { key }
    private val store = CredentialStore(dao, cipher)

    @Test fun cipherRoundTripsAndNeverRepeats() {
        val a = cipher.seal("hunter2")
        val b = cipher.seal("hunter2")
        assertNotEquals("fresh IV each time", a, b)
        assertEquals("hunter2", cipher.open(a))
        assertFalse(a.contains("hunter2"))
    }

    @Test fun tamperedOrForeignCiphertextIsRefused() {
        val sealed = java.util.Base64.getDecoder().decode(cipher.seal("secret"))
        sealed[sealed.size - 1] = (sealed.last().toInt() xor 1).toByte()
        assertNull(cipher.open(java.util.Base64.getEncoder().encodeToString(sealed)))
        val other = AesGcmSecretCipher { newKey() }
        assertNull(other.open(cipher.seal("secret")))
        assertNull(cipher.open("not base64 !!"))
    }

    @Test fun secretsAreStoredSealedNeverInPlainText() = runBlocking {
        store.save(CloudAccount(service = "sftp", host = "box.example", username = "alice", port = 22), AccountSecrets(password = "alice-secret"))
        val row = dao.rows.single()
        assertFalse(row.secrets!!.contains("alice-secret"))
        assertFalse(row.toString().contains("alice-secret"))
        assertEquals("alice-secret", store.secretsFor(store.list().single())!!.password)
        assertEquals("AccountSecrets(***)", AccountSecrets(password = "x").toString())
    }

    @Test fun multipleAccountsPerHostAndAutomaticChoice() = runBlocking {
        store.save(CloudAccount(service = "sftp", host = "box.example", username = "alice"), AccountSecrets(password = "a"))
        store.save(CloudAccount(service = "sftp", host = "box.example", username = "bob"), AccountSecrets(password = "b"))
        assertEquals(2, store.list().size)
        assertEquals("bob", store.accountFor("sftp", "box.example", "bob")!!.username)
        assertEquals("alice", store.accountFor("sftp", "box.example")!!.username)
        assertNull(store.accountFor("sftp", "other.example"))
    }

    @Test fun savingAgainKeepsTheSecretWhenNoneIsTyped() = runBlocking {
        val id = store.save(CloudAccount(service = "ftp", host = "h", username = "u"), AccountSecrets(password = "pw"))
        store.save(CloudAccount(id = id, service = "ftp", host = "h", username = "u", port = 2121), null)
        val account = store.list().single()
        assertEquals(2121, account.port)
        assertEquals("pw", store.secretsFor(account)!!.password)
    }

    @Test fun deleteRemovesTheAccountAndItsSecret() = runBlocking {
        val id = store.save(CloudAccount(service = "ftp", host = "ftp.example", username = "u"), AccountSecrets(password = "pw"))
        store.delete(id)
        assertTrue(store.list().isEmpty())
        assertTrue(dao.rows.isEmpty())
    }

    @Test fun savedLoginDecryptsToNothingWhenTheKeyIsGone() = runBlocking {
        store.save(CloudAccount(service = "ftp", host = "h", username = "u"), AccountSecrets(password = "pw"))
        val lost = CredentialStore(dao, AesGcmSecretCipher { newKey() })
        assertNull(lost.secretsFor(lost.list().single()))
    }

    // How a link and a saved account combine (the desktop's connection tests).

    private suspend fun resolve(url: String, inline: String? = null) = CloudResolver.resolve(CloudUrl.parse(url)!!, inline, store)

    @Test fun savedPortAndExplicitUrlPortPrecedence() = runBlocking {
        store.save(CloudAccount(service = "ftp", host = "files.example", username = "qa", port = 2121), AccountSecrets(password = "test-only"))
        store.save(CloudAccount(service = "ftps", host = "files.example", username = "qa", port = 2990), AccountSecrets(password = "test-only"))
        store.save(CloudAccount(service = "sftp", host = "files.example", username = "qa", port = 2222), AccountSecrets(password = "test-only"))
        store.save(CloudAccount(service = "webdav", host = "files.example", username = "qa", port = 9123), AccountSecrets(password = "qa-password"))
        assertEquals(2121, resolve("ftp://files.example/file").port)
        assertEquals(2990, resolve("ftps://files.example/file").port)
        assertEquals(2021, resolve("ftp://files.example:2021/file").port)
        assertEquals(2222, resolve("sftp://files.example/file").port)
        assertEquals(2200, resolve("sftp://files.example:2200/file").port)
        assertEquals(2222, resolve("scp://files.example/file").port)
        assertEquals(9123, resolve("webdav://files.example/file.bin").port)
        assertEquals(9123, resolve("webdavs://files.example/file.bin").port)
        assertEquals(9999, resolve("webdavs://files.example:9999/file.bin").port)
        assertEquals("no account: the protocol default", 21, resolve("ftp://other.example/f").port)
    }

    @Test fun inlinePasswordBeatsTheSavedOne() = runBlocking {
        val c = resolve("ftp://joe:pw@host/file")
        assertEquals("joe", c.user)
        assertEquals("pw", c.password)
        store.save(CloudAccount(service = "ftp", host = "host", username = "joe"), AccountSecrets(password = "saved"))
        assertEquals("saved", resolve("ftp://joe@host/file").password)
        assertEquals("typed", resolve("ftp://joe@host/file", inline = "typed").password)
    }

    @Test fun savedWebdavLoginServesHttpAndHttps() = runBlocking {
        store.save(CloudAccount(service = "webdav", host = "files.example", username = "qa"), AccountSecrets(password = "qa-password"))
        for (scheme in listOf("webdav", "webdavs")) {
            val c = resolve("$scheme://files.example/file.bin")
            assertEquals("qa", c.user)
            assertEquals("qa-password", c.password)
        }
    }

    @Test fun scpUsesItsSavedKeyAndPassphrase() = runBlocking {
        store.save(
            CloudAccount(service = "sftp", host = "files.example", username = "qa"),
            AccountSecrets(privateKey = "qa-private-key", passphrase = "test-passphrase"),
        )
        for (scheme in listOf("sftp", "scp")) {
            val c = resolve("$scheme://files.example/file")
            assertEquals("qa-private-key", c.privateKey)
            assertEquals("test-passphrase", c.passphrase)
            assertEquals("", c.password)
        }
    }

    @Test fun s3DottedBucketIsNotAnEndpoint() = runBlocking {
        store.save(CloudAccount(service = "s3", host = "qa.bucket.name", username = "qa-key"), AccountSecrets(password = "test-only"))
        val c = resolve("s3://qa.bucket.name/object.bin")
        assertEquals("", c.endpoint)
        assertEquals("qa-key", c.user)
        assertEquals("test-only", c.password)
    }

    @Test fun explicitS3EndpointSurvivesStorage() = runBlocking {
        store.save(CloudAccount(service = "s3", host = "qa-bucket", username = "k", endpoint = "https://minio.example:9000", region = "eu-west-1"), null)
        val account = store.list().single()
        assertEquals("https://minio.example:9000", account.endpoint)
        val c = resolve("s3://qa-bucket/object.bin")
        assertEquals("https://minio.example:9000", c.endpoint)
        assertEquals("eu-west-1", c.region)
    }

    @Test fun anS3AccountNamedStarFitsAnyBucket() = runBlocking {
        store.save(CloudAccount(service = "s3", host = "*", username = "key"), AccountSecrets(password = "sec"))
        assertEquals("key", resolve("s3://whatever-bucket/o").user)
        assertNotNull(store.accountFor("s3", "another"))
    }
}
