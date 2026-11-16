package dev.grabline.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "paired_pcs")
data class PairedPc(
    @PrimaryKey val pcId: String,
    val name: String,
    /** Pinned certificate hash. */
    val fingerprint: String,
    /** The id this phone has on that PC. */
    val deviceId: String,
    val addresses: List<String>,
    val port: Int,
    val lastGoodAddress: String? = null,
    val protocolVersion: Int,
    val pcAppVersion: String? = null,
    val pairedAt: Long,
    val lastSeenAt: Long? = null,
    val defaultQueueId: Int? = null,
    val notifyActivity: Boolean = true,
    /** Keystore alias of this pairing's device key; null for rows made before aliases (legacy `grabline-device-<pcId>`). */
    val keyAlias: String? = null,
    /** Set when the PC said it no longer accepts this phone (QA-06). The row stays so the UI can say so. */
    val removedAt: Long? = null,
)

@Entity(
    tableName = "pc_jobs",
    primaryKeys = ["pcId", "jobId"],
    foreignKeys = [
        ForeignKey(
            entity = PairedPc::class,
            parentColumns = ["pcId"],
            childColumns = ["pcId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PcJob(
    val pcId: String,
    val jobId: Long,
    val url: String,
    val name: String,
    /** queued, downloading, paused, completed, failed or cancelled. */
    val status: String,
    val downloaded: Long = 0,
    val total: Long? = null,
    val speed: Long? = null,
    val etaSeconds: Long? = null,
    val queueId: Int? = null,
    val createdAt: Long? = null,
    val finishedAt: Long? = null,
    val error: String? = null,
    val updatedAt: Long,
    /** The PC would accept `fetch` for this job (added in schema 5). */
    val fetchable: Boolean = false,
)

object OutboxState {
    const val PENDING = "pending"
    const val SENDING = "sending"
    const val DELIVERED = "delivered"
    const val FAILED = "failed"
}

@Entity(
    tableName = "outbox",
    indices = [
        Index(value = ["requestId"], unique = true),
        Index(value = ["pcId", "state"]),
    ],
)
data class OutboxItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val requestId: String,
    val pcId: String,
    val url: String,
    val title: String? = null,
    val quality: String? = null,
    val queueId: Int? = null,
    val folderId: String? = null,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
    val state: String = OutboxState.PENDING,
)
