package com.karam.youaremysoul

import org.json.JSONObject
import java.util.UUID

/**
 * Converts local state differences into small, independently replayable encrypted events.
 * The server sequence is the deterministic order when both devices edit the same field.
 */
internal class WorldMutationSync(
    private val outbox: RoomSyncStore,
    private val keyVault: WorldKeyVault
) {
    fun enqueueChanges(
        previous: WorldSnapshot,
        next: WorldSnapshot,
        session: AuthenticatedSupabaseUser
    ): Int {
        val worldId = UUID.fromString(next.cloudWorldId)
        require(previous.cloudWorldId == next.cloudWorldId)
        val changes = mutableListOf<Pair<SyncEventType, JSONObject>>()

        val removed = previous.memories.toMutableList()
        val added = mutableListOf<String>()
        next.memories.forEach { value ->
            val index = removed.indexOf(value)
            if (index >= 0) removed.removeAt(index) else added += value
        }
        added.forEach { changes += SyncEventType.MEMORY_CREATE to JSONObject().put("value", it) }
        removed.forEach { changes += SyncEventType.MEMORY_DELETE to JSONObject().put("value", it) }

        val flowerDelta = next.gardenFlowers - previous.gardenFlowers
        if (flowerDelta != 0 || next.gardenLastWateredAt != previous.gardenLastWateredAt) {
            changes += SyncEventType.GARDEN_UPDATE to JSONObject()
                .put("delta", flowerDelta)
                .put("lastWateredAt", next.gardenLastWateredAt)
        }

        if (next.dailyAnswerDate != previous.dailyAnswerDate ||
            next.dailyAnswer != previous.dailyAnswer
        ) {
            changes += SyncEventType.DAILY_ANSWER to JSONObject()
                .put("date", next.dailyAnswerDate)
                .put("answer", next.dailyAnswer)
        }

        if (next.worldName != previous.worldName ||
            next.relationshipStartDate != previous.relationshipStartDate
        ) {
            changes += SyncEventType.SETTINGS_UPDATE to JSONObject().apply {
                if (next.worldName != previous.worldName) put("worldName", next.worldName)
                if (next.relationshipStartDate != previous.relationshipStartDate) {
                    put("relationshipStartDate", next.relationshipStartDate)
                }
            }
        }

        changes.forEach { (type, payload) ->
            val eventId = UUID.randomUUID()
            val plaintext = payload.toString().toByteArray(Charsets.UTF_8)
            val encrypted = try {
                keyVault.encrypt(worldId, eventId, type, plaintext, keyVersion = 1)
            } finally {
                plaintext.fill(0)
            }
            val event = WorldSyncEvent(
                eventId = eventId,
                worldId = worldId,
                actorUserId = session.userId,
                type = type,
                ciphertextBase64 = encrypted,
                clientCreatedAtEpochMs = System.currentTimeMillis()
            )
            // RoomSyncStore is suspend; this method is called from the caller's coroutine.
            kotlinx.coroutines.runBlocking { outbox.enqueue(event) }
        }
        return changes.size
    }
}

/** Rebuilds shared state from the latest encrypted checkpoint and its ordered event tail. */
internal class WorldEventReducer(
    private val syncStore: RoomSyncStore,
    private val snapshotStore: LocalWorldStore,
    private val keyVault: WorldKeyVault
) {
    suspend fun rebuild(worldId: UUID): WorldSnapshot? {
        val events = syncStore.allRemoteEvents(worldId)
        val checkpoint = events.indexOfLast { it.event.type == SyncEventType.WORLD_UPDATE }
        if (checkpoint < 0) return null

        val baseEvent = events[checkpoint].event
        val baseVersion = envelopeVersion(baseEvent.ciphertextBase64)
        val baseBytes = keyVault.decrypt(
            worldId, baseEvent.eventId, baseEvent.type, baseEvent.ciphertextBase64, baseVersion
        )
        val ownerFlag = snapshotStore.load()?.localIsOwner ?: false
        var state = try {
            snapshotStore.restoreFromSyncPayload(baseBytes, worldId.toString())
                .copy(localIsOwner = ownerFlag)
                .also(snapshotStore::save)
        } finally {
            baseBytes.fill(0)
        }

        for (item in events.drop(checkpoint + 1)) {
            val event = item.event
            val version = envelopeVersion(event.ciphertextBase64)
            val bytes = keyVault.decrypt(
                worldId, event.eventId, event.type, event.ciphertextBase64, version
            )
            try {
                val payload = JSONObject(bytes.toString(Charsets.UTF_8))
                state = when (event.type) {
                    SyncEventType.WORLD_UPDATE -> state
                    SyncEventType.MEMORY_CREATE -> state.copy(
                        memories = state.memories + payload.getString("value")
                    )
                    SyncEventType.MEMORY_DELETE -> {
                        val memories = state.memories.toMutableList()
                        val index = memories.indexOf(payload.getString("value"))
                        if (index >= 0) memories.removeAt(index)
                        state.copy(memories = memories)
                    }
                    SyncEventType.GARDEN_UPDATE -> state.copy(
                        gardenFlowers = (state.gardenFlowers + payload.optInt("delta")).coerceAtLeast(0),
                        gardenLastWateredAt = maxOf(
                            state.gardenLastWateredAt, payload.optLong("lastWateredAt")
                        )
                    )
                    SyncEventType.DAILY_ANSWER -> state.copy(
                        dailyAnswerDate = payload.optString("date"),
                        dailyAnswer = payload.optString("answer")
                    )
                    SyncEventType.SETTINGS_UPDATE -> state.copy(
                        worldName = payload.optString("worldName", state.worldName),
                        relationshipStartDate = payload.optString(
                            "relationshipStartDate", state.relationshipStartDate
                        )
                    )
                    else -> state
                }
            } finally {
                bytes.fill(0)
            }
        }
        snapshotStore.save(state)
        return state
    }

    private fun envelopeVersion(envelope: String): Int {
        val parts = envelope.split(':', limit = 4)
        require(parts.size == 4 && parts[0] == "wce" && parts[1] == "v1")
        return parts[2].toInt().also { require(it > 0) }
    }
}
