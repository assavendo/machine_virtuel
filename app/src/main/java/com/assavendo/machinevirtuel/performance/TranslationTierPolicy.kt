package com.assavendo.machinevirtuel.performance

/**
 * Pure tier policy shared by native-aware profiling and deterministic tests.
 *
 * QEMU TCG remains the component that executes and invalidates translated
 * blocks. This policy only classifies activity and controls profile metadata.
 */
object TranslationTierPolicy {
    fun fromExecutions(executions: Long): TranslationTier {
        val safe = executions.coerceAtLeast(0L)
        return when {
            safe >= 100_000L -> TranslationTier.VERY_HOT
            safe >= 10_000L -> TranslationTier.HOT
            safe >= 100L -> TranslationTier.WARM
            else -> TranslationTier.COLD
        }
    }

    fun fromNativeName(name: String?): TranslationTier? =
        when (name?.uppercase()) {
            "COLD" -> TranslationTier.COLD
            "WARM" -> TranslationTier.WARM
            "HOT" -> TranslationTier.HOT
            "VERY_HOT" -> TranslationTier.VERY_HOT
            else -> null
        }
}
