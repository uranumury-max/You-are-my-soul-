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

    fun save(world: WorldSnapshot) {
        val encrypted = cipher.encrypt(encode(world))
        // save() is called on Dispatchers.IO, so commit guarantees the encrypted checkpoint is durable.
        check(
            prefs.edit()
                .putString(ENCRYPTED_SNAPSHOT_KEY_V2, encrypted)
                .remove(ENCRYPTED_SNAPSHOT_KEY_V1)
                .remove(LEGACY_SNAPSHOT_KEY)
                .commit()
        ) { "Unable to persist encrypted world snapshot" }
    }

    fun load(): WorldSnapshot? {
        val encryptedV2 = prefs.getString(ENCRYPTED_SNAPSHOT_KEY_V2, null)
        if (encryptedV2 != null) {
            return decode(cipher.decrypt(encryptedV2))
        }

        // Upgrade authenticated v1 ciphertext to v2 without exposing plaintext
        // or deleting the old value until the new durable write succeeds.
        val encryptedV1 = prefs.getString(ENCRYPTED_SNAPSHOT_KEY_V1, null)
        if (encryptedV1 != null) {
            val migrated = decode(cipher.decrypt(encryptedV1))
            save(migrated)
            return migrated
        }

        // One-time migration from the previous plaintext format. A decode or
        // durable-write failure must propagate and lock editing; never treat it
        // as an empty world that could later overwrite recoverable user data.
        val legacy = prefs.getString(LEGACY_SNAPSHOT_KEY, null) ?: return null
        val migrated = decode(legacy)
        save(migrated)
        return migrated
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
            relationshipStartDate = json.optString("relationshipStartDate", "")
        )
    }

    private companion object {
        const val ENCRYPTED_SNAPSHOT_KEY_V2 = "snapshot_encrypted_v2"
        const val ENCRYPTED_SNAPSHOT_KEY_V1 = "snapshot_encrypted_v1"
        const val LEGACY_SNAPSHOT_KEY = "snapshot"
    }
}
