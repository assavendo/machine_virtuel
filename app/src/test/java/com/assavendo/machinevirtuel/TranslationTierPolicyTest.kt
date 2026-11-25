package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.performance.TranslationTier
import com.assavendo.machinevirtuel.performance.TranslationTierPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranslationTierPolicyTest {
    @Test
    fun executionThresholdsAreStable() {
        assertEquals(TranslationTier.COLD, TranslationTierPolicy.fromExecutions(0))
        assertEquals(TranslationTier.COLD, TranslationTierPolicy.fromExecutions(99))
        assertEquals(TranslationTier.WARM, TranslationTierPolicy.fromExecutions(100))
        assertEquals(TranslationTier.WARM, TranslationTierPolicy.fromExecutions(9_999))
        assertEquals(TranslationTier.HOT, TranslationTierPolicy.fromExecutions(10_000))
        assertEquals(TranslationTier.HOT, TranslationTierPolicy.fromExecutions(99_999))
        assertEquals(TranslationTier.VERY_HOT, TranslationTierPolicy.fromExecutions(100_000))
    }

    @Test
    fun malformedNativeNamesFallbackCleanly() {
        assertEquals(TranslationTier.HOT, TranslationTierPolicy.fromNativeName("hot"))
        assertEquals(TranslationTier.VERY_HOT, TranslationTierPolicy.fromNativeName("VERY_HOT"))
        assertNull(TranslationTierPolicy.fromNativeName("unknown"))
        assertNull(TranslationTierPolicy.fromNativeName(null))
    }
}
