package com.karam.youaremysoul

data class WorldMember(val localCode: String, val displayName: String, val isHost: Boolean = false)
data class WorldSnapshot(
    val worldName: String = "عالمنا الخاص",
    val hostCode: String = "",
    val members: List<WorldMember> = emptyList(),
    val journeyStage: Int = 0,
    val memories: List<String> = emptyList()
)
