package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.engine.NativeEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeEngineTest {
    @Test
    fun translationDecisionAlwaysReturnsAValidTier() {
        listOf(0L, 99L, 100L, 10_000L, 100_000L).forEach { executions ->
            val decision = NativeEngine.translationDecision(executions)
            assertTrue(decision.tier in setOf("COLD", "WARM", "HOT", "VERY_HOT"))
        }
    }

    @Test
    fun negativeExecutionCountsAreClamped() {
        val decision = NativeEngine.translationDecision(-10L)
        assertEquals("COLD", decision.tier)
    }
}
