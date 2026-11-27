package com.karam.youaremysoul

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WorldSettingsPatchTest {
    @Test
    fun independentConcurrentFieldChangesComposeWithoutErasingEachOther() {
        val original = WorldSnapshot(
            worldName = "عالمنا",
            relationshipStartDate = "2026-01-01"
        )
        val rename = WorldSettingsPatch(worldName = "مجرّتنا", relationshipStartDate = null)
        val changeDate = WorldSettingsPatch(worldName = null, relationshipStartDate = "2026-02-14")

        val afterBoth = changeDate.applyTo(rename.applyTo(original))

        assertEquals("مجرّتنا", afterBoth.worldName)
        assertEquals("2026-02-14", afterBoth.relationshipStartDate)
    }

    @Test
    fun laterPatchForSameFieldWinsByReplayOrderAndPreservesOtherField() {
        val original = WorldSnapshot(
            worldName = "الأول",
            relationshipStartDate = "2026-01-01"
        )
        val first = WorldSettingsPatch(worldName = "الثاني", relationshipStartDate = null)
        val later = WorldSettingsPatch(worldName = "الثالث", relationshipStartDate = null)

        val result = later.applyTo(first.applyTo(original))

        assertEquals("الثالث", result.worldName)
        assertEquals("2026-01-01", result.relationshipStartDate)
    }

    @Test
    fun validationRejectsInvalidSettingsBeforePatchIsApplied() {
        assertFalse(WorldEventValidation.settings("ن".repeat(41), null))
        assertFalse(WorldEventValidation.settings(null, "bad-date"))
    }
}
