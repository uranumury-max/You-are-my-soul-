package com.karam.youaremysoul

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorldSettingsPatchTest {
    @Test
    fun independentConcurrentFieldChangesComposeWithoutErasingEachOther() {
        val original = WorldSnapshot(
            worldName = "عالمنا",
            relationshipStartDate = "2026-01-01"
        )
        val rename = requireNotNull(WorldSettingsPatch.fromPayload(
            JSONObject().put("worldName", "مجرّتنا")
        ))
        val changeDate = requireNotNull(WorldSettingsPatch.fromPayload(
            JSONObject().put("relationshipStartDate", "2026-02-14")
        ))

        val afterRename = rename.applyTo(original)
        val afterBoth = changeDate.applyTo(afterRename)

        assertEquals("مجرّتنا", afterBoth.worldName)
        assertEquals("2026-02-14", afterBoth.relationshipStartDate)
    }

    @Test
    fun laterPatchForSameFieldWinsByReplayOrderAndPreservesOtherField() {
        val original = WorldSnapshot(
            worldName = "الأول",
            relationshipStartDate = "2026-01-01"
        )
        val first = requireNotNull(WorldSettingsPatch.fromPayload(
            JSONObject().put("worldName", "الثاني")
        ))
        val later = requireNotNull(WorldSettingsPatch.fromPayload(
            JSONObject().put("worldName", "الثالث")
        ))

        val result = later.applyTo(first.applyTo(original))

        assertEquals("الثالث", result.worldName)
        assertEquals("2026-01-01", result.relationshipStartDate)
    }

    @Test
    fun invalidOrEmptyPatchIsRejectedRatherThanReplacingSettings() {
        assertNull(WorldSettingsPatch.fromPayload(JSONObject()))
        assertNull(WorldSettingsPatch.fromPayload(JSONObject().put("worldName", "ن".repeat(41))))
        assertNull(WorldSettingsPatch.fromPayload(JSONObject().put("relationshipStartDate", "bad-date")))
    }
}
