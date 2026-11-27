package com.karam.youaremysoul

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable encrypted local checkpoint.
 * The encryption key is held by Android Keystore; network replication is a separate layer.
 */
class LocalWorldStore(context: Context) {
    private val prefs = context.getSharedPreferences("world_checkpoint", Context.MODE_PRIVATE)
    private val cipher = SecureSnapshotCipher()
    private val snapshotDao = SyncDatabase.get(context).worldSnapshotDao()

    fun encodeForSync(world: WorldSnapshot): ByteArray =
        encode(world).toByteArray(Charsets.UTF_8)

    fun restoreFromSyncPayload(
        payload: ByteArray,
        cloudWorldId: String,
        persist: Boolean = true
    ): WorldSnapshot {
        require(payload.isNotEmpty() && payload.size <= 700_000)
        val restored = decode(payload.toString(Charsets.UTF_8)).copy(
            cloudWorldId = java.util.UUID.fromString(cloudWorldId).toString(),
            localIsOwner = false
        )
        if (persist) save(restored)
        return restored
    }

    fun encryptedEntity(world: WorldSnapshot): LocalWorldSnapshotEntity =
        LocalWorldSnapshotEntity(
            encryptedPayload = cipher.encrypt(encode(world)),
            updatedAtEpochMs = System.currentTimeMillis()
        )

    fun encryptedBaseline(world: WorldSnapshot): LocalSyncBaselineEntity =
        LocalSyncBaselineEntity(
            encryptedPayload = cipher.encrypt(encode(world)),
            updatedAtEpochMs = System.currentTimeMillis()
        )

    suspend fun loadSyncBaseline(outbox: RoomSyncStore): WorldSnapshot? =
        outbox.loadBaseline()?.let { decode(cipher.decrypt(it.encryptedPayload)) }

    suspend fun saveSyncBaselineIfMissing(world: WorldSnapshot, outbox: RoomSyncStore) {
        outbox.insertBaselineIfMissing(encryptedBaseline(world))
    }

    suspend fun updateSyncBaseline(world: WorldSnapshot, outbox: RoomSyncStore) {
        outbox.updateBaseline(encryptedBaseline(world))
    }

    suspend fun saveWithBaseline(world: WorldSnapshot, outbox: RoomSyncStore) {
        outbox.commitRemoteState(
            snapshot = encryptedEntity(world),
            baseline = encryptedBaseline(world)
        )
        removeLegacyPreferences()
    }

    suspend fun saveWithOutbox(
        world: WorldSnapshot,
        baseline: WorldSnapshot,
        events: List<WorldSyncEvent>,
        outbox: RoomSyncStore
    ) {
        outbox.commitLocalChange(
            snapshot = encryptedEntity(world),
            baseline = encryptedBaseline(baseline),
            events = events
        )
        removeLegacyPreferences()
    }

