package com.assavendo.machinevirtuel.performance

import com.assavendo.machinevirtuel.cache.TranslationCacheManager
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranslationEngineTest {
    @Test
    fun observePersistsVmLocalProfileWithCanonicalCacheFingerprint() {
        val root = Files.createTempDirectory("phoenix-translation-engine-test-").toFile()
        try {
            val cache = TranslationCacheManager.forTesting(root)
            val key = cache.key(
                vmId = "vm-profile",
                code = byteArrayOf(0x11, 0x22, 0x33),
                guestBuild = "guest-build",
                translatorVersion = "translator-v1",
                hostArch = "aarch64",
                hostFeatures = "asimd,crypto",
                level = 2,
            )
            val engine = TranslationEngine(cache)

            val observed = engine.observe(
                key = key,
                translatedMs = 2.5,
                cacheHit = false,
                allowGlobalLevel3 = true,
            )
            val persisted = cache.getProfile(key, allowGlobalLevel3 = true)

            assertEquals(TranslationTier.COLD, observed.tier)
            assertEquals(1L, observed.executions)
            assertEquals(1L, observed.cacheMisses)
            assertEquals(0L, observed.cacheHits)
            requireNotNull(persisted)
            assertEquals(observed.tier.name, persisted.state)
            assertEquals(observed.executions, persisted.executionCount)
            assertEquals(cache.compatibilityFingerprint(key), persisted.compatibilityFingerprint)
            assertEquals(2.5, persisted.totalTranslationMs)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun engineUsesOnlyTranslatorProvidedPortableArtifactsAndInvalidatesPerVm() {
        val root = Files.createTempDirectory("phoenix-translation-artifact-test-").toFile()
        try {
            val cache = TranslationCacheManager.forTesting(root)
            val key = cache.key(
                vmId = "vm-artifact",
                code = byteArrayOf(0x01, 0x02),
                guestBuild = "guest-build",
                translatorVersion = "translator-v1",
                hostArch = "aarch64",
                hostFeatures = "asimd",
                level = 2,
            )
            val engine = TranslationEngine(cache)
            val artifact = byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0x00)

            engine.storeArtifact(key, artifact)
            val returned = requireNotNull(engine.cachedArtifact(key))
            assertContentEquals(artifact, returned)
            returned[0] = 0
            assertContentEquals(artifact, engine.cachedArtifact(key))

            engine.invalidate(key)
            assertNull(engine.cachedArtifact(key))
        } finally {
            root.deleteRecursively()
        }
    }
}
