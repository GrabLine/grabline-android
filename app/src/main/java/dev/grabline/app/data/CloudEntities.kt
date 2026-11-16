package dev.grabline.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * A saved server login (Phase 6). The label, host, port and user name are plain; every secret (password, S3 secret key,
 * private key, key passphrase) is inside [secrets], sealed by an Android Keystore AES-GCM key (see engine.cloud).
 */
@Entity(tableName = "cloud_accounts", indices = [Index(value = ["service", "host", "username"], unique = true)])
data class CloudAccountRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "sftp", "ftp", "ftps", "webdav" or "s3". */
    val service: String,
    /** The server's host name, or the S3 bucket name ("*" = any bucket). */
    val host: String,
    /** 0 = the protocol's default port. */
    val port: Int,
    /** User name, or the S3 access key id. */
    val username: String,
    val label: String,
    /** Optional S3-compatible endpoint (https://minio.example:9000); empty = AWS. */
    val endpoint: String,
    /** S3 region; empty = us-east-1. */
    val region: String,
    /** Base64 of the Keystore-sealed secrets, or null when none are saved. */
    val secrets: String?,
)

/** The SSH host key first seen for a server (SHA-256 fingerprint). A different key later is refused. */
@Entity(tableName = "ssh_host_keys", primaryKeys = ["host", "port"])
data class SshHostKeyRow(
    val host: String,
    val port: Int,
    val keyType: String,
    /** "SHA256:" + base64 (no padding) of the SHA-256 of the key, as OpenSSH prints it. */
    val sha256: String,
    val firstSeenAt: Long,
)

@Dao
interface CloudAccountDao {
    @Query("SELECT * FROM cloud_accounts ORDER BY label COLLATE NOCASE, host COLLATE NOCASE, username")
    fun observeAll(): Flow<List<CloudAccountRow>>

    @Query("SELECT * FROM cloud_accounts ORDER BY id")
    suspend fun all(): List<CloudAccountRow>

    @Query("SELECT * FROM cloud_accounts WHERE id = :id")
    suspend fun get(id: Long): CloudAccountRow?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: CloudAccountRow): Long

    @Update
    suspend fun update(row: CloudAccountRow)

    @Query("DELETE FROM cloud_accounts WHERE id = :id")
    suspend fun delete(id: Long)
}

/** Blocking on purpose: the SSH library asks for the stored key from its own thread. */
@Dao
interface SshHostKeyDao {
    @Query("SELECT * FROM ssh_host_keys WHERE host = :host AND port = :port")
    fun find(host: String, port: Int): SshHostKeyRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(row: SshHostKeyRow)

    @Query("DELETE FROM ssh_host_keys WHERE host = :host AND port = :port")
    fun forget(host: String, port: Int)

    @Query("SELECT * FROM ssh_host_keys ORDER BY host COLLATE NOCASE, port")
    fun observeAll(): Flow<List<SshHostKeyRow>>
}
