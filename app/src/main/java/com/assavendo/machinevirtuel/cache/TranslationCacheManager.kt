package com.assavendo.machinevirtuel.cache

import android.content.Context
import java.io.File
import java.security.MessageDigest

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

class TranslationCacheManager(context: Context) {
    private val root = File(context.filesDir, "translation-cache").apply { mkdirs() }

    fun fingerprint(vararg components: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(components.joinToString("\u0000").toByteArray()).joinToString("") {
            "%02x".format(it.toInt() and 255)
        }
    }

    fun key(
        vmId: String,
        guestBuild: String,
        translatorVersion: String,
        hostArch: String,
        hostFeatures: String,
        level: Int,
    ): TranslationCacheKey {
        return TranslationCacheKey(
            vmId = vmId,
            guestFingerprint = fingerprint(guestBuild),
            translatorVersion = translatorVersion,
            hostArch = hostArch,
            hostFeatures = hostFeatures,
            level = level.coerceIn(0, 3),
        )
    }

    fun putManifest(vmId: String, fingerprint: String, metadata: String) {
        val vmRoot = File(root, vmId).apply { mkdirs() }
        File(vmRoot, fingerprint + ".meta").writeText(metadata)
    }

    fun putManifest(key: TranslationCacheKey, metadata: String) {
        val fp = fingerprint(
            key.vmId,
            key.guestFingerprint,
            key.translatorVersion,
            key.hostArch,
            key.hostFeatures,
            key.level.toString(),
        )
        putManifest(key.vmId, fp, metadata)
    }

    fun has(vmId: String, fingerprint: String): Boolean =
        File(root, vmId).resolve(fingerprint + ".meta").isFile

    fun invalidate(vmId: String, fingerprint: String) {
        File(root, vmId).resolve(fingerprint + ".meta").delete()
    }

    fun invalidateAll(vmId: String) {
        File(root, vmId).deleteRecursively()
    }

    fun cleanup(maxBytes: Long) {
        if (maxBytes <= 0) return
        var total = stats().bytes
        if (total <= maxBytes) return
        val files = root.walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }.toList()
        for (file in files) {
            if (total <= maxBytes) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    fun stats(vmId: String? = null): TranslationCacheStats {
        val base = if (vmId == null) root else File(root, vmId)
        if (!base.exists()) return TranslationCacheStats(0, 0)
        val files = base.walkTopDown().filter { it.isFile }.toList()
        return TranslationCacheStats(files.size, files.sumOf { it.length() })
    }

    fun clear(vmId: String? = null) {
        if (vmId == null) root.listFiles()?.forEach { it.deleteRecursively() }
        else File(root, vmId).deleteRecursively()
    }
}
