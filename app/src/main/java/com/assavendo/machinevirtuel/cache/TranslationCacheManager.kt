package com.assavendo.machinevirtuel.cache

import android.content.Context
import android.content.SharedPreferences
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * Cache for translator-produced artifacts and their profile metadata.
 *
 * QEMU TCG owns its live executable Translation Blocks and invalidation. PhoenixVM
 * only persists an artifact when a translator explicitly supplies a relocation-safe
 * payload; this API does not snapshot opaque QEMU TCG host pointers or TB internals.
 */
data class TranslationCachePolicy(
    val l0MaxEntries: Int = 64,
    val l1MaxEntries: Int = 512,
    val l0MaxBytes: Long = 32L * 1024 * 1024,
    val l1MaxBytes: Long = 128L * 1024 * 1024,
    val maxPersistentBytes: Long = 256L * 1024 * 1024,
    val maxArtifactBytes: Int = 16 * 1024 * 1024,
    val cleanupIntervalWrites: Int = 8,
    val globalLevel3Enabled: Boolean = false,
) {
    init {
        require(l0MaxEntries in 1..4096)
        require(l1MaxEntries in l0MaxEntries..16_384)
        require(l0MaxBytes in 1L..(1024L * 1024 * 1024))
        require(l1MaxBytes in l0MaxBytes..(2L * 1024 * 1024 * 1024))
        require(maxPersistentBytes in 1L..(16L * 1024 * 1024 * 1024))
        require(maxArtifactBytes in 1..(64 * 1024 * 1024))
        require(cleanupIntervalWrites in 1..1024)
    }
}

data class TranslationCacheStats(
    val files: Int,
    val bytes: Long,
    val hits: Long = 0,
    val misses: Long = 0,
    val l0Hits: Long = 0,
    val l1Hits: Long = 0,
    val l2Hits: Long = 0,
    val l3Hits: Long = 0,
    val maxPersistentBytes: Long = 0,
)

