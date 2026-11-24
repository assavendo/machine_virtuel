package com.assavendo.machinevirtuel.performance

import com.assavendo.machinevirtuel.cache.TranslationCacheKey
import com.assavendo.machinevirtuel.cache.TranslationCacheManager
import com.assavendo.machinevirtuel.cache.TranslationProfile as CachedTranslationProfile

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
    fun observe(
        key: TranslationCacheKey,
        translatedMs: Double,
        cacheHit: Boolean,
        allowGlobalLevel3: Boolean = false,
    ): TranslationProfile {
        val cacheId = cacheIdentity(key)
        val existing = profiles[cacheId] ?: cache.getProfile(key, allowGlobalLevel3)?.let {
            TranslationProfile(
                fingerprint = cacheId,
                tier = runCatching { TranslationTier.valueOf(it.state) }.getOrDefault(TranslationTier.COLD),
                executions = it.executionCount,
                translationMs = 0.0,
                cacheHits = 0,
                cacheMisses = 0,
            )
        } ?: TranslationProfile(cacheId)

        val previousTier = existing.tier
        existing.executions++
        existing.translationMs += translatedMs.coerceAtLeast(0.0)
        if (cacheHit) existing.cacheHits++ else existing.cacheMisses++
        existing.tier = when {
            existing.executions >= 100_000 -> TranslationTier.VERY_HOT
            existing.executions >= 10_000 -> TranslationTier.HOT
            existing.executions >= 100 -> TranslationTier.WARM
            else -> TranslationTier.COLD
        }
        profiles[cacheId] = existing

        val shouldPersist = existing.executions == 1L ||
            existing.executions % 64L == 0L ||
            existing.tier != previousTier

        if (shouldPersist) {
            cache.putProfile(
                key,
                CachedTranslationProfile(
                    state = existing.tier.name,
                    hotBlocks = 0L,
                    coldBlocks = 0L,
                    executionCount = existing.executions,
                    lastSeenMs = System.currentTimeMillis(),
                    compatibilityFingerprint = cacheId,
                ),
                allowGlobalLevel3 = allowGlobalLevel3,
            )
        }
        return existing.copy()
    }

    @Synchronized
    fun invalidate(key: TranslationCacheKey) {
        profiles.remove(cacheIdentity(key))
        cache.invalidate(key)
    }

    @Synchronized
    fun clear() {
        profiles.clear()
    }

    private fun cacheIdentity(key: TranslationCacheKey): String =
        cache.fingerprint(
            key.vmId,
            key.guestFingerprint,
            key.translatorVersion,
            key.hostArch,
            key.hostFeatures,
            key.level.toString(),
        )
}
