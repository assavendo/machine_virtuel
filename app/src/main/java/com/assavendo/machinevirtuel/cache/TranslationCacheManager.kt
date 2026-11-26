package com.assavendo.machinevirtuel.cache

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * Caches translation/profile metadata for PhoenixVM.
 *
 * This is deliberately not a cache of generated machine code. QEMU TCG owns
 * its executable translation blocks and their invalidation while a VM runs.
 */
data class TranslationCacheStats(
    val files: Int,
    val bytes: Long,
    val hits: Long = 0,
    val misses: Long = 0,
)

data class TranslationCacheKey(
    val vmId: String,
    val guestFingerprint: String,
    val translatorVersion: String,
    val hostArch: String,
    val hostFeatures: String,
    val level: Int,
)



data class TranslationAggregate(
    val executions: Long,
    val cacheHits: Long,
    val cacheMisses: Long,
    val totalTranslationMs: Double,
) {
    val cacheHitRate: Double?
        get() {
            val total = cacheHits.toDouble() + cacheMisses.toDouble()
            return if (total > 0.0) cacheHits.toDouble() / total else null
        }

    val averageTranslationMs: Double?
        get() = if (executions > 0L) totalTranslationMs / executions.toDouble() else null
}

data class TranslationProfile(
    val state: String,
    val hotBlocks: Long,
    val coldBlocks: Long,
    val executionCount: Long,
    val cacheHits: Long = 0,
    val cacheMisses: Long = 0,
    val totalTranslationMs: Double = 0.0,
    val lastSeenMs: Long,
    val compatibilityFingerprint: String,
)

class TranslationCacheManager(context: Context) {
    companion object {
        private const val FORMAT_VERSION = 2
        private const val MEMORY_LEVEL0_MAX = 64
        private const val PROCESS_LEVEL1_MAX = 512
        private const val METADATA_MAX_BYTES = 1_048_576
        private val VALID_STATES = setOf("COLD", "WARM", "HOT", "VERY_HOT")
    }

