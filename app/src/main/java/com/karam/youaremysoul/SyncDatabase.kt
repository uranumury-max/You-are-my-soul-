package com.karam.youaremysoul

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

@Entity(
    tableName = "sync_outbox",
    indices = [Index(value = ["worldId", "nextAttemptAtEpochMs", "queuedAtEpochMs"])]
)
data class SyncOutboxEntity(
    @PrimaryKey val eventId: String,
    val worldId: String,
    val actorUserId: String,
    val eventType: String,
    val ciphertextBase64: String,
    val clientCreatedAtEpochMs: Long,
    val queuedAtEpochMs: Long,
    val attemptCount: Int,
    val nextAttemptAtEpochMs: Long,
    val lastFailureCode: String?
)

@Entity(
    tableName = "sync_events",
    primaryKeys = ["worldId", "eventId"],
    indices = [Index(value = ["worldId", "serverSequence"], unique = true)]
)
data class SyncedEventEntity(
    val worldId: String,
    val eventId: String,
    val actorUserId: String,
    val eventType: String,
    val ciphertextBase64: String,
    val clientCreatedAtEpochMs: Long,
    val serverSequence: Long
)

@Entity(tableName = "sync_cursors")
data class SyncCursorEntity(
    @PrimaryKey val worldId: String,
    val serverSequence: Long,
    val updatedAtEpochMs: Long
)

@Dao
interface SyncDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun enqueue(record: SyncOutboxEntity)

    @Query(
        """
        SELECT * FROM sync_outbox
        WHERE nextAttemptAtEpochMs <= :nowEpochMs
          AND attemptCount < :maxAttempts
        ORDER BY queuedAtEpochMs ASC
        LIMIT :limit
        """
    )
    suspend fun loadDueOutbox(
        nowEpochMs: Long,
        maxAttempts: Int,
        limit: Int
    ): List<SyncOutboxEntity>

    @Query("SELECT * FROM sync_outbox WHERE eventId = :eventId LIMIT 1")
    suspend fun findPending(eventId: String): SyncOutboxEntity?

    @Query(
        """
        UPDATE sync_outbox
        SET attemptCount = :attemptCount,
            nextAttemptAtEpochMs = :nextAttemptAtEpochMs,
            lastFailureCode = :failureCode
        WHERE eventId = :eventId
        """
    )
    suspend fun recordAttempt(
        eventId: String,
        attemptCount: Int,
        nextAttemptAtEpochMs: Long,
        failureCode: String?
    )

    @Query("DELETE FROM sync_outbox WHERE eventId = :eventId")
    suspend fun markDelivered(eventId: String)

    @Query("SELECT * FROM sync_cursors WHERE worldId = :worldId LIMIT 1")
    suspend fun getCursor(worldId: String): SyncCursorEntity?

    @Query(
        """
        INSERT INTO sync_cursors(worldId, serverSequence, updatedAtEpochMs)
        VALUES(:worldId, :serverSequence, :updatedAtEpochMs)
        ON CONFLICT(worldId) DO UPDATE SET
            serverSequence = MAX(sync_cursors.serverSequence, excluded.serverSequence),
            updatedAtEpochMs = excluded.updatedAtEpochMs
        """
    )
    suspend fun advanceCursor(
        worldId: String,
        serverSequence: Long,
        updatedAtEpochMs: Long
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRemoteEvents(events: List<SyncedEventEntity>)

    @Query(
        """
        SELECT * FROM sync_events
        WHERE worldId = :worldId AND serverSequence > :afterSequence
        ORDER BY serverSequence ASC
        LIMIT :limit
        """
    )
    suspend fun loadRemoteEvents(
        worldId: String,
        afterSequence: Long,
        limit: Int
    ): List<SyncedEventEntity>

    @Transaction
    suspend fun applyRemotePage(
        worldId: String,
        events: List<SyncedEventEntity>,
        nextCursor: Long,
        updatedAtEpochMs: Long
    ) {
        insertRemoteEvents(events)
        advanceCursor(worldId, nextCursor, updatedAtEpochMs)
    }
}

@Database(
    entities = [SyncOutboxEntity::class, SyncedEventEntity::class, SyncCursorEntity::class],
    version = 1,
    exportSchema = false
)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun syncDao(): SyncDao

    companion object {
        @Volatile
        private var instance: SyncDatabase? = null

        fun get(context: Context): SyncDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SyncDatabase::class.java,
                    "you_are_my_soul_sync.db"
                ).build().also { instance = it }
            }
    }
}

/**
 * Durable local sync queue. It is intentionally not connected to the UI/network yet:
 * enqueueing must be wired to the same transaction that persists each user change.
 */
class RoomSyncStore(context: Context) {
    private val dao = SyncDatabase.get(context).syncDao()

