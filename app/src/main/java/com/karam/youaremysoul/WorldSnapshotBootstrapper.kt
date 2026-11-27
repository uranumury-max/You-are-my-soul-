package com.karam.youaremysoul

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
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
        // Pin one authoritative key generation for this checkpoint and its replay.
        val snapshotKeyVersion = worldKeyVault.activeKeyVersion(worldId)
        val payload = localStore.encodeForSync(world.copy(drawingVersions = emptyList()))
        val ciphertext = try {
            worldKeyVault.encrypt(
                worldId = worldId,
                eventId = eventId,
                eventType = SyncEventType.WORLD_UPDATE,
                plaintext = payload,
                keyVersion = snapshotKeyVersion
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

        // Keep the initial checkpoint within the event cipher's 700 KiB cap.
        // Saved canvas versions follow as independently encrypted replay events.
        world.drawingVersions.takeLast(WorldLimits.MAX_DRAWING_VERSIONS).forEach { version ->
            if (!WorldEventValidation.drawingVersion(version)) return@forEach
            val plaintext = JSONObject()
                .put("action", "save_version")
                .put("id", version.id)
                .put("title", version.title)
                .put("createdAtEpochMs", version.createdAtEpochMs)
                .put("strokes", JSONArray().apply {
                    version.strokes.take(DrawingHistory.MAX_STROKES).forEach { stroke ->
                        put(JSONObject()
                            .put("id", stroke.id)
                            .put("colorArgb", stroke.colorArgb)
                            .put("widthFraction", stroke.widthFraction.toDouble())
                            .put("points", JSONArray().apply {
                                stroke.points.take(150).forEach { point ->
                                    put(JSONArray().put(
                                        (kotlin.math.round(point.x * 10_000f).toInt() / 10_000.0)
                                    ).put(
                                        (kotlin.math.round(point.y * 10_000f).toInt() / 10_000.0)
                                    ))
                                }
                            })
                        )
                    }
                })
                .toString()
                .toByteArray(Charsets.UTF_8)
            val versionEventId = UUID.randomUUID()
            val encryptedVersion = try {
                worldKeyVault.encrypt(
                    worldId = worldId,
                    eventId = versionEventId,
                    eventType = SyncEventType.DRAWING_UPDATE,
                    plaintext = plaintext,
                    keyVersion = snapshotKeyVersion
                )
            } finally {
                plaintext.fill(0)
            }
            transport.append(
                session,
                WorldSyncEvent(
                    eventId = versionEventId,
                    worldId = worldId,
                    actorUserId = UUID.fromString(session.userId),
                    type = SyncEventType.DRAWING_UPDATE,
                    ciphertextBase64 = encryptedVersion,
                    clientCreatedAtEpochMs = System.currentTimeMillis()
                )
            )
        }
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
