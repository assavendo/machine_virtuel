package com.assavendo.machinevirtuel.performance

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GuestCpuStatsTest {
    @Test
    fun parsesLinuxQgaUnionAndIgnoresDuplicateGuestAccountingFields() {
        val response = JSONObject(
            """
            {
              "return": [
                {
                  "type": "linux",
                  "u": {
                    "cpu": 0,
                    "user": 150,
                    "nice": 10,
                    "system": 25,
                    "idle": 845,
                    "iowait": 60,
                    "irq": 12,
                    "softirq": 8,
                    "steal": 0,
                    "guest": 100,
                    "guestnice": 5
                  }
                },
                {
                  "type": "other",
                  "u": { "cpu": 1 }
                }
              ]
            }
            """.trimIndent()
        )

        val stats = requireNotNull(GuestCpuStatsParser.parse(response))
        assertEquals(1, stats.size)
        assertEquals(0, stats.single().cpu)
        assertEquals(205L, stats.single().busyMs)
        assertEquals(1110L, stats.single().totalMs)
    }

    @Test
    fun acceptsQapiDataPayloadAliasAndOptionalCounters() {
        val response = JSONObject(
            """
            {
              "return": [
                {
                  "type": "linux",
                  "data": {
                    "cpu": 2,
                    "user": 10,
                    "nice": 0,
                    "system": 5,
                    "idle": 90
                  }
                }
              ]
            }
            """.trimIndent()
        )
        val stats = requireNotNull(GuestCpuStatsParser.parse(response)).single()
        assertEquals(0L, stats.ioWaitMs)
        assertEquals(0L, stats.irqMs)
        assertEquals(105L, stats.totalMs)
    }

    @Test
    fun rejectsMalformedNegativeAndDuplicateCpuCounters() {
        assertNull(GuestCpuStatsParser.parse(JSONObject("""{"return":[]}""")))
        assertNull(
            GuestCpuStatsParser.parse(
                JSONObject(
                    """{"return":[{"type":"linux","u":{"cpu":0,"user":-1,"nice":0,"system":0,"idle":0}}]}"""
                )
            )
        )
        assertNull(
            GuestCpuStatsParser.parse(
                JSONObject(
                    """{"return":[{"type":"linux","u":{"cpu":0,"user":1,"nice":0,"system":0,"idle":1}},{"type":"linux","u":{"cpu":0,"user":2,"nice":0,"system":0,"idle":2}}]}"""
                )
            )
        )
    }

    @Test
    fun computesGuestCpuIoWaitAndInterruptPercentFromCumulativeDeltas() {
        val tracker = GuestCpuStatsTracker()
        val first = listOf(GuestCpuCounters(0, 100, 10, 20, 800, 50, 10, 5, 0))
        val next = listOf(GuestCpuCounters(0, 150, 10, 25, 845, 60, 12, 8, 0))

        assertNull(tracker.update(first, 1_000L))
        val metrics = requireNotNull(tracker.update(next, 2_000L))

        assertEquals(60L, metrics.guestCpuTimeDeltaMs)
        assertEquals(1_000L, metrics.intervalMs)
        assertEquals(60.0 / 115.0 * 100.0, metrics.cpuBusyPercent, 0.0001)
        assertEquals(10.0 / 115.0 * 100.0, metrics.ioWaitPercent, 0.0001)
        assertEquals(5.0 / 115.0 * 100.0, metrics.interruptPercent, 0.0001)
    }

    @Test
    fun resetsBaselineAfterCounterResetOrMissingAgentSample() {
        val tracker = GuestCpuStatsTracker()
        tracker.update(listOf(GuestCpuCounters(0, 100, 0, 0, 100, 0, 0, 0, 0)), 1_000L)
        val reset = listOf(GuestCpuCounters(0, 1, 0, 0, 1, 0, 0, 0, 0))
        assertNull(tracker.update(reset, 2_000L))
        tracker.reset()
        assertNull(tracker.update(reset, 3_000L))
    }
}
