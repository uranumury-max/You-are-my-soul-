package com.karam.youaremysoul

import org.json.JSONObject

enum class InviteStatus { PENDING, ACCEPTED, DECLINED }

data class JoinInvite(
    val fromCode: String,
    val fromName: String,
    val toHostCode: String,
    val status: InviteStatus = InviteStatus.PENDING
) {
    fun toJson() = JSONObject()
        .put("fromCode", fromCode)
        .put("fromName", fromName)
        .put("toHostCode", toHostCode)
        .put("status", status.name)
}