data class TranslationCacheKey(
    val vmId: String,
    val codeFingerprint: String,
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
        get() = if (cacheHits + cacheMisses > 0L) {
            cacheHits.toDouble() / (cacheHits + cacheMisses).toDouble()
        } else null

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

class TranslationCacheManager private constructor(
    private val root: File,
    initialPolicy: TranslationCachePolicy,
    private val preferences: SharedPreferences?,
) {
    companion object {
        private const val FORMAT_VERSION = 3
        private const val METADATA_MAX_BYTES = 1_048_576
        private const val ARTIFACT_HEADER_MAX_BYTES = 64 * 1024
        private const val ARTIFACT_MAGIC = 0x50564d54 // "PVMT"
        private const val PREFS_NAME = "phoenixvm_translation_cache"
        private val VALID_STATES = setOf("COLD", "WARM", "HOT", "VERY_HOT")
        @Volatile
        private var applicationInstance: TranslationCacheManager? = null

        /** One process-wide cache manager shared by activities, services and VM monitors. */
        fun get(context: Context): TranslationCacheManager = synchronized(this) {
            applicationInstance ?: TranslationCacheManager(context.applicationContext).also {
                applicationInstance = it
            }
        }

        internal fun forTesting(
            cacheRoot: File,
            policy: TranslationCachePolicy = TranslationCachePolicy(),
        ): TranslationCacheManager = TranslationCacheManager(cacheRoot, policy, null)

        private fun readPolicy(preferences: SharedPreferences): TranslationCachePolicy {
            val defaults = TranslationCachePolicy()
            return runCatching {
                TranslationCachePolicy(
                    l0MaxEntries = preferences.getInt("l0MaxEntries", defaults.l0MaxEntries),
                    l1MaxEntries = preferences.getInt("l1MaxEntries", defaults.l1MaxEntries),
                    l0MaxBytes = preferences.getLong("l0MaxBytes", defaults.l0MaxBytes),
                    l1MaxBytes = preferences.getLong("l1MaxBytes", defaults.l1MaxBytes),
                    maxPersistentBytes = preferences.getLong(
                        "maxPersistentBytes",
                        defaults.maxPersistentBytes,
                    ),
                    maxArtifactBytes = preferences.getInt("maxArtifactBytes", defaults.maxArtifactBytes),
                    cleanupIntervalWrites = preferences.getInt(
                        "cleanupIntervalWrites",
                        defaults.cleanupIntervalWrites,
                    ),
                    globalLevel3Enabled = preferences.getBoolean(
                        "globalLevel3Enabled",
                        defaults.globalLevel3Enabled,
                    ),
                )
            }.getOrDefault(defaults)
        }
    }

    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, "translation-cache"),
        readPolicy(
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ),
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    @Volatile
    private var policy: TranslationCachePolicy = initialPolicy

    private val globalRoot = File(root, "global")
    private val memoryL0 = object : LinkedHashMap<String, String>(policy.l0MaxEntries, 0.75f, true) {}
    private val processL1 = object : LinkedHashMap<String, String>(policy.l1MaxEntries, 0.75f, true) {}
    private val memoryArtifactsL0 = object : LinkedHashMap<String, ByteArray>(policy.l0MaxEntries, 0.75f, true) {}
    private val processArtifactsL1 = object : LinkedHashMap<String, ByteArray>(policy.l1MaxEntries, 0.75f, true) {}
    private val localOwnerHashes = HashMap<String, String>()
    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)
    private val l0HitCount = AtomicLong(0)
    private val l1HitCount = AtomicLong(0)
    private val l2HitCount = AtomicLong(0)
    private val l3HitCount = AtomicLong(0)
    private var writesSinceCleanup = 0

    init {
        check(root.mkdirs() || root.isDirectory) { "Unable to create translation cache root." }
    }

    fun currentPolicy(): TranslationCachePolicy = policy

    @Synchronized
    fun configure(newPolicy: TranslationCachePolicy) {
        policy = newPolicy
        preferences?.edit()
            ?.putInt("l0MaxEntries", newPolicy.l0MaxEntries)
            ?.putInt("l1MaxEntries", newPolicy.l1MaxEntries)
            ?.putLong("l0MaxBytes", newPolicy.l0MaxBytes)
            ?.putLong("l1MaxBytes", newPolicy.l1MaxBytes)
            ?.putLong("maxPersistentBytes", newPolicy.maxPersistentBytes)
            ?.putInt("maxArtifactBytes", newPolicy.maxArtifactBytes)
            ?.putInt("cleanupIntervalWrites", newPolicy.cleanupIntervalWrites)
            ?.putBoolean("globalLevel3Enabled", newPolicy.globalLevel3Enabled)
            ?.apply()
        trimMetadata(memoryL0, newPolicy.l0MaxEntries, newPolicy.l0MaxBytes)
        trimMetadata(processL1, newPolicy.l1MaxEntries, newPolicy.l1MaxBytes)
        trimArtifacts(memoryArtifactsL0, newPolicy.l0MaxEntries, newPolicy.l0MaxBytes)
        trimArtifacts(processArtifactsL1, newPolicy.l1MaxEntries, newPolicy.l1MaxBytes)
        refreshLocalOwners()
        cleanup(newPolicy.maxPersistentBytes)
    }

    /** A length-delimited SHA-256 over textual compatibility components. */
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

    /** Raw SHA-256 over the exact guest code bytes supplied by a translator. */
    fun fingerprintCode(code: ByteArray): String {
        require(code.isNotEmpty()) { "Translated code fingerprint input cannot be empty." }
        return MessageDigest.getInstance("SHA-256")
            .digest(code)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun key(
        vmId: String,
        code: ByteArray,
        guestBuild: String,
        translatorVersion: String,
        hostArch: String,
        hostFeatures: String,
        level: Int,
    ): TranslationCacheKey {
        require(vmId.isNotBlank())
        require(guestBuild.isNotBlank())
        require(translatorVersion.isNotBlank() && hostArch.isNotBlank())
        require(level in 0..3)
        return TranslationCacheKey(
            vmId = vmId,
            codeFingerprint = fingerprintCode(code),
            guestFingerprint = fingerprint(guestBuild),
            translatorVersion = translatorVersion,
            hostArch = hostArch,
            hostFeatures = hostFeatures,
            level = level,
        )
    }

    @Synchronized
    fun putManifest(
        key: TranslationCacheKey,
        metadata: String,
        allowGlobalLevel3: Boolean = policy.globalLevel3Enabled,
    ) {
        validateKey(key)
        require(metadata.toByteArray(Charsets.UTF_8).size <= METADATA_MAX_BYTES) {
            "Translation profile metadata exceeds the configured limit."
        }
        val localId = keyId(key)
        val vmEnvelope = buildMetadataEnvelope(key, localId, "VM", metadata)
        writeAtomically(vmFile(key, localId), vmEnvelope)

        if (allowGlobalLevel3 && key.level == 3) {
            val globalId = globalKeyId(key)
            writeAtomically(globalFile(globalId), buildMetadataEnvelope(key, globalId, "GLOBAL", metadata))
        }
        cacheMetadataInMemory(localId, key.vmId, vmEnvelope)
        afterPersistentWrite()
    }

    /** Persist a relocation-safe artifact explicitly supplied by the translation engine. */
    @Synchronized
    fun putArtifact(
        key: TranslationCacheKey,
        artifact: ByteArray,
        allowGlobalLevel3: Boolean = policy.globalLevel3Enabled,
    ) {
        validateKey(key)
        require(artifact.isNotEmpty() && artifact.size <= policy.maxArtifactBytes) {
            "Translation artifact is empty or exceeds the configured per-artifact limit."
        }
        val localId = keyId(key)
        val vmBytes = encodeArtifact(key, localId, "VM", artifact)
        writeAtomically(vmArtifactFile(key, localId), vmBytes)

        if (allowGlobalLevel3 && key.level == 3) {
            val globalId = globalKeyId(key)
            writeAtomically(globalArtifactFile(globalId), encodeArtifact(key, globalId, "GLOBAL", artifact))
        }
        cacheArtifactInMemory(localId, key.vmId, artifact)
        afterPersistentWrite()
    }

    @Synchronized
    fun getArtifact(
        key: TranslationCacheKey,
        allowGlobalLevel3: Boolean = policy.globalLevel3Enabled,
    ): ByteArray? {
        validateKey(key)
        val localId = keyId(key)
        memoryArtifactsL0[localId]?.let {
            hitCount.incrementAndGet()
            l0HitCount.incrementAndGet()
            return it.copyOf()
        }
        processArtifactsL1[localId]?.let {
            cacheArtifactInMemory(localId, key.vmId, it)
            hitCount.incrementAndGet()
            l1HitCount.incrementAndGet()
            return it.copyOf()
        }

        val local = readArtifact(vmArtifactFile(key, localId), key, localId, "VM")
        if (local != null) {
            cacheArtifactInMemory(localId, key.vmId, local)
            touch(vmArtifactFile(key, localId))
            hitCount.incrementAndGet()
            l2HitCount.incrementAndGet()
            return local.copyOf()
        }

        if (allowGlobalLevel3 && key.level == 3) {
            val globalId = globalKeyId(key)
            val shared = readArtifact(globalArtifactFile(globalId), key, globalId, "GLOBAL")
            if (shared != null) {
                cacheArtifactInMemory(localId, key.vmId, shared)
                writeAtomically(vmArtifactFile(key, localId), encodeArtifact(key, localId, "VM", shared))
                touch(globalArtifactFile(globalId))
                afterPersistentWrite()
                hitCount.incrementAndGet()
                l3HitCount.incrementAndGet()
                return shared.copyOf()
            }
        }

        missCount.incrementAndGet()
        return null
    }

    @Synchronized
    fun getManifest(
        key: TranslationCacheKey,
        allowGlobalLevel3: Boolean = policy.globalLevel3Enabled,
    ): String? {
        validateKey(key)
        val localId = keyId(key)
        memoryL0[localId]?.let {
            if (isValidMetadataEnvelope(it, key, localId, "VM")) {
                hitCount.incrementAndGet()
                l0HitCount.incrementAndGet()
                return extractMetadata(it)
            }
            memoryL0.remove(localId)
        }
        processL1[localId]?.let {
            if (isValidMetadataEnvelope(it, key, localId, "VM")) {
                memoryL0[localId] = it
                trimMetadata(memoryL0, policy.l0MaxEntries, policy.l0MaxBytes)
                hitCount.incrementAndGet()
                l1HitCount.incrementAndGet()
                return extractMetadata(it)
            }
            processL1.remove(localId)
        }

        val localFile = vmFile(key, localId)
        val local = readMetadataEnvelope(localFile, key, localId, "VM")
        if (local != null) {
            cacheMetadataInMemory(localId, key.vmId, local)
            touch(localFile)
            hitCount.incrementAndGet()
            l2HitCount.incrementAndGet()
            return extractMetadata(local)
        }

        if (allowGlobalLevel3 && key.level == 3) {
            val globalId = globalKeyId(key)
            val globalFile = globalFile(globalId)
            val shared = readMetadataEnvelope(globalFile, key, globalId, "GLOBAL")
            if (shared != null) {
                val localEnvelope = buildMetadataEnvelope(
                    key,
                    localId,
                    "VM",
                    extractMetadata(shared) ?: return null,
                )
                cacheMetadataInMemory(localId, key.vmId, localEnvelope)
                writeAtomically(localFile, localEnvelope)
                touch(globalFile)
                afterPersistentWrite()
                hitCount.incrementAndGet()
                l3HitCount.incrementAndGet()
                return extractMetadata(localEnvelope)
            }
        }

        missCount.incrementAndGet()
        return null
    }

    @Synchronized
    fun has(key: TranslationCacheKey, allowGlobalLevel3: Boolean = policy.globalLevel3Enabled): Boolean {
        validateKey(key)
        val localId = keyId(key)
        if (memoryL0[localId]?.let { isValidMetadataEnvelope(it, key, localId, "VM") } == true) return true
        if (processL1[localId]?.let { isValidMetadataEnvelope(it, key, localId, "VM") } == true) return true
        if (readMetadataEnvelope(vmFile(key, localId), key, localId, "VM") != null) return true
        if (memoryArtifactsL0.containsKey(localId) || processArtifactsL1.containsKey(localId)) return true
        if (readArtifact(vmArtifactFile(key, localId), key, localId, "VM") != null) return true
        if (allowGlobalLevel3 && key.level == 3) {
            val globalId = globalKeyId(key)
            if (readMetadataEnvelope(globalFile(globalId), key, globalId, "GLOBAL") != null) return true
            if (readArtifact(globalArtifactFile(globalId), key, globalId, "GLOBAL") != null) return true
        }
        return false
    }

    /** Legacy existence check used by early builds. */
    fun has(vmId: String, fingerprint: String): Boolean {
        if (vmId.isBlank() || !fingerprint.matches(Regex("[A-Fa-f0-9]{16,128}"))) return false
        val vm = File(root, this.fingerprint(vmId))
        return File(vm, fingerprint + ".meta").isFile
    }

    @Synchronized
    fun putProfile(
        key: TranslationCacheKey,
        profile: TranslationProfile,
        @Suppress("UNUSED_PARAMETER") allowGlobalLevel3: Boolean = false,
    ) {
        validateKey(key)
        require(profile.state in VALID_STATES) { "Invalid translation profile state." }
        require(profile.hotBlocks >= 0 && profile.coldBlocks >= 0 && profile.executionCount >= 0)
        require(profile.compatibilityFingerprint == keyId(key)) {
            "Translation profile fingerprint does not match the cache key."
        }
        require(profile.cacheHits >= 0 && profile.cacheMisses >= 0)
        require(profile.cacheHits + profile.cacheMisses == profile.executionCount) {
            "Translation cache hit/miss counts must equal execution count."
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
        // Profile counters belong to one VM. They must never be inherited from L3.
        putManifest(key, json.toString(), allowGlobalLevel3 = false)
    }

    @Synchronized
    fun getProfile(
        key: TranslationCacheKey,
        @Suppress("UNUSED_PARAMETER") allowGlobalLevel3: Boolean = false,
    ): TranslationProfile? {
        // Profiles are VM-local; only code artifacts and neutral manifests can use L3.
        val metadata = getManifest(key, allowGlobalLevel3 = false) ?: return null
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

    /** Invalidates only the selected VM's entry. Shared L3 remains available to peers. */
    @Synchronized
    fun invalidate(key: TranslationCacheKey) {
        val id = keyId(key)
        memoryL0.remove(id)
        processL1.remove(id)
        memoryArtifactsL0.remove(id)
        processArtifactsL1.remove(id)
        localOwnerHashes.remove(id)
        vmFile(key, id).delete()
        vmArtifactFile(key, id).delete()
    }

    @Synchronized
    fun invalidateGlobal(key: TranslationCacheKey) {
        if (key.level != 3) return
        val id = globalKeyId(key)
        globalFile(id).delete()
        globalArtifactFile(id).delete()
    }

    /** Removes the VM-scoped L2 and local indexes without deleting shared global L3 data. */
    @Synchronized
    fun invalidateAll(vmId: String) {
        require(vmId.isNotBlank())
        val ownerHash = fingerprint(vmId)
        File(root, ownerHash).deleteRecursively()
        val localIds = localOwnerHashes.filterValues { it == ownerHash }.keys.toList()
        localIds.forEach { id ->
            memoryL0.remove(id)
            processL1.remove(id)
            memoryArtifactsL0.remove(id)
            processArtifactsL1.remove(id)
            localOwnerHashes.remove(id)
        }
    }

    @Synchronized
    fun cleanup(maxBytes: Long = policy.maxPersistentBytes) {
        require(maxBytes >= 0L)
        val files = root.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= maxBytes) return@forEach
            val size = file.length()
            if (file.delete()) total -= size
        }
        memoryL0.clear()
        processL1.clear()
        memoryArtifactsL0.clear()
        processArtifactsL1.clear()
        localOwnerHashes.clear()
    }

    @Synchronized
    fun aggregate(vmId: String): TranslationAggregate {
        require(vmId.isNotBlank())
        val base = File(root, fingerprint(vmId))
        if (!base.isDirectory) return TranslationAggregate(0L, 0L, 0L, 0.0)

        var executions = 0L
        var hits = 0L
        var misses = 0L
        var translationMs = 0.0
        base.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".meta") && it.length() in 1..METADATA_MAX_BYTES }
            .forEach { file ->
                runCatching {
                    val json = JSONObject(file.readText(Charsets.UTF_8))
                    if (json.optInt("format_version", -1) != FORMAT_VERSION ||
                        json.optString("scope") != "VM"
                    ) return@runCatching
                    executions = safeAdd(executions, json.optLong("execution_count", 0L).coerceAtLeast(0L))
                    hits = safeAdd(hits, json.optLong("cache_hits", 0L).coerceAtLeast(0L))
                    misses = safeAdd(misses, json.optLong("cache_misses", 0L).coerceAtLeast(0L))
                    val value = json.optDouble("total_translation_ms", 0.0)
                    if (value.isFinite() && value >= 0.0) translationMs += value
                }
            }
        return TranslationAggregate(executions, hits, misses, translationMs)
    }

    @Synchronized
    fun stats(vmId: String? = null): TranslationCacheStats {
        val base = if (vmId == null) root else File(root, fingerprint(vmId))
        val files = if (!base.exists()) emptyList() else base.walkTopDown().filter { it.isFile }.toList()
        return TranslationCacheStats(
            files = files.size,
            bytes = files.sumOf { it.length() },
            hits = hitCount.get(),
            misses = missCount.get(),
            l0Hits = l0HitCount.get(),
            l1Hits = l1HitCount.get(),
            l2Hits = l2HitCount.get(),
            l3Hits = l3HitCount.get(),
            maxPersistentBytes = policy.maxPersistentBytes,
        )
    }

    @Synchronized
    fun clear(vmId: String? = null) {
        if (vmId == null) {
            root.listFiles()?.forEach { it.deleteRecursively() }
            memoryL0.clear()
            processL1.clear()
            memoryArtifactsL0.clear()
            processArtifactsL1.clear()
            localOwnerHashes.clear()
        } else {
            invalidateAll(vmId)
        }
    }

    private fun validateKey(key: TranslationCacheKey) {
        require(key.vmId.isNotBlank())
        require(key.codeFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(key.guestFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(key.translatorVersion.isNotBlank() && key.hostArch.isNotBlank())
        require(key.level in 0..3)
    }

    /** Canonical VM-local profile identity; shared by the cache and translation engine. */
    fun compatibilityFingerprint(key: TranslationCacheKey): String {
        validateKey(key)
        return keyId(key)
    }

    private fun keyId(key: TranslationCacheKey): String = fingerprint(
        "VM",
        key.vmId,
        key.codeFingerprint,
        key.guestFingerprint,
        key.translatorVersion,
        key.hostArch,
        key.hostFeatures,
        key.level.toString(),
    )

    /** Compatibility identity deliberately excludes vmId so compatible VMs share L3. */
    private fun globalKeyId(key: TranslationCacheKey): String = fingerprint(
        "GLOBAL",
        key.codeFingerprint,
        key.guestFingerprint,
        key.translatorVersion,
        key.hostArch,
        key.hostFeatures,
        key.level.toString(),
    )

    private fun buildMetadataEnvelope(
        key: TranslationCacheKey,
        id: String,
        scope: String,
        metadata: String,
    ): String {
        val json = JSONObject()
            .put("format_version", FORMAT_VERSION)
            .put("scope", scope)
            .put("key_fingerprint", id)
            .put("global_key_fingerprint", globalKeyId(key))
            .put("code_fingerprint", key.codeFingerprint)
            .put("guest_fingerprint", key.guestFingerprint)
            .put("translator_version", key.translatorVersion)
            .put("host_arch", key.hostArch)
            .put("host_features", key.hostFeatures)
            .put("level", key.level)
            .put("updated_at", System.currentTimeMillis())
            .put("metadata", metadata)
        if (scope == "VM") json.put("vm_id_hash", fingerprint(key.vmId))
        return json.toString()
    }

    private fun isValidMetadataEnvelope(
        value: String,
        key: TranslationCacheKey,
        id: String,
        scope: String,
    ): Boolean = runCatching {
        val json = JSONObject(value)
        json.getInt("format_version") == FORMAT_VERSION &&
            json.getString("scope") == scope &&
            json.getString("key_fingerprint") == id &&
            json.getString("global_key_fingerprint") == globalKeyId(key) &&
            json.getString("code_fingerprint") == key.codeFingerprint &&
            json.getString("guest_fingerprint") == key.guestFingerprint &&
            json.getString("translator_version") == key.translatorVersion &&
            json.getString("host_arch") == key.hostArch &&
            json.getString("host_features") == key.hostFeatures &&
            json.getInt("level") == key.level &&
            (scope == "GLOBAL" || json.getString("vm_id_hash") == fingerprint(key.vmId)) &&
            json.getString("metadata").toByteArray(Charsets.UTF_8).size <= METADATA_MAX_BYTES
    }.getOrDefault(false)

    private fun readMetadataEnvelope(file: File, key: TranslationCacheKey, id: String, scope: String): String? {
        if (!file.isFile || file.length() !in 1..METADATA_MAX_BYTES.toLong()) return null
        val value = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return value.takeIf { isValidMetadataEnvelope(it, key, id, scope) }
    }

    private fun extractMetadata(value: String): String? =
        runCatching { JSONObject(value).getString("metadata") }.getOrNull()

    private fun encodeArtifact(key: TranslationCacheKey, id: String, scope: String, payload: ByteArray): ByteArray {
        val header = JSONObject()
            .put("format_version", FORMAT_VERSION)
            .put("scope", scope)
            .put("key_fingerprint", id)
            .put("global_key_fingerprint", globalKeyId(key))
            .put("code_fingerprint", key.codeFingerprint)
            .put("guest_fingerprint", key.guestFingerprint)
            .put("translator_version", key.translatorVersion)
            .put("host_arch", key.hostArch)
            .put("host_features", key.hostFeatures)
            .put("level", key.level)
            .put("artifact_sha256", fingerprintCode(payload))
            .put("artifact_bytes", payload.size)
        if (scope == "VM") header.put("vm_id_hash", fingerprint(key.vmId))
        val headerBytes = header.toString().toByteArray(Charsets.UTF_8)
        require(headerBytes.size <= ARTIFACT_HEADER_MAX_BYTES)
        return ByteArrayOutputStream(headerBytes.size + payload.size + 12).use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(ARTIFACT_MAGIC)
                output.writeInt(headerBytes.size)
                output.write(headerBytes)
                output.writeInt(payload.size)
                output.write(payload)
            }
            buffer.toByteArray()
        }
    }

    private fun readArtifact(file: File, key: TranslationCacheKey, id: String, scope: String): ByteArray? {
        val maxBytes = policy.maxArtifactBytes.toLong() + ARTIFACT_HEADER_MAX_BYTES + 12L
        if (!file.isFile || file.length() !in 12L..maxBytes) return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                require(input.readInt() == ARTIFACT_MAGIC)
                val headerLength = input.readInt()
                require(headerLength in 1..ARTIFACT_HEADER_MAX_BYTES)
                val headerBytes = ByteArray(headerLength)
                input.readFully(headerBytes)
                val header = JSONObject(headerBytes.toString(Charsets.UTF_8))
                val payloadLength = input.readInt()
                require(payloadLength in 1..policy.maxArtifactBytes)
                require(header.getInt("artifact_bytes") == payloadLength)
                require(header.getInt("format_version") == FORMAT_VERSION)
                require(header.getString("scope") == scope)
                require(header.getString("key_fingerprint") == id)
                require(header.getString("global_key_fingerprint") == globalKeyId(key))
                require(header.getString("code_fingerprint") == key.codeFingerprint)
                require(header.getString("guest_fingerprint") == key.guestFingerprint)
                require(header.getString("translator_version") == key.translatorVersion)
                require(header.getString("host_arch") == key.hostArch)
                require(header.getString("host_features") == key.hostFeatures)
                require(header.getInt("level") == key.level)
                if (scope == "VM") require(header.getString("vm_id_hash") == fingerprint(key.vmId))
                val payload = ByteArray(payloadLength)
                input.readFully(payload)
                require(input.read() == -1)
                require(header.getString("artifact_sha256") == fingerprintCode(payload))
                payload
            }
        }.getOrNull()
    }

    private fun cacheMetadataInMemory(id: String, vmId: String, envelope: String) {
        localOwnerHashes[id] = fingerprint(vmId)
        memoryL0[id] = envelope
        processL1[id] = envelope
        trimMetadata(memoryL0, policy.l0MaxEntries, policy.l0MaxBytes)
        trimMetadata(processL1, policy.l1MaxEntries, policy.l1MaxBytes)
        refreshLocalOwners()
    }

    private fun cacheArtifactInMemory(id: String, vmId: String, payload: ByteArray) {
        localOwnerHashes[id] = fingerprint(vmId)
        if (payload.size <= policy.l0MaxBytes) {
            memoryArtifactsL0[id] = payload.copyOf()
            trimArtifacts(memoryArtifactsL0, policy.l0MaxEntries, policy.l0MaxBytes)
        } else {
            memoryArtifactsL0.remove(id)
        }
        if (payload.size <= policy.l1MaxBytes) {
            processArtifactsL1[id] = payload.copyOf()
            trimArtifacts(processArtifactsL1, policy.l1MaxEntries, policy.l1MaxBytes)
        } else {
            processArtifactsL1.remove(id)
        }
        refreshLocalOwners()
    }

    private fun trimMetadata(
        map: LinkedHashMap<String, String>,
        maxEntries: Int,
        maxBytes: Long,
    ) {
        fun byteCount(): Long = map.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
        while (map.size > maxEntries || byteCount() > maxBytes) {
            val iterator = map.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
    }

    /** Keep the invalidation index bounded by entries actually resident in memory. */
    private fun refreshLocalOwners() {
        val resident = memoryL0.keys + processL1.keys +
            memoryArtifactsL0.keys + processArtifactsL1.keys
        localOwnerHashes.keys.retainAll(resident.toSet())
    }

    private fun trimArtifacts(map: LinkedHashMap<String, ByteArray>, maxEntries: Int, maxBytes: Long) {
        while (map.size > maxEntries || map.values.sumOf { it.size.toLong() } > maxBytes) {
            val iterator = map.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
    }

    private fun vmDirectory(key: TranslationCacheKey): File =
        File(root, fingerprint(key.vmId)).apply { mkdirs() }

    private fun vmFile(key: TranslationCacheKey, id: String): File =
        File(vmDirectory(key), id + ".meta")

    private fun vmArtifactFile(key: TranslationCacheKey, id: String): File =
        File(vmDirectory(key), id + ".artifact")

    private fun globalFile(id: String): File {
        globalRoot.mkdirs()
        return File(globalRoot, id + ".meta")
    }

    private fun globalArtifactFile(id: String): File {
        globalRoot.mkdirs()
        return File(globalRoot, id + ".artifact")
    }

    private fun writeAtomically(file: File, value: String) =
        writeAtomically(file, value.toByteArray(Charsets.UTF_8))

    private fun writeAtomically(file: File, bytes: ByteArray) {
        file.parentFile?.let { check(it.mkdirs() || it.isDirectory) }
        val temporary = File(file.parentFile, file.name + ".tmp-" + Thread.currentThread().id)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            check(temporary.renameTo(file)) { "Could not atomically persist translation-cache data." }
        } finally {
            temporary.delete()
        }
    }

    private fun touch(file: File) {
        runCatching { file.setLastModified(System.currentTimeMillis()) }
    }

    private fun afterPersistentWrite() {
        writesSinceCleanup++
        if (writesSinceCleanup >= policy.cleanupIntervalWrites || stats().bytes > policy.maxPersistentBytes) {
            writesSinceCleanup = 0
            cleanup(policy.maxPersistentBytes)
        }
    }

    private fun safeAdd(a: Long, b: Long): Long =
        if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
}
