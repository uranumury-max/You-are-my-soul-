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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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

@Entity(tableName = "local_world_snapshot")
data class LocalWorldSnapshotEntity(
    @PrimaryKey val id: Int = 1,
    val encryptedPayload: String,
    val updatedAtEpochMs: Long
)

@Dao
interface LocalWorldSnapshotDao {
    @Query("SELECT * FROM local_world_snapshot WHERE id = 1 LIMIT 1")
    fun load(): LocalWorldSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(snapshot: LocalWorldSnapshotEntity)
}

@Dao
interface SyncDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(record: SyncOutboxEntity)

    @Transaction
    suspend fun enqueueBatch(records: List<SyncOutboxEntity>) {
        records.forEach { enqueue(it) }
    }

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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCursorIfMissing(cursor: SyncCursorEntity)

    @Query(
        """
        UPDATE sync_cursors
        SET serverSequence = MAX(serverSequence, :serverSequence),
            updatedAtEpochMs = :updatedAtEpochMs
        WHERE worldId = :worldId
        """
    )
    suspend fun updateCursorMonotonically(
        worldId: String,
        serverSequence: Long,
        updatedAtEpochMs: Long
    )

    @Query("SELECT * FROM sync_events WHERE worldId = :worldId AND eventId = :eventId LIMIT 1")
    suspend fun findRemoteById(worldId: String, eventId: String): SyncedEventEntity?

    @Query("SELECT * FROM sync_events WHERE worldId = :worldId AND serverSequence = :sequence LIMIT 1")
    suspend fun findRemoteBySequence(worldId: String, sequence: Long): SyncedEventEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRemoteEvent(event: SyncedEventEntity)

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

    @Query("SELECT * FROM sync_events WHERE worldId = :worldId ORDER BY serverSequence ASC LIMIT :limit")
    suspend fun loadAllRemoteEvents(worldId: String, limit: Int): List<SyncedEventEntity>

    @Query("SELECT * FROM sync_events WHERE worldId = :worldId AND eventType = 'world_update' ORDER BY serverSequence DESC LIMIT 1")
    suspend fun latestCheckpoint(worldId: String): SyncedEventEntity?

    @Transaction
    suspend fun applyRemotePage(
        worldId: String,
        events: List<SyncedEventEntity>,
        nextCursor: Long,
        updatedAtEpochMs: Long
    ) {
        // Replayed pages are safe, but a reused event ID or server sequence
        // with different contents is treated as corruption, not ignored.
        for (event in events) {
            val byId = findRemoteById(worldId, event.eventId)
            val bySequence = findRemoteBySequence(worldId, event.serverSequence)
            check((byId == null || byId == event) && (bySequence == null || bySequence == event)) {
                "Conflicting event ID or server sequence in sync database"
            }
            if (byId == null && bySequence == null) insertRemoteEvent(event)
        }
        insertCursorIfMissing(
            SyncCursorEntity(worldId, serverSequence = 0L, updatedAtEpochMs = updatedAtEpochMs)
        )
        updateCursorMonotonically(worldId, nextCursor, updatedAtEpochMs)
    }
}

@Database(
    entities = [SyncOutboxEntity::class, SyncedEventEntity::class, SyncCursorEntity::class, LocalWorldSnapshotEntity::class],
    version = 2,
    exportSchema = false
)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun syncDao(): SyncDao
    abstract fun worldSnapshotDao(): LocalWorldSnapshotDao

    companion object {
        @Volatile
        private var instance: SyncDatabase? = null

        fun get(context: Context): SyncDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SyncDatabase::class.java,
                    "you_are_my_soul_sync.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `local_world_snapshot` (" +
                        "`id` INTEGER NOT NULL, " +
                        "`encryptedPayload` TEXT NOT NULL, " +
                        "`updatedAtEpochMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
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
        enqueueAll(listOf(event), queuedAtEpochMs)
    }

    suspend fun enqueueAll(
        events: List<WorldSyncEvent>,
        queuedAtEpochMs: Long = System.currentTimeMillis()
    ) {
        require(queuedAtEpochMs > 0L)
        require(events.size <= SyncProtocolRules.MAX_EVENTS_PER_PAGE)
        if (events.isEmpty()) return
        val records = events.map { event ->
            require(SyncProtocolRules.validate(event) == null) { "Invalid encrypted sync event" }
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
        }
        dao.enqueueBatch(records)
        records.forEach { record ->
            val stored = requireNotNull(dao.findPending(record.eventId)) {
                "Sync outbox insert did not persist"
            }
            check(
                stored.worldId == record.worldId &&
                    stored.actorUserId == record.actorUserId &&
                    stored.eventType == record.eventType &&
                    stored.ciphertextBase64 == record.ciphertextBase64 &&
                    stored.clientCreatedAtEpochMs == record.clientCreatedAtEpochMs
            ) { "Sync event ID was reused with different content" }
        }
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
        ).map { row ->
            val type = requireNotNull(SyncEventType.fromWire(row.eventType)) {
                "Unknown persisted sync event type"
            }
            PendingSyncEvent(
                event = WorldSyncEvent(
                    eventId = java.util.UUID.fromString(row.eventId),
                    worldId = java.util.UUID.fromString(row.worldId),
                    actorUserId = java.util.UUID.fromString(row.actorUserId),
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
        require(nowEpochMs > 0L)
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

    suspend fun allRemoteEvents(worldId: java.util.UUID): List<SequencedWorldEvent> {
        val checkpoint = dao.latestCheckpoint(worldId.toString()) ?: return emptyList()
        val rows = mutableListOf(checkpoint)
        var cursor = checkpoint.serverSequence - 1L
        while (rows.size < MAX_REPLAY_EVENTS) {
            val page = dao.loadRemoteEvents(
                worldId = worldId.toString(),
                afterSequence = cursor,
                limit = minOf(REPLAY_PAGE_SIZE, MAX_REPLAY_EVENTS - rows.size)
            )
            if (page.isEmpty()) break
            rows += page
            cursor = page.last().serverSequence
            if (page.size < REPLAY_PAGE_SIZE) break
        }
        check(
            dao.loadRemoteEvents(worldId.toString(), cursor, 1).isEmpty()
        ) { "World event history exceeds the safe replay limit" }
        return rows.distinctBy { it.eventId }.sortedBy { it.serverSequence }.map { row ->
            val type = requireNotNull(SyncEventType.fromWire(row.eventType)) {
                "Unknown persisted remote event type"
            }
            SequencedWorldEvent(
                serverSequence = row.serverSequence,
                event = WorldSyncEvent(
                    eventId = java.util.UUID.fromString(row.eventId),
                    worldId = java.util.UUID.fromString(row.worldId),
                    actorUserId = java.util.UUID.fromString(row.actorUserId),
                    type = type,
                    ciphertextBase64 = row.ciphertextBase64,
                    clientCreatedAtEpochMs = row.clientCreatedAtEpochMs
                )
            )
        }
    }

    suspend fun applyRemotePage(
        worldId: java.util.UUID,
        page: SyncPage,
        requestedAfter: SyncCursor,
        nowEpochMs: Long = System.currentTimeMillis()
    ) {
        require(SyncProtocolRules.validatePage(page, requestedAfter) == null) {
            "Rejected invalid synchronization page"
        }
        require(cursor(worldId) == requestedAfter) {
            "Local synchronization cursor changed; discard this stale page"
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
        const val MAX_REPLAY_EVENTS = 10_000
        const val REPLAY_PAGE_SIZE = 500
        const val MAX_RETRY_DELAY_MS = 6 * 60 * 60 * 1000L
    }
}
