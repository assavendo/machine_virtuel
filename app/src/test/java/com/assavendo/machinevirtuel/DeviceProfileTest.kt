package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.device.DeviceFamily
import com.assavendo.machinevirtuel.device.DeviceProfileDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DeviceProfileTest {
    @Test fun qualcommProfileMatches() {
        assertEquals(
            DeviceFamily.QUALCOMM_SNAPDRAGON,
            DeviceProfileDatabase.match("Qualcomm", "Generic Phone", "SM8650").family
        )
    }
    @Test fun moreSpecificModelProfileWins() {
        val profile = DeviceProfileDatabase.match("Qualcomm", "Snapdragon 8 Gen 4 Reference", "SM8750")
        assertNotNull(profile)
        assertEquals("qualcomm-8gen", profile.id)
    }
}
