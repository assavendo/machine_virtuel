package com.assavendo.machinevirtuel

import android.content.Context
import android.content.ContextWrapper
import com.assavendo.machinevirtuel.cache.TranslationCacheManager
import com.assavendo.machinevirtuel.cache.TranslationProfile
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranslationCacheManagerTest {
    private class CacheContext(private val cacheFilesDir: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = cacheFilesDir
    }

    private fun withCache(block: (CacheContext) -> Unit) {
        val directory = Files.createTempDirectory("phoenixvm-translation-cache-test").toFile()
        try {
            block(CacheContext(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun TranslationCacheManager.testKey(
        vmId: String = "vm-alpha",
        guestBuild: String = "guest-build-42",
        translatorVersion: String = "qemu-11.1.3-tcg-v1",
        hostArch: String = "aarch64",
        hostFeatures: String = "asimd,aes,sha2",
        level: Int = 2,
    ) = key(vmId, guestBuild, translatorVersion, hostArch, hostFeatures, level)

    @Test
    fun l2MetadataSurvivesManagerRecreationAndRemainsVmScoped() = withCache { context ->
        val writer = TranslationCacheManager(context)
        val key = writer.testKey()
        val metadata = """{"state":"HOT","hot_blocks":17}"""

        writer.putManifest(key, metadata)

        // A new manager represents a new application/process lifetime.
        val reader = TranslationCacheManager(context)
        assertEquals(metadata, reader.getManifest(key))
        assertTrue(reader.stats("vm-alpha").files > 0)

        val otherVmKey = reader.testKey(vmId = "vm-beta")
        assertNull(reader.getManifest(otherVmKey))
    }

    @Test
    fun compatibilityFingerprintRejectsDifferentTranslatorGuestAndHost() = withCache { context ->
        val writer = TranslationCacheManager(context)
        val key = writer.testKey()
        writer.putManifest(key, """{"state":"WARM"}""")

        val reader = TranslationCacheManager(context)
        val incompatibleKeys = listOf(
            reader.testKey(guestBuild = "guest-build-43"),
            reader.testKey(translatorVersion = "qemu-11.1.2-tcg-v1"),
            reader.testKey(hostArch = "x86_64"),
            reader.testKey(hostFeatures = "asimd"),
            reader.testKey(level = 1),
            reader.testKey(vmId = "vm-beta"),
        )

        incompatibleKeys.forEach { candidate ->
            assertNull(reader.getManifest(candidate), "Unexpected metadata hit for incompatible key: $candidate")
        }
    }

    @Test
    fun selectiveInvalidationAndZeroQuotaCleanupRemovePersistentMetadata() = withCache { context ->
        val manager = TranslationCacheManager(context)
        val key = manager.testKey()
        manager.putManifest(key, """{"state":"HOT"}""")

        val reader = TranslationCacheManager(context)
        assertNotNull(reader.getManifest(key))

        manager.invalidate(key)
        assertNull(TranslationCacheManager(context).getManifest(key))

        manager.putManifest(key, """{"state":"WARM"}""")
        manager.cleanup(0L)

        val afterCleanup = TranslationCacheManager(context)
        assertEquals(0L, afterCleanup.stats().bytes)
        assertNull(afterCleanup.getManifest(key))
    }

    @Test
    fun corruptedPersistentEnvelopeIsRejectedAsMiss() = withCache { context ->
        val writer = TranslationCacheManager(context)
        val key = writer.testKey()
        writer.putManifest(key, """{"state":"HOT"}""")

        val entry = File(context.filesDir, "translation-cache")
            .walkTopDown()
            .firstOrNull { it.isFile && it.extension == "meta" }
        assertNotNull(entry)
        entry.writeText("{not-valid-json", Charsets.UTF_8)

        val reader = TranslationCacheManager(context)
        assertNull(reader.getManifest(key))
        assertEquals(1L, reader.stats().misses)
    }

    @Test
    fun profileRequiresMatchingCompatibilityKeyAndConsistentCounters() = withCache { context ->
        val manager = TranslationCacheManager(context)
        val key = manager.testKey()
        val profile = TranslationProfile(
            state = "HOT",
            hotBlocks = 11,
            coldBlocks = 2,
            executionCount = 13,
            cacheHits = 9,
            cacheMisses = 4,
            totalTranslationMs = 31.5,
            lastSeenMs = 1_900_000_000_000L,
            compatibilityFingerprint = manager.fingerprint(
                key.vmId,
                key.guestFingerprint,
                key.translatorVersion,
                key.hostArch,
                key.hostFeatures,
                key.level.toString(),
            ),
        )

        manager.putProfile(key, profile)
        assertEquals(profile, TranslationCacheManager(context).getProfile(key))

        val mismatched = profile.copy(compatibilityFingerprint = "0".repeat(64))
        assertFailsWith<IllegalArgumentException> {
            manager.putProfile(key, mismatched)
        }
        assertFailsWith<IllegalArgumentException> {
            manager.putProfile(key, profile.copy(cacheHits = 10, cacheMisses = 4))
        }
    }
}
