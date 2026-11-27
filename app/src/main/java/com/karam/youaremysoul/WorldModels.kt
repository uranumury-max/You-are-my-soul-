package com.karam.youaremysoul

data class WorldMember(val localCode: String, val displayName: String, val isHost: Boolean = false)
data class WorldMessage(
    val id: String,
    val senderName: String,
    val text: String,
    val createdAtEpochMs: Long
)
data class WorldSnapshot(
    val worldName: String = "عالمنا الخاص",
    val hostCode: String = "",
    val members: List<WorldMember> = emptyList(),
    val journeyStage: Int = 0,
    val memories: List<String> = emptyList(),
    val gardenFlowers: Int = 0,
    val gardenLastWateredAt: Long = 0L,
    val dailyAnswerDate: String = "",
    val dailyAnswer: String = "",
    val relationshipStartDate: String = "",
    /** Stable cloud world UUID; blank until assigned for legacy local worlds. */
    val cloudWorldId: String = "",
    /** True only when this installation owns the local world. */
    val localIsOwner: Boolean = true,
    val messages: List<WorldMessage> = emptyList()
)
