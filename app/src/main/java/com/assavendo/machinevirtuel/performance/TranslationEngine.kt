package com.assavendo.machinevirtuel.performance

import com.assavendo.machinevirtuel.cache.TranslationCacheKey
import com.assavendo.machinevirtuel.cache.TranslationCacheManager
import com.assavendo.machinevirtuel.cache.TranslationProfile as CacheTranslationProfile
import com.assavendo.machinevirtuel.engine.NativeEngine

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
                tier = TranslationTierPolicy.fromNativeName(it.state)
                    ?: TranslationTierPolicy.fromExecutions(it.executionCount),
                executions = it.executionCount,
                translationMs = it.totalTranslationMs,
                cacheHits = it.cacheHits,
                cacheMisses = it.cacheMisses,
            )
        } ?: TranslationProfile(cacheId)

        val previousTier = existing.tier
        existing.executions++
        existing.translationMs += translatedMs.coerceAtLeast(0.0)
        if (cacheHit) existing.cacheHits++ else existing.cacheMisses++

        val nativeDecision = NativeEngine.translationDecision(existing.executions)
        val nativeTier = TranslationTierPolicy.fromNativeName(nativeDecision.tier)
        existing.tier = if (nativeDecision.nativeAvailable && nativeTier != null) {
            nativeTier
        } else {
            TranslationTierPolicy.fromExecutions(existing.executions)
        }

        profiles[cacheId] = existing

        val shouldPersist = existing.executions == 1L ||
            existing.executions % 64L == 0L ||
            existing.tier != previousTier

        if (shouldPersist) {
            cache.putProfile(
                key,
                CacheTranslationProfile(
                    state = existing.tier.name,
                    hotBlocks = 0L,
                    coldBlocks = 0L,
                    executionCount = existing.executions,
                    cacheHits = existing.cacheHits,
                    cacheMisses = existing.cacheMisses,
                    totalTranslationMs = existing.translationMs,
                    lastSeenMs = System.currentTimeMillis(),
                    compatibilityFingerprint = cacheId,
                ),
                allowGlobalLevel3 = allowGlobalLevel3,
            )
        }
        return existing.copy()
    }

    /** Lookup a translator-provided, compatibility-validated artifact before recompilation. */
    fun cachedArtifact(key: TranslationCacheKey, allowGlobalLevel3: Boolean = false): ByteArray? =
        cache.getArtifact(key, allowGlobalLevel3)

    /** Persist only artifacts that the active translator declares relocation-safe. */
    fun storeArtifact(
        key: TranslationCacheKey,
        artifact: ByteArray,
        allowGlobalLevel3: Boolean = false,
    ) = cache.putArtifact(key, artifact, allowGlobalLevel3)

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
        cache.compatibilityFingerprint(key)
}
