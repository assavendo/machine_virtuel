package com.assavendo.machinevirtuel.cache

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranslationCacheManagerTest {
    private fun tempRoot(): File = Files.createTempDirectory("phoenix-translation-cache-test-").toFile()

    private fun policy(
        persistentBytes: Long = 4L * 1024L * 1024L,
        l0Entries: Int = 8,
        l1Entries: Int = 32,
        l0Bytes: Long = 1024,
        l1Bytes: Long = 4096,
        cleanupWrites: Int = 64,
        global: Boolean = false,
    ) = TranslationCachePolicy(
        l0MaxEntries = l0Entries,
        l1MaxEntries = l1Entries,
        l0MaxBytes = l0Bytes,
        l1MaxBytes = l1Bytes,
        maxPersistentBytes = persistentBytes,
        maxArtifactBytes = 1024 * 1024,
        cleanupIntervalWrites = cleanupWrites,
        globalLevel3Enabled = global,
    )

    private fun key(
        cache: TranslationCacheManager,
        vmId: String,
        code: ByteArray = byteArrayOf(0x10, 0x20, 0x30),
        guestBuild: String = "linux-build-2026.11",
        translatorVersion: String = "phoenix-translator-1",
        hostArch: String = "aarch64",
        hostFeatures: String = "asimd,crypto",
        level: Int = 3,
    ): TranslationCacheKey = cache.key(
        vmId = vmId,
        code = code,
        guestBuild = guestBuild,
        translatorVersion = translatorVersion,
        hostArch = hostArch,
        hostFeatures = hostFeatures,
        level = level,
    )

    @Test
    fun codeFingerprintTracksExactBytesAndKeyCapturesAllCompatibilityInputs() {
        val root = tempRoot()
        try {
            val cache = TranslationCacheManager.forTesting(root)
            val first = key(cache, "vm-a")
            val sameCodeOtherVm = key(cache, "vm-b")
            val changedCode = key(cache, "vm-c", code = byteArrayOf(0x10, 0x20, 0x31))
            val changedGuest = key(cache, "vm-d", guestBuild = "linux-build-next")
            val changedTranslator = key(cache, "vm-e", translatorVersion = "phoenix-translator-2")
            val changedArchitecture = key(cache, "vm-f", hostArch = "x86_64")
            val changedFeatures = key(cache, "vm-g", hostFeatures = "asimd")
            val changedLevel = key(cache, "vm-h", level = 2)

            assertEquals(first.codeFingerprint, sameCodeOtherVm.codeFingerprint)
            assertNotEquals(first.codeFingerprint, changedCode.codeFingerprint)
            assertNotEquals(first.guestFingerprint, changedGuest.guestFingerprint)
            assertNotEquals(first.translatorVersion, changedTranslator.translatorVersion)
            assertNotEquals(first.hostArch, changedArchitecture.hostArch)
            assertNotEquals(first.hostFeatures, changedFeatures.hostFeatures)
            assertNotEquals(first.level, changedLevel.level)
            assertEquals(64, first.codeFingerprint.length)
            assertFailsWith<IllegalArgumentException> {
                cache.key("vm-invalid", ByteArray(0), "guest", "translator", "aarch64", "", 3)
            }
            assertFailsWith<IllegalArgumentException> {
                cache.key("vm-invalid-level", byteArrayOf(1), "guest", "translator", "aarch64", "", 4)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun artifactCacheUsesL0L1AndPersistentL2WithDefensiveCopies() {
        val root = tempRoot()
        try {
            val limits = policy(l0Entries = 1, l1Entries = 4, l0Bytes = 4, l1Bytes = 16)
            val cache = TranslationCacheManager.forTesting(root, limits)
            val first = key(cache, "vm-a", code = byteArrayOf(1))
            val second = key(cache, "vm-a", code = byteArrayOf(2))
            val payload = byteArrayOf(11, 12, 13)

            cache.putArtifact(first, payload, relocationSafe = true)
            val l0 = requireNotNull(cache.getArtifact(first))
            assertContentEquals(payload, l0)
            l0[0] = 99
            assertContentEquals(payload, cache.getArtifact(first))
            assertEquals(2L, cache.stats().l0Hits)

            cache.putArtifact(second, byteArrayOf(21, 22, 23), relocationSafe = true)
            assertContentEquals(payload, cache.getArtifact(first))
            assertEquals(1L, cache.stats().l1Hits)

            val reopened = TranslationCacheManager.forTesting(root, limits)
            assertContentEquals(payload, reopened.getArtifact(first))
            assertEquals(1L, reopened.stats().l2Hits)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun l0AndL1EntryBudgetsAreSharedBetweenMetadataAndArtifacts() {
        val root = tempRoot()
        try {
            val cache = TranslationCacheManager.forTesting(
                root,
                policy(l0Entries = 1, l1Entries = 1),
            )
            val metadataKey = key(cache, "vm-shared-entry-budget", code = byteArrayOf(1))
            val artifactKey = key(cache, "vm-shared-entry-budget", code = byteArrayOf(2))

            cache.putManifest(metadataKey, "portable manifest")
            cache.putArtifact(artifactKey, ByteArray(32) { it.toByte() }, relocationSafe = true)

            // One resident entry per tier means the newer artifact evicts the manifest
            // from both tiers; separate per-map limits would incorrectly retain both.
            assertEquals("portable manifest", cache.getManifest(metadataKey))
            assertEquals(1L, cache.stats().l2Hits)

            assertContentEquals(
                ByteArray(32) { it.toByte() },
                cache.getArtifact(artifactKey),
            )
            assertEquals(2L, cache.stats().l2Hits)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sharedLruRefreshesMetadataAcrossArtifactInsertions() {
        val root = tempRoot()
        try {
            val cache = TranslationCacheManager.forTesting(
                root,
                policy(l0Entries = 2, l1Entries = 2, l0Bytes = 4096, l1Bytes = 4096),
            )
            val metadataKey = key(cache, "vm-shared-lru", code = byteArrayOf(1))
            val olderArtifactKey = key(cache, "vm-shared-lru", code = byteArrayOf(2))
            val newerArtifactKey = key(cache, "vm-shared-lru", code = byteArrayOf(3))
            val olderArtifact = ByteArray(32) { (it + 1).toByte() }
            val newerArtifact = ByteArray(32) { (it + 33).toByte() }

            cache.putManifest(metadataKey, "recent manifest")
            cache.putArtifact(olderArtifactKey, olderArtifact, relocationSafe = true)

            // A read refreshes metadata recency in both memory tiers.
            assertEquals("recent manifest", cache.getManifest(metadataKey))
            cache.putArtifact(newerArtifactKey, newerArtifact, relocationSafe = true)

            // The older artifact is now the LRU victim, while the recently read
            // manifest remains resident in L0 rather than being reloaded from L2.
            assertEquals("recent manifest", cache.getManifest(metadataKey))
            assertEquals(2L, cache.stats().l0Hits)
            assertContentEquals(newerArtifact, cache.getArtifact(newerArtifactKey))
            assertEquals(3L, cache.stats().l0Hits)
            assertContentEquals(olderArtifact, cache.getArtifact(olderArtifactKey))
            assertEquals(1L, cache.stats().l2Hits)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun l0AndL1ByteBudgetsAreSharedBetweenMetadataAndArtifacts() {
        val root = tempRoot()
        try {
            val cache = TranslationCacheManager.forTesting(
                root,
                policy(l0Entries = 8, l1Entries = 8, l0Bytes = 4096, l1Bytes = 4096),
            )
            val metadataKey = key(cache, "vm-shared-byte-budget", code = byteArrayOf(3))
            val artifactKey = key(cache, "vm-shared-byte-budget", code = byteArrayOf(4))
            val artifact = ByteArray(3800) { (it % 251).toByte() }

            cache.putManifest(metadataKey, "small manifest")
            cache.putArtifact(artifactKey, artifact, relocationSafe = true)

            // Both entries fit individually, but their combined resident size exceeds
            // the 4 KiB tier limit and the LRU manifest must be fetched from L2.
            assertEquals("small manifest", cache.getManifest(metadataKey))
            assertEquals(1L, cache.stats().l2Hits)
            assertContentEquals(artifact, cache.getArtifact(artifactKey))
            assertEquals(2L, cache.stats().l2Hits)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun maximumAllowedMetadataPayloadSurvivesPersistentReopen() {
        val root = tempRoot()
        try {
            val cache = TranslationCacheManager.forTesting(root)
            val key = key(cache, "vm-max-metadata")
            val metadata = "x".repeat(1_048_576)

            cache.putManifest(key, metadata)
            val reopened = TranslationCacheManager.forTesting(root)
            assertEquals(metadata, reopened.getManifest(key))
            assertEquals(1L, reopened.stats().l2Hits)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun globalL3SharesOnlyCompatibleArtifactsAcrossDifferentVmIds() {
        val root = tempRoot()
        try {
            val cachePolicy = policy(global = true)
            val source = TranslationCacheManager.forTesting(root, cachePolicy)
            val destination = TranslationCacheManager.forTesting(root, cachePolicy)
            val vmA = key(source, "vm-a")
            val vmB = key(destination, "vm-b")
            val payload = byteArrayOf(1, 3, 3, 7, 9)

            source.putArtifact(vmA, payload, relocationSafe = true, allowGlobalLevel3 = true)
            assertContentEquals(payload, destination.getArtifact(vmB, allowGlobalLevel3 = true))
            assertEquals(1L, destination.stats().l3Hits)

            val wrongGuest = key(destination, "vm-c", guestBuild = "different-linux-build")
            val wrongTranslator = key(destination, "vm-d", translatorVersion = "translator-99")
            val wrongArch = key(destination, "vm-e", hostArch = "x86_64")
            val wrongFeatures = key(destination, "vm-f", hostFeatures = "different-cpu-features")
            val wrongCode = key(destination, "vm-g", code = byteArrayOf(9, 9, 9))

            assertNull(destination.getArtifact(wrongGuest, allowGlobalLevel3 = true))
            assertNull(destination.getArtifact(wrongTranslator, allowGlobalLevel3 = true))
            assertNull(destination.getArtifact(wrongArch, allowGlobalLevel3 = true))
            assertNull(destination.getArtifact(wrongFeatures, allowGlobalLevel3 = true))
            assertNull(destination.getArtifact(wrongCode, allowGlobalLevel3 = true))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun globalMetadataCanBeReusedButRuntimeProfilesRemainVmLocal() {
        val root = tempRoot()
        try {
            val cachePolicy = policy(global = true)
            val firstCache = TranslationCacheManager.forTesting(root, cachePolicy)
            val secondCache = TranslationCacheManager.forTesting(root, cachePolicy)
            val firstKey = key(firstCache, "vm-a")
            val secondKey = key(secondCache, "vm-b")

            firstCache.putManifest(firstKey, "portable-metadata", allowGlobalLevel3 = true)
            assertEquals("portable-metadata", secondCache.getManifest(secondKey, allowGlobalLevel3 = true))
            assertEquals(1L, secondCache.stats().l3Hits)

            val profile = TranslationProfile(
                state = "HOT",
                hotBlocks = 12,
                coldBlocks = 4,
                executionCount = 16,
                cacheHits = 10,
                cacheMisses = 6,
                totalTranslationMs = 2.5,
                lastSeenMs = 1000,
                compatibilityFingerprint = firstCache.compatibilityFingerprint(firstKey),
            )
            firstCache.putProfile(firstKey, profile, allowGlobalLevel3 = true)
            assertEquals(profile, firstCache.getProfile(firstKey, allowGlobalLevel3 = true))
            assertNull(secondCache.getProfile(secondKey, allowGlobalLevel3 = true))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun explicitCallerFlagCannotBypassDisabledGlobalLevel3Policy() {
        val root = tempRoot()
        try {
            val disabledPolicy = policy(global = false)
            val cacheA = TranslationCacheManager.forTesting(root, disabledPolicy)
            val cacheB = TranslationCacheManager.forTesting(root, disabledPolicy)
            val keyA = key(cacheA, "vm-a")
            val keyB = key(cacheB, "vm-b")

            cacheA.putArtifact(
                keyA,
                byteArrayOf(5, 4, 3),
                relocationSafe = true,
                allowGlobalLevel3 = true,
            )

            assertNull(cacheB.getArtifact(keyB, allowGlobalLevel3 = true))
            assertEquals(0L, cacheB.stats().l3Hits)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun invalidatingOneVmPreservesSharedL3UntilExplicitGlobalInvalidation() {
        val root = tempRoot()
        try {
            val cachePolicy = policy(global = true)
            val cacheA = TranslationCacheManager.forTesting(root, cachePolicy)
            val cacheB = TranslationCacheManager.forTesting(root, cachePolicy)
            val cacheC = TranslationCacheManager.forTesting(root, cachePolicy)
            val keyA = key(cacheA, "vm-a")
            val keyB = key(cacheB, "vm-b")
            val keyC = key(cacheC, "vm-c")
            val payload = byteArrayOf(4, 5, 6, 7)

            cacheA.putArtifact(keyA, payload, relocationSafe = true, allowGlobalLevel3 = true)
            cacheA.invalidateAll("vm-a")
            assertContentEquals(payload, cacheB.getArtifact(keyB, allowGlobalLevel3 = true))
            assertEquals(1L, cacheB.stats().l3Hits)

            cacheA.invalidateGlobal(keyA)
            assertNull(cacheC.getArtifact(keyC, allowGlobalLevel3 = true))
            assertTrue(cacheB.has(keyB, allowGlobalLevel3 = true))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun persistentArtifactWriteRequiresExplicitRelocationSafetyDeclaration() {
        val root = tempRoot()
        try {
            val cache = TranslationCacheManager.forTesting(root)
            val key = key(cache, "vm-unrelocatable")
            assertFailsWith<IllegalArgumentException> {
                cache.putArtifact(
                    key,
                    byteArrayOf(1, 2, 3),
                    relocationSafe = false,
                )
            }
            assertFalse(cache.has(key))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptedArtifactIsRejectedByItsHeaderAndPayloadDigest() {
        val root = tempRoot()
        try {
            val cache = TranslationCacheManager.forTesting(root, policy())
            val key = key(cache, "vm-corrupt")
            cache.putArtifact(key, byteArrayOf(7, 8, 9, 10), relocationSafe = true)
            val artifactFile = File(root, cache.fingerprint(key.vmId))
                .listFiles()
                ?.single { it.name.endsWith(".artifact") }
                ?: error("Expected a persisted per-VM artifact file.")
            val bytes = artifactFile.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x7f).toByte()
            artifactFile.writeBytes(bytes)

            val reopened = TranslationCacheManager.forTesting(root, policy())
            assertNull(reopened.getArtifact(key))
            assertEquals(1L, reopened.stats().misses)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cleanupEnforcesPersistentQuotaAndConfigurationTriggersImmediatePruning() {
        val root = tempRoot()
        try {
            val cache = TranslationCacheManager.forTesting(
                root,
                policy(persistentBytes = 4096, cleanupWrites = 1),
            )
            repeat(8) { index ->
                val key = key(cache, "vm-$index", code = byteArrayOf(index.toByte()))
                cache.putManifest(key, "metadata-$index-" + "x".repeat(256))
            }
            assertTrue(cache.stats().bytes <= 4096L)

            cache.configure(cache.currentPolicy().copy(maxPersistentBytes = 1024L))
            assertTrue(cache.stats().bytes <= 1024L)
            assertEquals(1024L, cache.stats().maxPersistentBytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cachePolicyRejectsUnsafeOrInconsistentBudgets() {
        assertFailsWith<IllegalArgumentException> {
            TranslationCachePolicy(l0MaxEntries = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            TranslationCachePolicy(l0MaxBytes = 4096, l1MaxBytes = 1024)
        }
        assertFailsWith<IllegalArgumentException> {
            TranslationCachePolicy(maxArtifactBytes = 128 * 1024 * 1024)
        }
    }

    @Test
    fun artifactsAboveMemoryBudgetsFallBackToPersistentVmTier() {
        val root = tempRoot()
        try {
            val limits = policy(
                l0Entries = 4,
                l1Entries = 8,
                l0Bytes = 2,
                l1Bytes = 4,
            )
            val cache = TranslationCacheManager.forTesting(root, limits)
            val key = key(cache, "vm-large-artifact")
            val payload = ByteArray(16) { it.toByte() }

            cache.putArtifact(key, payload, relocationSafe = true)
            assertContentEquals(payload, cache.getArtifact(key))
            assertEquals(1L, cache.stats().l2Hits)
            assertEquals(0L, cache.stats().l0Hits)
            assertEquals(0L, cache.stats().l1Hits)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aggregateReadsCountersFromPersistedProfileMetadata() {
        val root = tempRoot()
        try {
            val writer = TranslationCacheManager.forTesting(root, policy())
            val key = key(writer, "vm-profile")
            val profile = TranslationProfile(
                state = "HOT",
                hotBlocks = 12,
                coldBlocks = 4,
                executionCount = 16,
                cacheHits = 10,
                cacheMisses = 6,
                totalTranslationMs = 40.0,
                lastSeenMs = 1_900_000_000_000L,
                compatibilityFingerprint = writer.compatibilityFingerprint(key),
            )

            writer.putProfile(key, profile)

            // Aggregate through a newly created manager, as a later process session would.
            val aggregate = TranslationCacheManager.forTesting(root, policy()).aggregate("vm-profile")
            assertEquals(16L, aggregate.executions)
            assertEquals(10L, aggregate.cacheHits)
            assertEquals(6L, aggregate.cacheMisses)
            assertEquals(0.625, aggregate.cacheHitRate!!, absoluteTolerance = 1e-12)
            assertEquals(2.5, aggregate.averageTranslationMs!!, absoluteTolerance = 1e-12)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun profileCounterAdditionRejectsLongOverflow() {
        val root = tempRoot()
        try {
            val cache = TranslationCacheManager.forTesting(root, policy())
            val key = key(cache, "vm-overflow")
            val profile = TranslationProfile(
                state = "HOT",
                hotBlocks = 1,
                coldBlocks = 0,
                executionCount = Long.MAX_VALUE,
                cacheHits = Long.MAX_VALUE,
                cacheMisses = 1,
                totalTranslationMs = 1.0,
                lastSeenMs = 1L,
                compatibilityFingerprint = cache.compatibilityFingerprint(key),
            )
            assertFailsWith<IllegalArgumentException> {
                cache.putProfile(key, profile)
            }
        } finally {
            root.deleteRecursively()
        }
    }


    @Test
    fun artifactL0HitRefreshesMatchingArtifactInL1() {
        val root = tempRoot()
        try {
            val cache = TranslationCacheManager.forTesting(
                root,
                policy(l0Entries = 1, l1Entries = 2, l0Bytes = 4096, l1Bytes = 4096),
            )
            val olderManifest = key(cache, "vm-artifact-lru", code = byteArrayOf(1))
            val activeArtifact = key(cache, "vm-artifact-lru", code = byteArrayOf(2))
            val newerManifest = key(cache, "vm-artifact-lru", code = byteArrayOf(3))
            val artifact = ByteArray(24) { (it + 7).toByte() }

            cache.putManifest(olderManifest, "older")
            cache.putArtifact(activeArtifact, artifact, relocationSafe = true)
            assertContentEquals(artifact, cache.getArtifact(activeArtifact))
            cache.putManifest(newerManifest, "newer")

            // L0 has only the newer manifest; the artifact must still be present in L1
            // because its L0 read refreshed the corresponding L1 recency.
            assertContentEquals(artifact, cache.getArtifact(activeArtifact))
            assertEquals(1L, cache.stats().l1Hits)
            assertEquals(0L, cache.stats().l2Hits)
        } finally {
            root.deleteRecursively()
        }
    }

}
