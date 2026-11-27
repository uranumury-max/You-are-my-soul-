package com.karam.youaremysoul

import org.json.JSONObject

/**
 * A settings event is a field-level patch, not a replacement snapshot.
 * Events are replayed by ascending server sequence; therefore edits to different
 * fields compose, while edits to the same field use deterministic server order.
 */
internal data class WorldSettingsPatch(
    val worldName: String?,
    val relationshipStartDate: String?
) {
    fun applyTo(snapshot: WorldSnapshot): WorldSnapshot = snapshot.copy(
        worldName = worldName ?: snapshot.worldName,
        relationshipStartDate = relationshipStartDate ?: snapshot.relationshipStartDate
    )

    companion object {
        fun fromPayload(payload: JSONObject): WorldSettingsPatch? {
            val name = if (payload.has("worldName")) payload.optString("worldName") else null
            val date = if (payload.has("relationshipStartDate")) {
                payload.optString("relationshipStartDate")
            } else null
            if (name == null && date == null) return null
            if (!WorldEventValidation.settings(name, date)) return null
            return WorldSettingsPatch(name, date)
        }
    }
}
