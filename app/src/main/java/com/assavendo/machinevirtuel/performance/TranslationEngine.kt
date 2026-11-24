package com.assavendo.machinevirtuel.performance

import com.assavendo.machinevirtuel.cache.TranslationCacheManager

enum class TranslationTier { COLD, WARM, HOT, VERY_HOT }

data class TranslationProfile(
    val fingerprint: String,
    var tier: TranslationTier = TranslationTier.COLD,
    var executions: Long = 0,
    var translationMs: Double = 0.0,
    var cacheHits: Long = 0,
    var cacheMisses: Long = 0,
)

class TranslationEngine(private val cache: TranslationCacheManager) {
    private val profiles = mutableMapOf<String, TranslationProfile>()

    @Synchronized
    fun observe(key: String, translatedMs: Double, cacheHit: Boolean): TranslationProfile {
        val profile = profiles.getOrPut(key) { TranslationProfile(key) }
        profile.executions++
        profile.translationMs += translatedMs.coerceAtLeast(0.0)
        if (cacheHit) profile.cacheHits++ else profile.cacheMisses++
        profile.tier = when {
            profile.executions >= 100_000 -> TranslationTier.VERY_HOT
            profile.executions >= 10_000 -> TranslationTier.HOT
            profile.executions >= 100 -> TranslationTier.WARM
            else -> TranslationTier.COLD
        }
        cache.putManifest(
            "global",
            key,
            "tier=" + profile.tier + ";exec=" + profile.executions +
                ";hit=" + profile.cacheHits + ";miss=" + profile.cacheMisses,
        )
        return profile.copy()
    }

    @Synchronized fun invalidate(fingerprint: String) { profiles.remove(fingerprint) }
    @Synchronized fun clear() { profiles.clear() }
}
