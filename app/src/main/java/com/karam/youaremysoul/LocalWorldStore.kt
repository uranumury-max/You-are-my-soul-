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
        // SharedPreferences.apply() writes to disk asynchronously and avoids blocking the UI thread.
        prefs.edit()
            .putString(ENCRYPTED_SNAPSHOT_KEY, encrypted)
            .remove(LEGACY_SNAPSHOT_KEY)
            .apply()
    }

    fun load(): WorldSnapshot? {
        val encrypted = prefs.getString(ENCRYPTED_SNAPSHOT_KEY, null)
        if (encrypted != null) {
            return runCatching { decode(cipher.decrypt(encrypted)) }.getOrNull()
        }

        // One-time migration from the previous plaintext format.
        val legacy = prefs.getString(LEGACY_SNAPSHOT_KEY, null) ?: return null
        val migrated = runCatching { decode(legacy) }.getOrNull() ?: return null
        return runCatching {
            save(migrated)
            migrated
        }.getOrNull()
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
        const val ENCRYPTED_SNAPSHOT_KEY = "snapshot_encrypted_v1"
        const val LEGACY_SNAPSHOT_KEY = "snapshot"
    }
}
