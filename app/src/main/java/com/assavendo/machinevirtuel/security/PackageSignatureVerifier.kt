package com.assavendo.machinevirtuel.security

import java.io.File
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
        return verify(file.readBytes(), signature.readBytes(), publicKey.readBytes())
    }
}
