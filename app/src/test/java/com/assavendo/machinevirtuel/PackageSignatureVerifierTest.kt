package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.security.PackageSignatureVerifier
import java.io.File
import java.security.KeyPairGenerator
import java.security.Security
import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.bouncycastle.jce.provider.BouncyCastleProvider

class PackageSignatureVerifierTest {
    @Test fun verifiesEd25519PackagesAndRejectsTampering() {
        if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
        val generator = KeyPairGenerator.getInstance("Ed25519", "BC")
        val pair = generator.generateKeyPair()
        val packageFile = File.createTempFile("phoenixvm-package", ".zip")
        val signatureFile = File.createTempFile("phoenixvm-package", ".sig")
        val publicKeyFile = File.createTempFile("phoenixvm-package", ".pub")
        try {
            packageFile.writeBytes(ByteArray(256 * 1024) { (it % 251).toByte() })
            val signer = Signature.getInstance("Ed25519", "BC")
            signer.initSign(pair.private)
            packageFile.inputStream().buffered().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) signer.update(buffer, 0, count)
                }
            }
            signatureFile.writeBytes(signer.sign())
            publicKeyFile.writeBytes(pair.public.encoded)
            assertTrue(PackageSignatureVerifier.verifyFile(packageFile, signatureFile, publicKeyFile))

            packageFile.appendBytes(byteArrayOf(1, 2, 3))
            assertFalse(PackageSignatureVerifier.verifyFile(packageFile, signatureFile, publicKeyFile))
        } finally {
            packageFile.delete()
            signatureFile.delete()
            publicKeyFile.delete()
        }
    }

    @Test fun rejectsOversizedSignatureMaterial() {
        assertFalse(PackageSignatureVerifier.verify(ByteArray(1), ByteArray(4097), ByteArray(1)))
    }
}
