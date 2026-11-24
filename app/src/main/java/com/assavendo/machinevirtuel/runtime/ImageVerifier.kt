package com.assavendo.machinevirtuel.runtime

import java.io.File
import java.security.MessageDigest

data class ImageMetadata(
    val source: String,
    val version: String,
    val architecture: String,
    val sha256: String,
    val sizeBytes: Long,
    val downloadedAt: Long = System.currentTimeMillis(),
    val verified: Boolean,
    val license: String = "unknown",
)

object ImageVerifier {
    fun sha256(file: File): String {
        require(file.isFile) { "Fichier image introuvable: ${file.absolutePath}" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(128 * 1024).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun verify(file: File, expectedSha256: String): Boolean =
        sha256(file).equals(expectedSha256.trim(), ignoreCase = true)
}
