package com.karam.youaremysoul

import android.content.Context
import java.util.UUID

/**
 * Transfers the first complete world checkpoint as an authenticated encrypted event.
 * Subsequent granular edits are handled by the event/outbox synchronization layer.
 */
class WorldSnapshotBootstrapper(context: Context) {
    private val localStore = LocalWorldStore(context.applicationContext)
    private val transport = SupabaseEventTransport()

    suspend fun publishOwnerSnapshot(
        session: AuthenticatedSupabaseUser,
        world: WorldSnapshot,
        worldKeyVault: WorldKeyVault
    ) {
        require(world.localIsOwner)
        val worldId = UUID.fromString(world.cloudWorldId)
        val eventId = UUID.randomUUID()
        val payload = localStore.encodeForSync(world)
        val ciphertext = try {
            worldKeyVault.encrypt(
                worldId = worldId,
                eventId = eventId,
                eventType = SyncEventType.WORLD_UPDATE,
                plaintext = payload,
                keyVersion = 1
            )
        } finally {
            payload.fill(0)
        }
        val event = WorldSyncEvent(
            eventId = eventId,
            worldId = worldId,
            actorUserId = UUID.fromString(session.userId),
            type = SyncEventType.WORLD_UPDATE,
            ciphertextBase64 = ciphertext,
            clientCreatedAtEpochMs = System.currentTimeMillis()
        )
        transport.append(session, event)
    }

    suspend fun restoreMemberSnapshot(
        session: AuthenticatedSupabaseUser,
        worldId: String,
        worldKeyVault: WorldKeyVault
    ): WorldSnapshot {
        val canonicalWorldId = UUID.fromString(worldId)
        var cursor = SyncCursor(0L)
        var latestPayload: ByteArray? = null
        var pagesRead = 0
        var hasMore: Boolean
        do {
            val page = transport.fetchPage(session, canonicalWorldId, cursor)
            for (item in page.events) {
                val event = item.event
                if (event.type != SyncEventType.WORLD_UPDATE) continue
                val keyVersion = event.ciphertextBase64
                    .removePrefix("wce:v1:")
                    .substringBefore(':')
                    .toIntOrNull()
                    ?: throw SupabaseAuthException("snapshot_invalid_key_version")
                latestPayload?.fill(0)
                latestPayload = worldKeyVault.decrypt(
                    worldId = canonicalWorldId,
                    eventId = event.eventId,
                    eventType = event.type,
                    envelope = event.ciphertextBase64,
                    keyVersion = keyVersion
                )
            }
            cursor = page.nextCursor
            hasMore = page.hasMore
            pagesRead += 1
        } while (hasMore && pagesRead < MAX_BOOTSTRAP_PAGES)

        if (hasMore) {
            latestPayload?.fill(0)
            throw SupabaseAuthException("snapshot_bootstrap_page_limit")
        }
        val payload = latestPayload
            ?: throw SupabaseAuthException("snapshot_bootstrap_not_found")
        return try {
            localStore.restoreFromSyncPayload(payload, canonicalWorldId.toString())
        } finally {
            payload.fill(0)
        }
    }

    private companion object {
        const val MAX_BOOTSTRAP_PAGES = 10
    }
}
