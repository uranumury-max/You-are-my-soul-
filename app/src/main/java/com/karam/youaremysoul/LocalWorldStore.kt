package com.karam.youaremysoul

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Durable local checkpoint. Each device keeps its own copy; network replication is a separate layer. */
class LocalWorldStore(context: Context) {
    private val prefs = context.getSharedPreferences("world_checkpoint", Context.MODE_PRIVATE)

    fun save(world: WorldSnapshot) {
        val json = JSONObject().apply {
            put("worldName", world.worldName)
            put("hostCode", world.hostCode)
            put("journeyStage", world.journeyStage)
            put("gardenFlowers", world.gardenFlowers)
            put("gardenLastWateredAt", world.gardenLastWateredAt)
            put("dailyAnswerDate", world.dailyAnswerDate)
            put("dailyAnswer", world.dailyAnswer)
            put("memories", JSONArray().apply { world.memories.forEach(::put) })
            put("members", JSONArray().apply {
                world.members.forEach { member ->
                    put(JSONObject().put("code", member.localCode)
                        .put("name", member.displayName).put("host", member.isHost))
                }
            })
        }
        prefs.edit().putString("snapshot", json.toString()).apply()
    }

    fun load(): WorldSnapshot? = runCatching {
        val json = JSONObject(prefs.getString("snapshot", null) ?: return null)
        val membersJson = json.optJSONArray("members") ?: JSONArray()
        val memoriesJson = json.optJSONArray("memories") ?: JSONArray()
        WorldSnapshot(
            worldName = json.optString("worldName", "عالمنا الخاص"),
            hostCode = json.optString("hostCode"),
            members = (0 until membersJson.length()).map { i ->
                val m = membersJson.getJSONObject(i)
                WorldMember(m.optString("code"), m.optString("name"), m.optBoolean("host"))
            },
            journeyStage = json.optInt("journeyStage"),
            memories = (0 until memoriesJson.length()).map { memoriesJson.optString(it) },
            gardenFlowers = json.optInt("gardenFlowers", 0),
            gardenLastWateredAt = json.optLong("gardenLastWateredAt", 0L),
            dailyAnswerDate = json.optString("dailyAnswerDate", ""),
            dailyAnswer = json.optString("dailyAnswer", "")
        )
    }.getOrNull()
}
