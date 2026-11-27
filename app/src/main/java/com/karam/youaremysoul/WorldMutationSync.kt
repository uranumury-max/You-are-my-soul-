package com.karam.youaremysoul

import org.json.JSONArray
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
    suspend fun enqueueChanges(
        previous: WorldSnapshot,
        next: WorldSnapshot,
        session: AuthenticatedSupabaseUser
    ): Int {
        val events = buildEvents(previous, next, session)
        outbox.enqueueAll(events)
        return events.size
    }

    fun buildEvents(
        previous: WorldSnapshot,
        next: WorldSnapshot,
        session: AuthenticatedSupabaseUser
    ): List<WorldSyncEvent> {
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

        val drawingDelta = DrawingDiff.between(previous.drawings, next.drawings)
        if (drawingDelta.clearBoard) {
            changes += SyncEventType.DRAWING_UPDATE to JSONObject().put("action", "clear")
        }
        drawingDelta.removedStrokeId?.let { removedId ->
            changes += SyncEventType.DRAWING_UPDATE to JSONObject()
                .put("action", "remove")
                .put("id", removedId)
        }
        drawingDelta.addedStrokes.forEach { stroke ->
            val points = JSONArray().apply {
                stroke.points.take(150).forEach { point ->
                    put(JSONArray().put(point.x.toDouble()).put(point.y.toDouble()))
                }
            }
            changes += SyncEventType.DRAWING_UPDATE to JSONObject()
                .put("action", "add")
                .put("id", stroke.id)
                .put("colorArgb", stroke.colorArgb)
                .put("widthFraction", stroke.widthFraction.toDouble())
                .put("points", points)
        }

        val previousCapsules = previous.capsules.associateBy { it.id }
        next.capsules.forEach { capsule ->
            val old = previousCapsules[capsule.id]
            if (old == null) {
                changes += SyncEventType.CAPSULE_UPDATE to JSONObject()
                    .put("action", "create")
                    .put("id", capsule.id)
                    .put("message", capsule.message)
                    .put("createdAtEpochMs", capsule.createdAtEpochMs)
                    .put("unlockAtEpochMs", capsule.unlockAtEpochMs)
            } else if (old.openedAtEpochMs == null && capsule.openedAtEpochMs != null) {
                changes += SyncEventType.CAPSULE_UPDATE to JSONObject()
                    .put("action", "open")
                    .put("id", capsule.id)
                    .put("openedAtEpochMs", capsule.openedAtEpochMs)
            }
        }

        val previousMediaIds = previous.media.mapTo(mutableSetOf()) { it.id }
        next.media.filter { it.id !in previousMediaIds }.forEach { media ->
            changes += SyncEventType.MEDIA_ADD to JSONObject()
                .put("id", media.id)
                .put("displayName", media.displayName)
                .put("mimeType", media.mimeType)
                .put("sizeBytes", media.sizeBytes)
                .put("createdAtEpochMs", media.createdAtEpochMs)
                .put("objectPath", media.objectPath)
                .put("keyVersion", media.keyVersion)
        }
        val nextMediaIds = next.media.mapTo(mutableSetOf()) { it.id }
        previous.media.filter { it.id !in nextMediaIds }.forEach { media ->
            changes += SyncEventType.MEDIA_DELETE to JSONObject().put("id", media.id)
        }

        val previousMessageIds = previous.messages.mapTo(mutableSetOf()) { it.id }
        next.messages.filter { it.id !in previousMessageIds }.forEach { message ->
            changes += SyncEventType.MESSAGE_CREATE to JSONObject()
                .put("id", message.id)
                .put("senderName", message.senderName)
                .put("text", message.text)
                .put("createdAtEpochMs", message.createdAtEpochMs)
        }

        val flowerDelta = next.gardenFlowers - previous.gardenFlowers
        if (flowerDelta != 0 || next.gardenLastWateredAt != previous.gardenLastWateredAt) {
            changes += SyncEventType.GARDEN_UPDATE to JSONObject()
                .put("delta", flowerDelta)
                .put("lastWateredAt", next.gardenLastWateredAt)
        }

        if (next.dailyAnswers != previous.dailyAnswers) {
            val previousAnswers = previous.dailyAnswers.associateBy { it.date + "|" + it.authorCode }
            next.dailyAnswers.forEach { answer ->
                if (previousAnswers[answer.date + "|" + answer.authorCode] != answer) {
                    changes += SyncEventType.DAILY_ANSWER to JSONObject()
                        .put("date", answer.date)
                        .put("authorCode", answer.authorCode)
                        .put("answer", answer.answer)
                        .put("createdAtEpochMs", answer.createdAtEpochMs)
                }
            }
        } else if (next.dailyAnswerDate != previous.dailyAnswerDate ||
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

        val events = changes.map { (type, payload) ->
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
                actorUserId = UUID.fromString(session.userId),
                type = type,
                ciphertextBase64 = encrypted,
                clientCreatedAtEpochMs = System.currentTimeMillis()
            )
            event
        }
        return events
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
            snapshotStore.restoreFromSyncPayload(
                baseBytes, worldId.toString(), persist = false
            ).copy(localIsOwner = ownerFlag)
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
                    SyncEventType.MEMORY_CREATE -> {
                        val value = payload.optString("value")
                        if (!WorldEventValidation.memory(value)) state
                        else state.copy(memories = (state.memories + value).takeLast(100))
                    }
                    SyncEventType.MEMORY_DELETE -> {
                        val value = payload.optString("value")
                        if (!WorldEventValidation.memory(value)) state else {
                            val memories = state.memories.toMutableList()
                            val index = memories.indexOf(value)
                            if (index >= 0) memories.removeAt(index)
                            state.copy(memories = memories)
                        }
                    }
                    SyncEventType.DRAWING_UPDATE -> {
                        when (payload.optString("action")) {
                            "clear" -> state.copy(drawings = emptyList())
                            "remove" -> {
                                val removedId = payload.optString("id")
                                if (removedId.length !in 1..64) state
                                else state.copy(drawings = state.drawings.filterNot { it.id == removedId })
                            }
                            "add" -> {
                                val strokeId = payload.optString("id")
                                val pointsJson = payload.optJSONArray("points") ?: JSONArray()
                                val pointCount = pointsJson.length()
                                // Bound parsing before allocating attacker-controlled point arrays.
                                val points = if (pointCount in 1..150) {
                                    (0 until pointCount).mapNotNull { index ->
                                        val pair = pointsJson.optJSONArray(index) ?: return@mapNotNull null
                                        if (pair.length() != 2) return@mapNotNull null
                                        val x = pair.optDouble(0, -1.0).toFloat()
                                        val y = pair.optDouble(1, -1.0).toFloat()
                                        if (x !in 0f..1f || y !in 0f..1f) null else WorldPoint(x, y)
                                    }
                                } else emptyList()
                                val width = payload.optDouble("widthFraction", 0.006).toFloat()
                                if (points.size != pointCount ||
                                    !WorldEventValidation.drawing(strokeId, width, points) ||
                                    state.drawings.any { it.id == strokeId }
                                ) state else state.copy(
                                    drawings = (state.drawings + DrawingStroke(
                                        id = strokeId,
                                        colorArgb = payload.optLong("colorArgb", -1L),
                                        widthFraction = width,
                                        points = points
                                    )).takeLast(40)
                                )
                            }
                            else -> state
                        }
                    }
                    SyncEventType.CAPSULE_UPDATE -> {
                        val capsuleId = payload.optString("id")
                        when (payload.optString("action")) {
                            "create" -> {
                                val unlockAt = payload.optLong("unlockAtEpochMs")
                                val createdAt = payload.optLong("createdAtEpochMs")
                                val message = payload.optString("message")
                                if (!WorldEventValidation.capsule(capsuleId, message, createdAt, unlockAt) ||
                                    state.capsules.any { it.id == capsuleId }
                                ) state else state.copy(
                                    capsules = (state.capsules + FutureCapsule(
                                        id = capsuleId,
                                        message = message,
                                        createdAtEpochMs = createdAt,
                                        unlockAtEpochMs = unlockAt
                                    )).takeLast(30)
                                )
                            }
                            "open" -> {
                                val openedAt = payload.optLong("openedAtEpochMs")
                                state.copy(capsules = state.capsules.map { capsule ->
                                    if (capsule.id == capsuleId && capsule.openedAtEpochMs == null &&
                                        openedAt >= capsule.unlockAtEpochMs
                                    ) capsule.copy(openedAtEpochMs = openedAt)
                                    else capsule
                                })
                            }
                            else -> state
                        }
                    }
                    SyncEventType.MESSAGE_CREATE -> {
                        val messageId = payload.optString("id")
                        val senderName = payload.optString("senderName", "مشارك")
                        val text = payload.optString("text")
                        val createdAt = payload.optLong("createdAtEpochMs")
                        if (!WorldEventValidation.message(messageId, senderName, text, createdAt) ||
                            state.messages.any { it.id == messageId }
                        ) {
                            state
                        } else {
                            state.copy(
                                messages = (state.messages + WorldMessage(
                                    id = messageId,
                                    senderName = senderName,
                                    text = text,
                                    createdAtEpochMs = createdAt
                                )).takeLast(100)
                            )
                        }
                    }
                    SyncEventType.MEDIA_ADD -> {
                        val media = WorldMediaItem(
                            id = payload.optString("id"),
                            displayName = payload.optString("displayName"),
                            mimeType = payload.optString("mimeType"),
                            sizeBytes = payload.optLong("sizeBytes"),
                            createdAtEpochMs = payload.optLong("createdAtEpochMs"),
                            objectPath = payload.optString("objectPath"),
                            keyVersion = payload.optInt("keyVersion", 1)
                        )
                        if (!WorldEventValidation.media(
                                state.cloudWorldId, media.id, media.displayName, media.mimeType,
                                media.sizeBytes, media.createdAtEpochMs, media.objectPath, media.keyVersion
                            ) || state.media.any { it.id == media.id }
                        ) state else state.copy(media = (state.media + media).takeLast(WorldLimits.MAX_MEDIA_ITEMS))
                    }
                    SyncEventType.MEDIA_DELETE -> {
                        val mediaId = payload.optString("id")
                        if (runCatching { java.util.UUID.fromString(mediaId) }.isFailure ||
                            state.media.none { it.id == mediaId }
                        ) state else state.copy(media = state.media.filterNot { it.id == mediaId })
                    }
                    SyncEventType.GARDEN_UPDATE -> {
                        val delta = payload.optInt("delta")
                        val wateredAt = payload.optLong("lastWateredAt")
                        if (!WorldEventValidation.garden(delta, wateredAt)) state else state.copy(
                            gardenFlowers = (state.gardenFlowers.toLong() + delta.toLong())
                                .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                            gardenLastWateredAt = maxOf(state.gardenLastWateredAt, wateredAt)
                        )
                    }
                    SyncEventType.DAILY_ANSWER -> {
                        val date = payload.optString("date")
                        val answer = payload.optString("answer")
                        val authorCode = payload.optString("authorCode")
                        val createdAt = payload.optLong("createdAtEpochMs")
                        if (authorCode.isBlank()) {
                            if (!WorldEventValidation.dailyAnswer(date, answer)) state
                            else state.copy(dailyAnswerDate = date, dailyAnswer = answer)
                        } else if (!WorldEventValidation.dailyAnswerEntry(
                                date, authorCode, answer, createdAt
                            )
                        ) {
                            state
                        } else {
                            val entry = DailyAnswerEntry(date, authorCode, answer, createdAt)
                            val updated = state.dailyAnswers.filterNot {
                                it.date == date && it.authorCode == authorCode
                            } + entry
                            state.copy(
                                dailyAnswerDate = date,
                                dailyAnswer = answer,
                                dailyAnswers = updated.takeLast(100)
                            )
                        }
                    }
                    SyncEventType.SETTINGS_UPDATE -> {
                        WorldSettingsPatch.fromPayload(payload)?.applyTo(state) ?: state
                    }
                    else -> state
                }
            } finally {
                bytes.fill(0)
            }
        }
        snapshotStore.saveWithBaseline(state, syncStore)
        return state
    }

    private fun envelopeVersion(envelope: String): Int {
        val parts = envelope.split(':', limit = 4)
        require(parts.size == 4 && parts[0] == "wce" && parts[1] == "v1")
        return parts[2].toInt().also { require(it > 0) }
    }
}
