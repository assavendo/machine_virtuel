package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.performance.MetricsHistory
import com.assavendo.machinevirtuel.performance.VmMetricsSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetricsHistoryTest {
    @Test
    fun unavailableGuestMeasurementsRemainUnknown() {
        val history = MetricsHistory()
        history.add(
            VmMetricsSample(
                timestampMs = System.currentTimeMillis(),
                fps = 60.0,
                cpuHostPercent = 25.0,
                cpuGuestPercent = null,
                vCpuUtilization = 50.0,
                ramGuestMb = null,
                cacheHitRate = null,
                translationMs = null,
            )
        )
        val window = history.windows().first()
        assertEquals(60.0, window.fpsAvg)
        assertNull(window.cpuGuestAvg)
        assertNull(window.ramGuestAvgMb)
        assertNull(window.cacheHitRateAvg)
        assertNull(window.translationMsAvg)
    }

    @Test
    fun latestUpdatePreservesOtherMeasurements() {
        val history = MetricsHistory()
        history.add(
            VmMetricsSample(
                timestampMs = System.currentTimeMillis(),
                cpuHostPercent = 12.0,
                temperatureC = 40.0,
            )
        )
        history.updateLatest { it.copy(fps = 59.5) }
        val sample = history.all().single()
        assertEquals(59.5, sample.fps)
        assertEquals(12.0, sample.cpuHostPercent)
        assertEquals(40.0, sample.temperatureC)
    }
}
