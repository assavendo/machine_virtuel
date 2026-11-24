
package com.assavendo.machinevirtuel.runtime

import android.content.Context
import android.os.Build
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.tukaani.xz.XZInputStream

object RuntimeInstaller {
    private const val RUNTIME_REV = "DroidVM-Prebuilts:c4998e150a08f0e60d141591fd63ea0010eb4f75"
    private val lock = Any()
    private val installing = AtomicBoolean(false)

    fun ensure(context: Context): RuntimePaths = synchronized(lock) {
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) {
            "PhoenixVM full runtime currently requires an arm64-v8a host."
        }
        val paths = RuntimePaths(context)
        if (paths.marker().isFile && paths.marker().readText() == RUNTIME_REV && paths.isComplete()) return paths
        check(installing.compareAndSet(false, true)) { "Runtime installation is already in progress." }
        try {
            installLocked(context, paths)
            check(paths.isComplete()) { "Required full-system runtime files are missing after extraction." }
            paths
        } finally { installing.set(false) }
    }

    private fun installLocked(context: Context, paths: RuntimePaths) {
        val asset = "prebuilts/prebuilt-arm64-v8a.tar.xz"
        val manifestAsset = "prebuilts/prebuilt-arm64-v8a.json"
        val staging = File(context.cacheDir, "runtime-staging")
        staging.deleteRecursively()
        val extracted = File(staging, "root").apply { mkdirs() }
        context.assets.open(asset).use { raw ->
            XZInputStream(BufferedInputStream(raw, 128 * 1024)).use { xz -> TarReader(xz).extractTo(extracted) }
        }
        verifyCriticalFiles(extracted)
        verifyCriticalHashes(context, manifestAsset, extracted)
        paths.root.deleteRecursively()
        extracted.copyRecursively(paths.root, overwrite = true)
        staging.deleteRecursively()
        paths.marker().apply { parentFile?.mkdirs(); writeText(RUNTIME_REV) }
    }

    private fun verifyCriticalFiles(root: File) {
        listOf(
            "usr/bin/qemu-system-aarch64", "usr/bin/qemu-img", "usr/bin/crosvm",
            "usr/share/droidvm/edk2-qemu.fd", "usr/share/droidvm/edk2-qemu.vars.fd",
            "usr/lib/libgfxstream_backend.so", "usr/lib/libvirglrenderer.so"
        ).forEach { path ->
            val file = File(root, path)
            check(file.isFile) { "Runtime file missing: $path" }
            if (path.startsWith("usr/bin/")) check(file.setExecutable(true, false)) { "Cannot mark runtime executable: $path" }
        }
    }

    private fun verifyCriticalHashes(context: Context, manifestAsset: String, root: File) {
        val json = context.assets.open(manifestAsset).bufferedReader().use { it.readText() }
        val entries = JSONObject(json).optJSONArray("hash") ?: JSONArray()
        val hashes = mutableMapOf<String, String>()
        for (index in 0 until entries.length()) {
            val item = entries.optJSONObject(index) ?: continue
            val file = item.optString("file")
            val hash = item.optString("sha256")
            if (file.isNotBlank() && hash.isNotBlank()) hashes[file] = hash
        }
        listOf(
            "usr/bin/qemu-system-aarch64", "usr/bin/qemu-img", "usr/bin/crosvm",
            "usr/share/droidvm/edk2-qemu.fd", "usr/share/droidvm/edk2-qemu.vars.fd"
        ).forEach { name ->
            val expected = hashes[name] ?: error("Runtime manifest missing " + name)
            val actual = sha256(File(root, name))
            check(expected.equals(actual, ignoreCase = true)) { "Runtime hash mismatch for " + name }
        }
    }

    fun optionalRuntimeStatus(context: Context): Map<String, Boolean> {
        val p = RuntimePaths(context)
        return mapOf(
            "qemu_arm64" to p.qemu.canExecute(),
            "qemu_x86_64" to (p.qemuX86.canExecute() || p.qemuX86Headless.canExecute()),
            "qemu_i386" to p.qemuI386.canExecute(),
            "virtiofsd" to p.hasVirtiofsd(),
            "swtpm" to p.hasSwtpm(),
            "gfxstream" to File(p.libDir, "libgfxstream_backend.so").isFile,
            "virglrenderer" to File(p.libDir, "libvirglrenderer.so").isFile,
        )
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            var n = input.read(buffer)
            while (n >= 0) {
                if (n > 0) md.update(buffer, 0, n)
                n = input.read(buffer)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private class TarReader(private val input: InputStream) {
        fun extractTo(root: File) {
            val header = ByteArray(512)
            while (true) {
                readFully(header) ?: break
                if (header.all { it == 0.toByte() }) break
                val name = header.copyOfRange(0, 100).ascii()
                val prefix = header.copyOfRange(345, 500).ascii()
                val fullName = if (prefix.isBlank()) name else prefix + "/" + name
                val mode = header.copyOfRange(100, 108).ascii().trim().toLongOrNull(8) ?: 0L
                val size = header.copyOfRange(124, 136).ascii().trim().toLongOrNull(8) ?: 0L
                val type = header[156].toInt().toChar()
                validatePath(fullName)
                when (type) {
                    '5' -> File(root, fullName).mkdirs()
                    '0', '\u0000' -> {
                        val out = File(root, fullName)
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { copyExactly(input, it, size) }
                        out.setReadable(true, false)
                        if ((mode and 0x49L) != 0L || fullName.startsWith("usr/bin/") || fullName.startsWith("bin/"))
                            check(out.setExecutable(true, false)) { "Cannot mark executable: " + fullName }
                    }
                    else -> skipExactly(input, size)
                }
                val padding = (512 - (size % 512)) % 512
                if (padding > 0) skipExactly(input, padding)
            }
        }

        private fun validatePath(name: String) {
            require(name.isNotBlank() && !name.startsWith("/") && !name.contains('\u0000')) { "Unsafe runtime path: " + name }
            require(name.split('/').none { it == ".." }) { "Path traversal in runtime archive: " + name }
        }
        private fun readFully(buffer: ByteArray): ByteArray? {
            var count = 0
            while (count < buffer.size) {
                val n = input.read(buffer, count, buffer.size - count)
                if (n < 0) return if (count == 0) null else error("Truncated tar header")
                if (n == 0) continue
                count += n
            }
            return buffer
        }
        private fun copyExactly(input: InputStream, output: FileOutputStream, length: Long) {
            var remaining = length
            val buffer = ByteArray(128 * 1024)
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(n > 0) { "Truncated tar entry" }
                output.write(buffer, 0, n)
                remaining -= n
            }
        }
        private fun skipExactly(input: InputStream, length: Long) {
            var remaining = length
            while (remaining > 0) {
                val n = input.skip(remaining)
                if (n > 0) remaining -= n
                else { check(input.read() >= 0) { "Truncated tar entry" }; remaining-- }
            }
        }
        private fun ByteArray.ascii(): String = String(this, Charsets.US_ASCII).trimEnd('\u0000', ' ')
    }
}
