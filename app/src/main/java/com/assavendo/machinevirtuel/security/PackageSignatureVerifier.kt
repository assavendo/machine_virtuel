package com.assavendo.machinevirtuel.security

import java.io.File
import java.io.FileInputStream
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

object PackageSignatureVerifier {
    fun verify(data: ByteArray, signatureBytes: ByteArray, publicKeyBytes: ByteArray): Boolean {
        return runCatching {
            val factory = KeyFactory.getInstance("Ed25519")
            val key: PublicKey = factory.generatePublic(X509EncodedKeySpec(publicKeyBytes))
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(data)
                verify(signatureBytes)
            }
        }.getOrDefault(false)
    }

    fun verifyFile(file: File, signature: File, publicKey: File): Boolean {
        if (!file.isFile || !signature.isFile || !publicKey.isFile) return false
        if (signature.length() !in 1L..4096L || publicKey.length() !in 1L..4096L) return false
        return runCatching {
            val signatureBytes = signature.readBytes()
            val keyBytes = publicKey.readBytes()
            val factory = KeyFactory.getInstance("Ed25519")
            val key: PublicKey = factory.generatePublic(X509EncodedKeySpec(keyBytes))
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(key)
            FileInputStream(file).buffered(128 * 1024).use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) verifier.update(buffer, 0, count)
                }
            }
            verifier.verify(signatureBytes)
        }.getOrDefault(false)
    }
}
