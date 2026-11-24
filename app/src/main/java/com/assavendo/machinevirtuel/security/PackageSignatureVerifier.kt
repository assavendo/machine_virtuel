package com.assavendo.machinevirtuel.security

import java.io.File
import java.io.FileInputStream
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Security
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import org.bouncycastle.jce.provider.BouncyCastleProvider

object PackageSignatureVerifier {
    private const val PROVIDER = "BC"

    private fun ensureProvider() {
        if (Security.getProvider(PROVIDER) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun verify(data: ByteArray, signatureBytes: ByteArray, publicKeyBytes: ByteArray): Boolean {
        if (signatureBytes.size !in 1..4096 || publicKeyBytes.size !in 1..4096) return false
        return runCatching {
            ensureProvider()
            val factory = KeyFactory.getInstance("Ed25519", PROVIDER)
            val key: PublicKey = factory.generatePublic(X509EncodedKeySpec(publicKeyBytes))
            Signature.getInstance("Ed25519", PROVIDER).run {
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
            ensureProvider()
            val signatureBytes = signature.readBytes()
            val keyBytes = publicKey.readBytes()
            val factory = KeyFactory.getInstance("Ed25519", PROVIDER)
            val key: PublicKey = factory.generatePublic(X509EncodedKeySpec(keyBytes))
            val verifier = Signature.getInstance("Ed25519", PROVIDER)
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