    private val root = File(context.applicationContext.filesDir, "translation-cache").apply { mkdirs() }
    private val globalRoot = File(root, "global")
    private val memoryL0 = object : LinkedHashMap<String, String>(MEMORY_LEVEL0_MAX, 0.75f, true) {}
    private val processL1 = object : LinkedHashMap<String, String>(PROCESS_LEVEL1_MAX, 0.75f, true) {}
    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)

    fun fingerprint(vararg components: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        components.forEach { component ->
            val bytes = component.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(0.toByte())
            digest.update(bytes)
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun key(
        vmId: String,
        guestBuild: String,
        translatorVersion: String,
        hostArch: String,
        hostFeatures: String,
        level: Int,
    ): TranslationCacheKey {
        require(vmId.isNotBlank())
        require(translatorVersion.isNotBlank() && hostArch.isNotBlank())
        return TranslationCacheKey(
            vmId = vmId,
            guestFingerprint = fingerprint(guestBuild),
            translatorVersion = translatorVersion,
            hostArch = hostArch,
            hostFeatures = hostFeatures,
            level = level.coerceIn(0, 3),
        )
    }

    @Synchronized
    fun putManifest(key: TranslationCacheKey, metadata: String, allowGlobalLevel3: Boolean = false) {
        require(metadata.toByteArray(Charsets.UTF_8).size <= METADATA_MAX_BYTES) {
            "Translation profile metadata exceeds the configured limit."
        }
        val id = keyId(key)
        val envelope = JSONObject()
            .put("format_version", FORMAT_VERSION)
            .put("key_fingerprint", id)
            .put("vm_id_hash", fingerprint(key.vmId))
            .put("guest_fingerprint", key.guestFingerprint)
            .put("translator_version", key.translatorVersion)
            .put("host_arch", key.hostArch)
            .put("host_features", key.hostFeatures)
            .put("level", key.level.coerceIn(0, 3))
            .put("updated_at", System.currentTimeMillis())
            .put("metadata", metadata)
            .toString()

        // L0: hot/active metadata in the current process memory.
        memoryL0[id] = envelope
        trim(memoryL0, MEMORY_LEVEL0_MAX)
        // L1: process-local profile index.
        processL1[id] = envelope
        trim(processL1, PROCESS_LEVEL1_MAX)
        // L2: durable, VM-scoped metadata.
        writeAtomically(vmFile(key, id), envelope)

        // L3 is optional and opt-in: it contains profile metadata only.
        if (allowGlobalLevel3 && key.level >= 3) {
            writeAtomically(globalFile(id), envelope)
        }
    }

    /** Legacy metadata API retained for existing callers. */
    @Synchronized
    fun putManifest(vmId: String, fingerprint: String, metadata: String) {
        require(vmId.isNotBlank() && fingerprint.matches(Regex("[A-Fa-f0-9]{16,128}")))
        require(metadata.toByteArray(Charsets.UTF_8).size <= METADATA_MAX_BYTES)
        val safeId = fingerprint(vmId)
        val envelope = JSONObject()
            .put("format_version", 1)
            .put("vm_id_hash", safeId)
            .put("key_fingerprint", fingerprint)
            .put("updated_at", System.currentTimeMillis())
            .put("metadata", metadata)
            .toString()
        writeAtomically(File(File(root, safeId).apply { mkdirs() }, fingerprint + ".meta"), envelope)
    }

    @Synchronized
    fun getManifest(key: TranslationCacheKey, allowGlobalLevel3: Boolean = false): String? {
        val id = keyId(key)
        memoryL0[id]?.let {
            if (isValidEnvelope(it, key, id)) {
                hitCount.incrementAndGet()
                return extractMetadata(it)
            }
            memoryL0.remove(id)
        }
        processL1[id]?.let {
            if (isValidEnvelope(it, key, id)) {
                memoryL0[id] = it
                trim(memoryL0, MEMORY_LEVEL0_MAX)
                hitCount.incrementAndGet()
                return extractMetadata(it)
            }
            processL1.remove(id)
        }

        val persistent = readEnvelope(vmFile(key, id), key, id)
            ?: if (allowGlobalLevel3 && key.level >= 3) readEnvelope(globalFile(id), key, id) else null

        if (persistent == null) {
            missCount.incrementAndGet()
            return null
        }
        processL1[id] = persistent
        trim(processL1, PROCESS_LEVEL1_MAX)
        memoryL0[id] = persistent
        trim(memoryL0, MEMORY_LEVEL0_MAX)
        hitCount.incrementAndGet()
        return extractMetadata(persistent)
    }

    @Synchronized
    fun has(key: TranslationCacheKey, allowGlobalLevel3: Boolean = false): Boolean =
        getManifest(key, allowGlobalLevel3) != null

    /** Legacy existence check used by early builds. */
    fun has(vmId: String, fingerprint: String): Boolean {
        if (vmId.isBlank() || !fingerprint.matches(Regex("[A-Fa-f0-9]{16,128}"))) return false
        val vm = File(root, fingerprint(vmId))
        return File(vm, fingerprint + ".meta").isFile
    }

    @Synchronized
    fun putProfile(key: TranslationCacheKey, profile: TranslationProfile, allowGlobalLevel3: Boolean = false) {
        require(profile.state in VALID_STATES) { "Invalid translation profile state." }
        require(profile.hotBlocks >= 0 && profile.coldBlocks >= 0 && profile.executionCount >= 0)
        require(profile.compatibilityFingerprint == keyId(key)) {
            "Translation profile fingerprint does not match the cache key."
        }
        require(profile.cacheHits >= 0 && profile.cacheMisses >= 0)
        require(profile.cacheHits <= Long.MAX_VALUE - profile.cacheMisses &&
            profile.cacheHits + profile.cacheMisses == profile.executionCount
        ) {
            "Translation cache hit/miss counts must equal execution count without overflow."
        }
        require(profile.totalTranslationMs.isFinite() && profile.totalTranslationMs >= 0.0)
        val json = JSONObject()
            .put("state", profile.state)
            .put("hot_blocks", profile.hotBlocks)
            .put("cold_blocks", profile.coldBlocks)
            .put("execution_count", profile.executionCount)
            .put("cache_hits", profile.cacheHits)
            .put("cache_misses", profile.cacheMisses)
            .put("total_translation_ms", profile.totalTranslationMs)
            .put("last_seen_ms", profile.lastSeenMs)
            .put("compatibility_fingerprint", profile.compatibilityFingerprint)
        putManifest(key, json.toString(), allowGlobalLevel3)
    }

    @Synchronized
    fun getProfile(key: TranslationCacheKey, allowGlobalLevel3: Boolean = false): TranslationProfile? {
        val metadata = getManifest(key, allowGlobalLevel3) ?: return null
        return runCatching {
            val json = JSONObject(metadata)
            val profile = TranslationProfile(
                state = json.getString("state"),
                hotBlocks = json.getLong("hot_blocks"),
                coldBlocks = json.getLong("cold_blocks"),
                executionCount = json.getLong("execution_count"),
                cacheHits = json.optLong("cache_hits", 0L),
                cacheMisses = json.optLong("cache_misses", 0L),
                totalTranslationMs = json.optDouble("total_translation_ms", 0.0),
                lastSeenMs = json.getLong("last_seen_ms"),
                compatibilityFingerprint = json.getString("compatibility_fingerprint"),
            )
            require(profile.state in VALID_STATES)
            require(profile.compatibilityFingerprint == keyId(key))
            profile
        }.getOrNull()
    }

    @Synchronized
    fun invalidate(key: TranslationCacheKey) {
        val id = keyId(key)
        memoryL0.remove(id)
        processL1.remove(id)
        vmFile(key, id).delete()
        globalFile(id).delete()
    }

    @Synchronized
    fun invalidate(vmId: String, fingerprint: String) {
        val id = fingerprint(vmId)
        File(File(root, id), fingerprint + ".meta").delete()
        memoryL0.entries.removeAll { it.value.contains("\"key_fingerprint\":\"$fingerprint\"") }
        processL1.entries.removeAll { it.value.contains("\"key_fingerprint\":\"$fingerprint\"") }
    }

    @Synchronized
    fun invalidateAll(vmId: String) {
        val id = fingerprint(vmId)
        File(root, id).deleteRecursively()
        val ownerHash = fingerprint(vmId)
        val ownerMarker = "\"vm_id_hash\":\"" + ownerHash + "\""
        memoryL0.entries.removeAll { it.value.contains(ownerMarker) }
        processL1.entries.removeAll { it.value.contains(ownerMarker) }

        // L3 is global in location only: every entry remains owned by exactly one VM.
        // Removing a VM must therefore remove its opt-in global metadata as well.
        globalRoot.listFiles()?.forEach { file ->
            if (!file.isFile || file.length() !in 1..METADATA_MAX_BYTES.toLong()) {
                return@forEach
            }
            val entryOwner = runCatching {
                JSONObject(file.readText(Charsets.UTF_8)).optString("vm_id_hash")
            }.getOrNull()
            if (entryOwner == ownerHash) file.delete()
        }
    }

    @Synchronized
    fun cleanup(maxBytes: Long) {
        require(maxBytes >= 0L)
        var total = stats().bytes
        if (total <= maxBytes) return
        val files = root.walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }.toList()
        for (file in files) {
            if (total <= maxBytes) break
            val size = file.length()
            if (file.delete()) total -= size
        }
        memoryL0.clear()
        processL1.clear()
    }

    @Synchronized
    fun aggregate(vmId: String): TranslationAggregate {
        require(vmId.isNotBlank())
        val base = File(root, fingerprint(vmId))
        if (!base.isDirectory) {
            return TranslationAggregate(0L, 0L, 0L, 0.0)
        }

        var executions = 0L
        var hits = 0L
        var misses = 0L
        var translationMs = 0.0

        base.walkTopDown()
            .filter { it.isFile && it.length() in 1..METADATA_MAX_BYTES }
            .forEach { file ->
                runCatching {
                    val envelope = JSONObject(file.readText(Charsets.UTF_8))
                    if (envelope.optInt("format_version", -1) != FORMAT_VERSION) {
                        return@runCatching
                    }

                    // putProfile() stores its JSON payload inside the envelope's metadata string.
                    // Reading counters from the outer envelope silently produced all-zero aggregates.
                    val profile = JSONObject(envelope.getString("metadata"))
                    if (profile.optString("compatibility_fingerprint") !=
                        envelope.optString("key_fingerprint")
                    ) {
                        return@runCatching
                    }

                    val profileExecutions = profile.optLong("execution_count", -1L)
                    val profileHits = profile.optLong("cache_hits", -1L)
                    val profileMisses = profile.optLong("cache_misses", -1L)
                    if (profileExecutions < 0L || profileHits < 0L || profileMisses < 0L ||
                        profileHits > Long.MAX_VALUE - profileMisses ||
                        profileHits + profileMisses != profileExecutions
                    ) {
                        return@runCatching
                    }

                    val value = profile.optDouble("total_translation_ms", Double.NaN)
                    if (!value.isFinite() || value < 0.0) return@runCatching

                    executions = saturatingAdd(executions, profileExecutions)
                    hits = saturatingAdd(hits, profileHits)
                    misses = saturatingAdd(misses, profileMisses)
                    translationMs += value
                }
            }

        return TranslationAggregate(executions, hits, misses, translationMs)
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    @Synchronized
    fun stats(vmId: String? = null): TranslationCacheStats {
        val base = if (vmId == null) root else File(root, fingerprint(vmId))
        val files = if (!base.exists()) emptyList() else base.walkTopDown().filter { it.isFile }.toList()
        return TranslationCacheStats(
            files = files.size,
            bytes = files.sumOf { it.length() },
            hits = hitCount.get(),
            misses = missCount.get(),
        )
    }

    @Synchronized
    fun clear(vmId: String? = null) {
        if (vmId == null) {
            root.listFiles()?.forEach { it.deleteRecursively() }
            memoryL0.clear()
            processL1.clear()
        } else {
            invalidateAll(vmId)
        }
    }

    private fun keyId(key: TranslationCacheKey): String = fingerprint(
        key.vmId,
        key.guestFingerprint,
        key.translatorVersion,
        key.hostArch,
        key.hostFeatures,
        key.level.coerceIn(0, 3).toString(),
    )

    private fun vmFile(key: TranslationCacheKey, id: String): File {
        val directory = File(root, fingerprint(key.vmId)).apply { mkdirs() }
        return File(directory, id + ".meta")
    }

    private fun globalFile(id: String): File {
        globalRoot.mkdirs()
        return File(globalRoot, id + ".meta")
    }

    private fun readEnvelope(file: File, key: TranslationCacheKey, id: String): String? {
        if (!file.isFile || file.length() !in 1..METADATA_MAX_BYTES.toLong()) return null
        val value = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return value.takeIf { isValidEnvelope(it, key, id) }
    }

    private fun isValidEnvelope(value: String, key: TranslationCacheKey, id: String): Boolean =
        runCatching {
            val json = JSONObject(value)
            json.getInt("format_version") == FORMAT_VERSION &&
                json.getString("key_fingerprint") == id &&
                json.getString("vm_id_hash") == fingerprint(key.vmId) &&
                json.getString("guest_fingerprint") == key.guestFingerprint &&
                json.getString("translator_version") == key.translatorVersion &&
                json.getString("host_arch") == key.hostArch &&
                json.getString("host_features") == key.hostFeatures &&
                json.getInt("level") == key.level.coerceIn(0, 3) &&
                json.getString("metadata").toByteArray(Charsets.UTF_8).size <= METADATA_MAX_BYTES
        }.getOrDefault(false)

    private fun extractMetadata(value: String): String? =
        runCatching { JSONObject(value).getString("metadata") }.getOrNull()

    private fun writeAtomically(file: File, value: String) {
        file.parentFile?.let { check(it.mkdirs() || it.isDirectory) }
        val temporary = File(file.parentFile, file.name + ".tmp-" + Thread.currentThread().id)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(value.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            check(temporary.renameTo(file)) { "Could not atomically persist translation-cache metadata." }
        } finally {
            temporary.delete()
        }
    }

    private fun <K, V> trim(map: LinkedHashMap<K, V>, limit: Int) {
        while (map.size > limit) {
            val first = map.entries.iterator()
            if (!first.hasNext()) break
            first.next()
            first.remove()
        }
    }
}
