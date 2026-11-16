package dev.grabline.app.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A cached job joined with the name of the PC it lives on, for the unified list. */
data class PcJobWithPc(
    @Embedded val job: PcJob,
    @ColumnInfo(name = "pcName") val pcName: String,
    @ColumnInfo(name = "pcLastSeenAt") val pcLastSeenAt: Long?,
)

@Dao
interface PairedPcDao {
    @Query("SELECT * FROM paired_pcs ORDER BY pairedAt")
    fun observeAll(): Flow<List<PairedPc>>

    @Query("SELECT * FROM paired_pcs WHERE pcId = :pcId")
    suspend fun get(pcId: String): PairedPc?

    @Upsert
    suspend fun upsert(pc: PairedPc)

    @Query("DELETE FROM paired_pcs WHERE pcId = :pcId")
    suspend fun delete(pcId: String)

    @Query("UPDATE paired_pcs SET lastGoodAddress = :address WHERE pcId = :pcId")
    suspend fun updateLastGood(pcId: String, address: String)

    @Query("UPDATE paired_pcs SET removedAt = :at WHERE pcId = :pcId")
    suspend fun markRemoved(pcId: String, at: Long)

    @Query("UPDATE paired_pcs SET lastSeenAt = :at WHERE pcId = :pcId")
    suspend fun touchSeen(pcId: String, at: Long)
}

@Dao
interface PcJobDao {
    @Query(
        "SELECT j.*, p.name AS pcName, p.lastSeenAt AS pcLastSeenAt FROM pc_jobs j " +
            "JOIN paired_pcs p ON p.pcId = j.pcId ORDER BY j.createdAt DESC, j.jobId DESC",
    )
    fun observeAll(): Flow<List<PcJobWithPc>>

    @Query("SELECT * FROM pc_jobs WHERE pcId = :pcId ORDER BY createdAt DESC, jobId DESC")
    fun observeForPc(pcId: String): Flow<List<PcJob>>

    @Upsert
    suspend fun upsertAll(jobs: List<PcJob>)

    @Query("DELETE FROM pc_jobs WHERE pcId = :pcId")
    suspend fun deleteForPc(pcId: String)

    @Query("DELETE FROM pc_jobs WHERE pcId = :pcId AND jobId = :jobId")
    suspend fun delete(pcId: String, jobId: Long)

    @Transaction
    suspend fun replaceAllForPc(pcId: String, jobs: List<PcJob>) {
        deleteForPc(pcId)
        upsertAll(jobs)
    }
}

@Dao
interface OutboxDao {
    /** Counts items still waiting to be delivered; pcId null counts every PC. */
    @Query(
        "SELECT COUNT(*) FROM outbox WHERE state IN ('pending','sending') " +
            "AND (:pcId IS NULL OR pcId = :pcId)",
    )
    fun observePendingCount(pcId: String? = null): Flow<Int>

    @Query("SELECT * FROM outbox WHERE pcId = :pcId AND state = 'pending' ORDER BY id")
    suspend fun pending(pcId: String): List<OutboxItem>

    /** Returns the new row id, or -1 when the requestId already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: OutboxItem): Long

    @Query("UPDATE outbox SET state = 'sending' WHERE id = :id")
    suspend fun markSending(id: Long)

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun markDelivered(id: Long)

    @Query("UPDATE outbox SET state = 'failed', attempts = attempts + 1, lastError = :error WHERE id = :id")
    suspend fun markFailed(id: Long, error: String?)

    @Query("UPDATE outbox SET state = 'pending' WHERE state = 'sending'")
    suspend fun resetSendingToPending()

    /** A transient failure: back to pending for another try with the same requestId. */
    @Query("UPDATE outbox SET state = 'pending', attempts = attempts + 1, lastError = :error WHERE id = :id")
    suspend fun markRetry(id: Long, error: String?)

    /** Items the PC refused for good, for the "couldn't send" notice. */
    @Query("SELECT * FROM outbox WHERE state = 'failed' ORDER BY id")
    fun observeFailed(): Flow<List<OutboxItem>>

    @Query("SELECT * FROM outbox WHERE pcId = :pcId AND url = :url AND state IN ('pending','sending') AND createdAt >= :since LIMIT 1")
    suspend fun recentSame(pcId: String, url: String, since: Long): OutboxItem?

    /** How many outbox rows carry this requestId (0 once the PC took the link and the row was deleted). */
    @Query("SELECT COUNT(*) FROM outbox WHERE requestId = :requestId")
    suspend fun countByRequestId(requestId: String): Int

    @Query("SELECT DISTINCT pcId FROM outbox WHERE state = 'pending'")
    fun observePendingPcs(): Flow<List<String>>

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun get(id: Long): OutboxItem?
}
