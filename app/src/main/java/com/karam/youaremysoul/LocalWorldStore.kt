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

    fun restoreFromSyncPayload(payload: ByteArray, cloudWorldId: String): WorldSnapshot {
        require(payload.isNotEmpty() && payload.size <= 700_000)
        val restored = decode(payload.toString(Charsets.UTF_8)).copy(
            cloudWorldId = java.util.UUID.fromString(cloudWorldId).toString(),
            localIsOwner = false
        )
        save(restored)
        return restored
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
        put("relationshipStartDate", world.relationshipStartDate)
        put("cloudWorldId", world.cloudWorldId)
        put("localIsOwner", world.localIsOwner)
        put("messages", JSONArray().apply {
            world.messages.forEach { message ->
                put(
                    JSONObject()
                        .put("id", message.id)
                        .put("senderName", message.senderName)
                        .put("text", message.text)
                        .put("createdAtEpochMs", message.createdAtEpochMs)
                )
            }
        })
        put("memories", JSONArray().apply { world.memories.forEach(::put) })
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
        return WorldSnapshot(
            worldName = json.optString("worldName", "عالمنا الخاص"),
            hostCode = json.optString("hostCode"),
            members = (0 until membersJson.length()).map { index ->
                val member = membersJson.getJSONObject(index)
                WorldMember(
                    member.optString("code"),
                    member.optString("name"),
                    member.optBoolean("host")
                )
            },
            journeyStage = json.optInt("journeyStage"),
            memories = (0 until memoriesJson.length()).map { memoriesJson.optString(it) },
            gardenFlowers = json.optInt("gardenFlowers", 0),
            gardenLastWateredAt = json.optLong("gardenLastWateredAt", 0L),
            dailyAnswerDate = json.optString("dailyAnswerDate", ""),
            dailyAnswer = json.optString("dailyAnswer", ""),
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
            }.filter { it.id.isNotBlank() && it.text.isNotBlank() }
                .takeLast(500)
        )
    }

    private companion object {
        const val ENCRYPTED_SNAPSHOT_KEY_V2 = "snapshot_encrypted_v2"
        const val ENCRYPTED_SNAPSHOT_KEY_V1 = "snapshot_encrypted_v1"
        const val LEGACY_SNAPSHOT_KEY = "snapshot"
    }
}
