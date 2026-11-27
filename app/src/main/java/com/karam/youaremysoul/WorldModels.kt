package com.karam.youaremysoul

data class WorldMember(val localCode: String, val displayName: String, val isHost: Boolean = false)
data class WorldMessage(
    val id: String,
    val senderName: String,
    val text: String,
    val createdAtEpochMs: Long
)
data class FutureCapsule(
    val id: String,
    val message: String,
    val createdAtEpochMs: Long,
    val unlockAtEpochMs: Long,
    val openedAtEpochMs: Long? = null
)
data class WorldPoint(val x: Float, val y: Float)
data class DrawingStroke(
    val id: String,
    val colorArgb: Long,
    val widthFraction: Float,
    val points: List<WorldPoint>
)
data class DailyAnswerEntry(
    val date: String,
    val authorCode: String,
    val answer: String,
    val createdAtEpochMs: Long
)
data class WorldMediaItem(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val createdAtEpochMs: Long,
    val objectPath: String,
    val keyVersion: Int = 1
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
    val dailyAnswers: List<DailyAnswerEntry> = emptyList(),
    val relationshipStartDate: String = "",
    /** Stable cloud world UUID; blank until assigned for legacy local worlds. */
    val cloudWorldId: String = "",
    /** True only when this installation owns the local world. */
    val localIsOwner: Boolean = true,
    val messages: List<WorldMessage> = emptyList(),
    val capsules: List<FutureCapsule> = emptyList(),
    val drawings: List<DrawingStroke> = emptyList(),
    val media: List<WorldMediaItem> = emptyList()
)
