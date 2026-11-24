package com.assavendo.machinevirtuel.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

object PackageSignatureManager {
    private const val PROVIDER = "BC"
    private const val KEY_ALIAS = "phoenixvm-package-signing-aes-v1"
    private const val DIRECTORY = "package-signing"
    private const val PRIVATE_FILE = "ed25519-private.enc"
    private const val PUBLIC_FILE = "ed25519-public.der"
    private const val MAGIC = 0x50564D53
    private const val VERSION = 1

    private fun ensureProvider() {
        if (Security.getProvider(PROVIDER) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun signFile(context: Context, file: File, signatureFile: File, publicKeyFile: File) {
        require(file.isFile && file.canRead()) { "Package à signer introuvable." }
        ensureProvider()

        val identityDir = File(context.applicationContext.filesDir, DIRECTORY)
        require(identityDir.mkdirs() || identityDir.isDirectory) {
            "Impossible de créer le stockage de l'identité de signature."
        }

        val privateKey = loadOrCreatePrivateKey(identityDir)
        val publicKey = File(identityDir, PUBLIC_FILE)
        require(publicKey.isFile) { "Clé publique de signature absente." }

        val signer = Signature.getInstance("Ed25519", PROVIDER)
        signer.initSign(privateKey)
        FileInputStream(file).buffered(128 * 1024).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) signer.update(buffer, 0, count)
            }
        }

        val signature = signer.sign()
        require(signature.size in 1..4096)
        val publicBytes = publicKey.readBytes()
        require(publicBytes.size in 1..4096)

        signatureFile.parentFile?.mkdirs()
        publicKeyFile.parentFile?.mkdirs()
        writeBytesAtomically(signatureFile, signature)
        writeBytesAtomically(publicKeyFile, publicBytes)
    }

    fun publicKeyFingerprint(context: Context): String {
        ensureProvider()
        val identityDir = File(context.applicationContext.filesDir, DIRECTORY)
        val publicKey = File(identityDir, PUBLIC_FILE)
        if (!publicKey.isFile) loadOrCreatePrivateKey(identityDir)
        val bytes = publicKey.readBytes()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun loadOrCreatePrivateKey(identityDir: File): PrivateKey {
        ensureProvider()
        val privateFile = File(identityDir, PRIVATE_FILE)
        val publicFile = File(identityDir, PUBLIC_FILE)

        if (!privateFile.isFile || !publicFile.isFile) {
            val generator = KeyPairGenerator.getInstance("Ed25519", PROVIDER)
            val pair = generator.generateKeyPair()
            writeEncryptedPrivate(identityDir, pair.private.encoded)
            writeBytesAtomically(publicFile, pair.public.encoded)
        }

        val privateBytes = decryptPrivate(privateFile.readBytes())
        val factory = KeyFactory.getInstance("Ed25519", PROVIDER)
        return factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes))
    }

    private fun writeEncryptedPrivate(identityDir: File, privateBytes: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val ciphertext = cipher.doFinal(privateBytes)

        val target = File(identityDir, PRIVATE_FILE)
        val temporary = File(target.parentFile, target.name + ".tmp-" + SecureRandom().nextLong())
        try {
            DataOutputStream(FileOutputStream(temporary)).use { output ->
                output.writeInt(MAGIC)
                output.writeByte(VERSION)
                output.writeInt(cipher.iv.size)
                output.write(cipher.iv)
                output.writeInt(ciphertext.size)
                output.write(ciphertext)
                output.flush()
                output.fd.sync()
            }
            check(temporary.renameTo(target)) { "Impossible de publier la clé privée chiffrée." }
        } finally {
            temporary.delete()
        }
    }

    private fun decryptPrivate(encoded: ByteArray): ByteArray {
        DataInputStream(encoded.inputStream().buffered()).use { input ->
            require(input.readInt() == MAGIC) { "Magic d'identité de signature invalide." }
            require(input.readUnsignedByte() == VERSION) { "Version d'identité de signature invalide." }
            val ivLength = input.readInt().coerceIn(12, 32)
            val iv = ByteArray(ivLength)
            input.readFully(iv)
            val cipherLength = input.readInt()
            require(cipherLength in 16..64_000) { "Payload d'identité invalide." }
            val ciphertext = ByteArray(cipherLength)
            input.readFully(ciphertext)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
            return cipher.doFinal(ciphertext)
        }
    }

    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(KEY_ALIAS)) {
            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generator.generateKey()
        }
        return (store.getKey(KEY_ALIAS, null) as? SecretKey)
            ?: error("Clé Android Keystore inaccessible.")
    }

    private fun writeBytesAtomically(target: File, bytes: ByteArray) {
        val temporary = File(target.parentFile, target.name + ".tmp-" + SecureRandom().nextLong())
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            check(temporary.renameTo(target)) { "Impossible de publier le fichier de signature." }
        } finally {
            temporary.delete()
        }
    }
}