    fun save(world: WorldSnapshot) {
        val encrypted = cipher.encrypt(encode(world))
        // Room is the durable source of truth. Legacy preferences are removed
        // only after the encrypted database write has committed.
        snapshotDao.save(
            LocalWorldSnapshotEntity(
                encryptedPayload = encrypted,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
        removeLegacyPreferences()
    }

    fun load(): WorldSnapshot? {
        // Once present, Room is authoritative. A decrypt failure propagates;
        // stale preferences must never overwrite a newer unreadable checkpoint.
        snapshotDao.load()?.let { stored ->
            return decode(cipher.decrypt(stored.encryptedPayload))
        }

        val encryptedV2 = prefs.getString(ENCRYPTED_SNAPSHOT_KEY_V2, null)
        if (encryptedV2 != null) {
            val migrated = decode(cipher.decrypt(encryptedV2))
            persistMigrated(encryptedV2)
            return migrated
        }

        val encryptedV1 = prefs.getString(ENCRYPTED_SNAPSHOT_KEY_V1, null)
        if (encryptedV1 != null) {
            val migrated = decode(cipher.decrypt(encryptedV1))
            persistMigrated(cipher.encrypt(encode(migrated)))
            return migrated
        }

        // Legacy plaintext is removed only after successful decoding,
        // encryption, and durable Room persistence.
        val legacy = prefs.getString(LEGACY_SNAPSHOT_KEY, null) ?: return null
        val migrated = decode(legacy)
        persistMigrated(cipher.encrypt(encode(migrated)))
        return migrated
    }

    private fun persistMigrated(encryptedPayload: String) {
        snapshotDao.save(
            LocalWorldSnapshotEntity(
                encryptedPayload = encryptedPayload,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
        removeLegacyPreferences()
    }

    private fun removeLegacyPreferences() {
        // Best-effort cleanup: Room already contains the durable encrypted copy.
        prefs.edit()
            .remove(ENCRYPTED_SNAPSHOT_KEY_V2)
            .remove(ENCRYPTED_SNAPSHOT_KEY_V1)
            .remove(LEGACY_SNAPSHOT_KEY)
            .commit()
    }

    private fun encode(world: WorldSnapshot): String = JSONObject().apply {
        put("worldName", world.worldName)
        put("hostCode", world.hostCode)
        put("journeyStage", world.journeyStage)
        put("gardenFlowers", world.gardenFlowers)
        put("gardenLastWateredAt", world.gardenLastWateredAt)
        put("dailyAnswerDate", world.dailyAnswerDate)
        put("dailyAnswer", world.dailyAnswer)
        put("dailyAnswers", JSONArray().apply {
            world.dailyAnswers.takeLast(100).forEach { answer ->
                put(
                    JSONObject()
                        .put("date", answer.date)
                        .put("authorCode", answer.authorCode)
                        .put("answer", answer.answer)
                        .put("createdAtEpochMs", answer.createdAtEpochMs)
                )
            }
        })
        put("relationshipStartDate", world.relationshipStartDate)
        put("cloudWorldId", world.cloudWorldId)
        put("localIsOwner", world.localIsOwner)
        put("messages", JSONArray().apply {
            world.messages.takeLast(100).forEach { message ->
                put(
                    JSONObject()
                        .put("id", message.id)
                        .put("senderName", message.senderName)
                        .put("text", message.text)
                        .put("createdAtEpochMs", message.createdAtEpochMs)
                )
            }
        })
        put("capsules", JSONArray().apply {
            world.capsules.takeLast(30).forEach { capsule ->
                put(
                    JSONObject()
                        .put("id", capsule.id)
                        .put("message", capsule.message)
                        .put("createdAtEpochMs", capsule.createdAtEpochMs)
                        .put("unlockAtEpochMs", capsule.unlockAtEpochMs)
                        .put("openedAtEpochMs", capsule.openedAtEpochMs ?: JSONObject.NULL)
                )
            }
        })
        put("drawings", JSONArray().apply {
            world.drawings.takeLast(40).forEach { stroke ->
                put(
                    JSONObject()
                        .put("id", stroke.id)
                        .put("colorArgb", stroke.colorArgb)
                        .put("widthFraction", stroke.widthFraction.toDouble())
                        .put("points", JSONArray().apply {
                            stroke.points.take(150).forEach { point ->
                                put(JSONArray().put(point.x.toDouble()).put(point.y.toDouble()))
                            }
                        })
                )
            }
        })
        put("drawingVersions", JSONArray().apply {
            world.drawingVersions.takeLast(WorldLimits.MAX_DRAWING_VERSIONS).forEach { version ->
                put(JSONObject()
                    .put("id", version.id)
                    .put("title", version.title)
                    .put("createdAtEpochMs", version.createdAtEpochMs)
                    .put("strokes", JSONArray().apply {
                        version.strokes.takeLast(DrawingHistory.MAX_STROKES).forEach { stroke ->
                            put(JSONObject()
                                .put("id", stroke.id)
                                .put("colorArgb", stroke.colorArgb)
                                .put("widthFraction", stroke.widthFraction.toDouble())
                                .put("points", JSONArray().apply {
                                    stroke.points.take(150).forEach { point ->
                                        put(JSONArray().put(point.x.toDouble()).put(point.y.toDouble()))
                                    }
                                })
                            )
                        }
                    })
                )
            }
        })
        put("media", JSONArray().apply {
            world.media.takeLast(WorldLimits.MAX_MEDIA_ITEMS).forEach { item ->
                put(
                    JSONObject()
                        .put("id", item.id)
                        .put("displayName", item.displayName)
                        .put("mimeType", item.mimeType)
                        .put("sizeBytes", item.sizeBytes)
                        .put("createdAtEpochMs", item.createdAtEpochMs)
                        .put("objectPath", item.objectPath)
                        .put("keyVersion", item.keyVersion)
                )
            }
        })
        put("memories", JSONArray().apply { world.memories.takeLast(100).forEach(::put) })
        put("members", JSONArray().apply {
            world.members.forEach { member ->
                put(
                    JSONObject()
                        .put("code", member.localCode)
                        .put("name", member.displayName)
                        .put("host", member.isHost)
                )
            }
        })
    }.toString()

    private fun decode(raw: String): WorldSnapshot {
        val json = JSONObject(raw)
        val membersJson = json.optJSONArray("members") ?: JSONArray()
        val memoriesJson = json.optJSONArray("memories") ?: JSONArray()
        val messagesJson = json.optJSONArray("messages") ?: JSONArray()
        val capsulesJson = json.optJSONArray("capsules") ?: JSONArray()
        val drawingsJson = json.optJSONArray("drawings") ?: JSONArray()
        val drawingVersionsJson = json.optJSONArray("drawingVersions") ?: JSONArray()
        val mediaJson = json.optJSONArray("media") ?: JSONArray()
        val dailyAnswersJson = json.optJSONArray("dailyAnswers") ?: JSONArray()
        val worldId = json.optString("cloudWorldId", "")
        return WorldSnapshot(
            worldName = json.optString("worldName", "عالمنا الخاص").take(40).ifBlank { "عالمنا الخاص" },
            hostCode = json.optString("hostCode"),
            members = (0 until membersJson.length()).map { index ->
                val member = membersJson.getJSONObject(index)
                WorldMember(
                    member.optString("code"),
                    member.optString("name").take(40),
                    member.optBoolean("host")
                )
            }.take(WorldLimits.MAX_MEMBERS),
            journeyStage = json.optInt("journeyStage"),
            memories = (0 until memoriesJson.length()).map { memoriesJson.optString(it) }
                .filter(WorldEventValidation::memory).takeLast(100),
            gardenFlowers = json.optInt("gardenFlowers", 0),
            gardenLastWateredAt = json.optLong("gardenLastWateredAt", 0L),
            dailyAnswerDate = json.optString("dailyAnswerDate", ""),
            dailyAnswer = json.optString("dailyAnswer", "").take(1000),
            dailyAnswers = (0 until dailyAnswersJson.length()).mapNotNull { index ->
                val answer = dailyAnswersJson.optJSONObject(index) ?: return@mapNotNull null
                DailyAnswerEntry(
                    date = answer.optString("date"),
                    authorCode = answer.optString("authorCode"),
                    answer = answer.optString("answer").take(1000),
                    createdAtEpochMs = answer.optLong("createdAtEpochMs")
                ).takeIf {
                    WorldEventValidation.dailyAnswerEntry(
                        it.date, it.authorCode, it.answer, it.createdAtEpochMs
                    )
                }
            }.takeLast(100),
            relationshipStartDate = json.optString("relationshipStartDate", ""),
            cloudWorldId = json.optString("cloudWorldId", ""),
            localIsOwner = json.optBoolean("localIsOwner", true),
            messages = (0 until messagesJson.length()).map { index ->
                val message = messagesJson.getJSONObject(index)
                WorldMessage(
                    id = message.optString("id"),
                    senderName = message.optString("senderName", "مشارك"),
                    text = message.optString("text"),
                    createdAtEpochMs = message.optLong("createdAtEpochMs")
                )
            }.filter {
                WorldEventValidation.message(it.id, it.senderName, it.text, it.createdAtEpochMs)
            }.takeLast(100),
            capsules = (0 until capsulesJson.length()).map { index ->
                val capsule = capsulesJson.getJSONObject(index)
                FutureCapsule(
                    id = capsule.optString("id"),
                    message = capsule.optString("message"),
                    createdAtEpochMs = capsule.optLong("createdAtEpochMs"),
                    unlockAtEpochMs = capsule.optLong("unlockAtEpochMs"),
                    openedAtEpochMs = if (capsule.isNull("openedAtEpochMs")) null
                        else capsule.optLong("openedAtEpochMs")
                )
            }.filter {
                WorldEventValidation.capsule(
                    it.id, it.message, it.createdAtEpochMs, it.unlockAtEpochMs
                )
            }.takeLast(30),
            drawings = (0 until drawingsJson.length()).mapNotNull { index ->
                val stroke = drawingsJson.optJSONObject(index) ?: return@mapNotNull null
                val pointsJson = stroke.optJSONArray("points") ?: JSONArray()
                val points = (0 until pointsJson.length()).mapNotNull { pointIndex ->
                    val pair = pointsJson.optJSONArray(pointIndex) ?: return@mapNotNull null
                    if (pair.length() != 2) return@mapNotNull null
                    val x = pair.optDouble(0, -1.0).toFloat()
                    val y = pair.optDouble(1, -1.0).toFloat()
                    if (x !in 0f..1f || y !in 0f..1f) null else WorldPoint(x, y)
                }.take(150)
                val id = stroke.optString("id")
                val width = stroke.optDouble("widthFraction", 0.006).toFloat()
                if (!WorldEventValidation.drawing(id, width, points)) null
                else DrawingStroke(id, stroke.optLong("colorArgb", -1L), width, points)
            }.takeLast(40),
            drawingVersions = (maxOf(0, drawingVersionsJson.length() - WorldLimits.MAX_DRAWING_VERSIONS) until drawingVersionsJson.length()).mapNotNull { index ->
                val version = drawingVersionsJson.optJSONObject(index) ?: return@mapNotNull null
                val versionStrokesJson = version.optJSONArray("strokes") ?: JSONArray()
                if (versionStrokesJson.length() > DrawingHistory.MAX_STROKES) return@mapNotNull null
                val versionStrokes = (0 until versionStrokesJson.length()).mapNotNull { strokeIndex ->
                    val stroke = versionStrokesJson.optJSONObject(strokeIndex) ?: return@mapNotNull null
                    val pointsJson = stroke.optJSONArray("points") ?: JSONArray()
                    if (pointsJson.length() !in 1..150) return@mapNotNull null
                    val points = (0 until pointsJson.length()).mapNotNull { pointIndex ->
                        val pair = pointsJson.optJSONArray(pointIndex) ?: return@mapNotNull null
                        if (pair.length() != 2) return@mapNotNull null
                        val x = pair.optDouble(0, -1.0).toFloat()
                        val y = pair.optDouble(1, -1.0).toFloat()
                        if (x !in 0f..1f || y !in 0f..1f) null else WorldPoint(x, y)
                    }
                    val id = stroke.optString("id")
                    val width = stroke.optDouble("widthFraction", 0.006).toFloat()
                    val color = stroke.optLong("colorArgb", -1L)
                    if (points.size != pointsJson.length() ||
                        !WorldEventValidation.drawing(id, width, points) ||
                        !WorldEventValidation.drawingColor(color)
                    ) null else DrawingStroke(id, color, width, points)
                }
                DrawingVersion(
                    id = version.optString("id"),
                    title = version.optString("title"),
                    createdAtEpochMs = version.optLong("createdAtEpochMs"),
                    strokes = versionStrokes
                ).takeIf(WorldEventValidation::drawingVersion)
            }.takeLast(WorldLimits.MAX_DRAWING_VERSIONS),
            media = (0 until mediaJson.length()).mapNotNull { index ->
                val item = mediaJson.optJSONObject(index) ?: return@mapNotNull null
                WorldMediaItem(
                    id = item.optString("id"),
                    displayName = item.optString("displayName"),
                    mimeType = item.optString("mimeType"),
                    sizeBytes = item.optLong("sizeBytes"),
                    createdAtEpochMs = item.optLong("createdAtEpochMs"),
                    objectPath = item.optString("objectPath"),
                    keyVersion = item.optInt("keyVersion", 1)
                ).takeIf {
                    WorldEventValidation.media(
                        worldId, it.id, it.displayName, it.mimeType, it.sizeBytes,
                        it.createdAtEpochMs, it.objectPath, it.keyVersion
                    )
                }
            }.takeLast(WorldLimits.MAX_MEDIA_ITEMS)
        )
    }

    private companion object {
        const val ENCRYPTED_SNAPSHOT_KEY_V2 = "snapshot_encrypted_v2"
        const val ENCRYPTED_SNAPSHOT_KEY_V1 = "snapshot_encrypted_v1"
        const val LEGACY_SNAPSHOT_KEY = "snapshot"
    }
}