    suspend fun enqueue(event: WorldSyncEvent, queuedAtEpochMs: Long = System.currentTimeMillis()) {
        require(SyncProtocolRules.validate(event) == null) { "Invalid encrypted sync event" }
        require(queuedAtEpochMs > 0L)
        dao.enqueue(
            SyncOutboxEntity(
                eventId = event.eventId.toString(),
                worldId = event.worldId.toString(),
                actorUserId = event.actorUserId.toString(),
                eventType = event.type.wireValue,
                ciphertextBase64 = event.ciphertextBase64,
                clientCreatedAtEpochMs = event.clientCreatedAtEpochMs,
                queuedAtEpochMs = queuedAtEpochMs,
                attemptCount = 0,
                nextAttemptAtEpochMs = queuedAtEpochMs,
                lastFailureCode = null
            )
        )
    }

    suspend fun dueEvents(
        nowEpochMs: Long = System.currentTimeMillis(),
        limit: Int = SyncProtocolRules.MAX_EVENTS_PER_PAGE
    ): List<PendingSyncEvent> {
        require(limit in 1..SyncProtocolRules.MAX_EVENTS_PER_PAGE)
        return dao.loadDueOutbox(
            nowEpochMs = nowEpochMs,
            maxAttempts = SyncProtocolRules.MAX_PENDING_ATTEMPTS,
            limit = limit
        ).mapNotNull { row ->
            val type = SyncEventType.fromWire(row.eventType) ?: return@mapNotNull null
            PendingSyncEvent(
                event = WorldSyncEvent(
                    eventId = runCatching { java.util.UUID.fromString(row.eventId) }.getOrNull()
                        ?: return@mapNotNull null,
                    worldId = runCatching { java.util.UUID.fromString(row.worldId) }.getOrNull()
                        ?: return@mapNotNull null,
                    actorUserId = runCatching { java.util.UUID.fromString(row.actorUserId) }.getOrNull()
                        ?: return@mapNotNull null,
                    type = type,
                    ciphertextBase64 = row.ciphertextBase64,
                    clientCreatedAtEpochMs = row.clientCreatedAtEpochMs
                ),
                queuedAtEpochMs = row.queuedAtEpochMs,
                attemptCount = row.attemptCount,
                nextAttemptAtEpochMs = row.nextAttemptAtEpochMs
            )
        }
    }

    suspend fun recordFailure(eventId: java.util.UUID, nowEpochMs: Long, safeFailureCode: String?) {
        val row = dao.findPending(eventId.toString()) ?: return
        val nextAttempt = (row.attemptCount + 1).coerceAtMost(SyncProtocolRules.MAX_PENDING_ATTEMPTS)
        val multiplier = 1L shl (nextAttempt - 1).coerceAtLeast(0)
        val delayMs = (15_000L * multiplier).coerceAtMost(MAX_RETRY_DELAY_MS)
        dao.recordAttempt(
            eventId = eventId.toString(),
            attemptCount = nextAttempt,
            nextAttemptAtEpochMs = nowEpochMs + delayMs,
            failureCode = safeFailureCode?.filter { it.isLetterOrDigit() || it == '_' }?.take(64)
        )
    }

    suspend fun markDelivered(eventId: java.util.UUID) {
        dao.markDelivered(eventId.toString())
    }

    suspend fun cursor(worldId: java.util.UUID): SyncCursor =
        SyncCursor(dao.getCursor(worldId.toString())?.serverSequence ?: 0L)

    suspend fun applyRemotePage(
        worldId: java.util.UUID,
        page: SyncPage,
        requestedAfter: SyncCursor,
        nowEpochMs: Long = System.currentTimeMillis()
    ) {
        require(SyncProtocolRules.validatePage(page, requestedAfter) == null) {
            "Rejected invalid synchronization page"
        }
        require(page.events.all { it.event.worldId == worldId }) {
            "Synchronization page contains an event from another world"
        }
        val entities = page.events.map { item ->
            SyncedEventEntity(
                worldId = item.event.worldId.toString(),
                eventId = item.event.eventId.toString(),
                actorUserId = item.event.actorUserId.toString(),
                eventType = item.event.type.wireValue,
                ciphertextBase64 = item.event.ciphertextBase64,
                clientCreatedAtEpochMs = item.event.clientCreatedAtEpochMs,
                serverSequence = item.serverSequence
            )
        }
        dao.applyRemotePage(
            worldId = worldId.toString(),
            events = entities,
            nextCursor = page.nextCursor.serverSequence,
            updatedAtEpochMs = nowEpochMs
        )
    }

    private companion object {
        const val MAX_RETRY_DELAY_MS = 6 * 60 * 60 * 1000L
    }
}

private fun SyncEventType.CompanionPlaceholder() = Unit
