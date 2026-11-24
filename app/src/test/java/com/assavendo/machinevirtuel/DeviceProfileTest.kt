package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.device.DeviceFamily
import com.assavendo.machinevirtuel.device.DeviceProfileDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceProfileTest {
    @Test fun qualcommProfileMatches() {
        assertEquals(
            DeviceFamily.QUALCOMM_SNAPDRAGON,
            DeviceProfileDatabase.match("Qualcomm", "Generic Phone", "SM8650").family
        )
    }
}
