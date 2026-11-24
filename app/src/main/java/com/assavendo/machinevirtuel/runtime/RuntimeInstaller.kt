
package com.assavendo.machinevirtuel.runtime

import android.content.Context
import android.os.Build
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.tukaani.xz.XZInputStream

object RuntimeInstaller {
    private const val RUNTIME_REV = "DroidVM-Prebuilts:c4998e150a08f0e60d141591fd63ea0010eb4f75"
    private const val MAX_ENTRIES = 100_000
    private const val MAX_ENTRY_BYTES = 1_073_741_824L
    private const val MAX_TOTAL_BYTES = 2_147_483_648L
    private val lock = Any()
    private val installing = AtomicBoolean(false)

    fun ensure(context: Context): RuntimePaths = synchronized(lock) {
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) {
            "PhoenixVM full runtime currently requires an arm64-v8a host."
        }
        val paths = RuntimePaths(context)
        recoverInterruptedSwap(context, paths)
        if (paths.marker().isFile && paths.marker().readText() == RUNTIME_REV && paths.isComplete()) {
            return paths
        }
        check(installing.compareAndSet(false, true)) { "Runtime installation is already in progress." }
        try {
            installLocked(context, paths)
            check(paths.isComplete()) { "Required full-system runtime files are missing after extraction." }
            paths
        } finally {
            installing.set(false)
        }
    }

    private fun recoverInterruptedSwap(context: Context, paths: RuntimePaths) {
        val previous = File(context.filesDir, "runtime.previous")
        if (!paths.root.exists() && previous.exists()) {
            val backupPaths = RuntimePaths(previous)
            if (backupPaths.isComplete() && backupPaths.marker().isFile) {
                check(previous.renameTo(paths.root)) { "Could not recover the previous verified runtime." }
            }
        }
    }

    private fun installLocked(context: Context, paths: RuntimePaths) {
        val asset = "prebuilts/prebuilt-arm64-v8a.tar.xz"
        val manifestAsset = "prebuilts/prebuilt-arm64-v8a.json"
        val staging = File(context.filesDir, "runtime-staging")
        val extracted = File(staging, "root")
        val candidate = File(context.filesDir, "runtime.next")
        val previous = File(context.filesDir, "runtime.previous")

        staging.deleteRecursively()
        candidate.deleteRecursively()
        check(extracted.mkdirs()) { "Could not create runtime staging directory." }

        try {
            context.assets.open(asset).use { raw ->
                XZInputStream(BufferedInputStream(raw, 128 * 1024)).use { xz ->
                    TarReader(xz).extractTo(extracted)
                }
            }
            verifyCriticalFiles(extracted)
            verifyAllManifestHashes(context, manifestAsset, extracted)

            check(extracted.renameTo(candidate)) {
                "Could not atomically prepare the verified runtime candidate."
            }
            RuntimePaths(candidate).marker().apply {
                parentFile?.mkdirs()
                writeText(RUNTIME_REV)
            }
            check(RuntimePaths(candidate).isComplete()) {
                "Runtime candidate is incomplete after verification."
            }

            var movedCurrent = false
            if (paths.root.exists()) {
                if (previous.exists()) {
                    val previousPaths = RuntimePaths(previous)
                    if (previousPaths.isComplete() && previousPaths.marker().isFile) {
                        previous.deleteRecursively()
                    } else {
                        error("An unverified runtime backup exists; refusing to overwrite it.")
                    }
                }
                check(paths.root.renameTo(previous)) {
                    "Could not preserve the currently installed runtime before update."
                }
                movedCurrent = true
            }

            if (!candidate.renameTo(paths.root)) {
                if (movedCurrent) {
                    check(previous.renameTo(paths.root)) {
                        "Runtime swap failed and the previous runtime could not be restored."
                    }
                }
                error("Could not activate the verified runtime candidate.")
            }

            val installed = RuntimePaths(paths.root)
            if (!installed.isComplete() || !installed.marker().isFile ||
                installed.marker().readText() != RUNTIME_REV
            ) {
                paths.root.deleteRecursively()
                if (movedCurrent) {
                    check(previous.renameTo(paths.root)) {
                        "Installed runtime failed post-swap verification and rollback failed."
                    }
                }
                error("Post-swap runtime verification failed; rollback was attempted.")
            }

            if (movedCurrent) previous.deleteRecursively()
        } finally {
            staging.deleteRecursively()
            candidate.deleteRecursively()
        }
    }

    private fun verifyCriticalFiles(root: File) {
        listOf(
            "usr/bin/qemu-system-aarch64",
            "usr/bin/qemu-img",
            "usr/bin/crosvm",
            "usr/share/droidvm/edk2-qemu.fd",
            "usr/share/droidvm/edk2-qemu.vars.fd",
            "usr/lib/libgfxstream_backend.so",
            "usr/lib/libvirglrenderer.so"
        ).forEach { path ->
            val file = safeChild(root, path)
            check(file.isFile) { "Runtime file missing: " + path }
            if (path.startsWith("usr/bin/")) {
                check(file.setExecutable(true, false)) { "Cannot mark runtime executable: " + path }
            }
        }
    }

    private fun verifyAllManifestHashes(context: Context, manifestAsset: String, root: File) {
        val json = context.assets.open(manifestAsset).bufferedReader().use { it.readText() }
        val entries = JSONObject(json).optJSONArray("hash") ?: JSONArray()
        check(entries.length() > 0) { "Runtime hash manifest is empty." }
        var verified = 0
        for (index in 0 until entries.length()) {
            val item = entries.optJSONObject(index) ?: error("Invalid runtime manifest entry at index " + index)
            val name = item.optString("file")
            val expected = item.optString("sha256")
            require(expected.matches(Regex("[A-Fa-f0-9]{64}"))) {
                "Invalid SHA-256 value in runtime manifest for " + name
            }
            val file = safeChild(root, name)
            check(file.isFile) { "Manifest file is missing: " + name }
            val actual = sha256(file)
            check(expected.equals(actual, ignoreCase = true)) {
                "Runtime hash mismatch for " + name
            }
            verified++
        }
        check(verified == entries.length()) { "Not all runtime manifest entries were verified." }
    }

    private fun safeChild(root: File, relativePath: String): File {
        require(relativePath.isNotBlank() && !relativePath.startsWith("/") &&
            !relativePath.contains('\u0000') && relativePath.split('/').none { it == ".." }
        ) { "Unsafe runtime path: " + relativePath }
        val canonicalRoot = root.canonicalFile.toPath().normalize()
        val candidate = File(root, relativePath).canonicalFile.toPath().normalize()
        require(candidate.startsWith(canonicalRoot) && candidate != canonicalRoot) {
            "Runtime path escapes staging root: " + relativePath
        }
        return candidate.toFile()
    }

    fun optionalRuntimeStatus(context: Context): Map<String, Boolean> {
        val p = RuntimePaths(context)
        return mapOf(
            "qemu_arm64" to p.qemu.canExecute(),
            "qemu_x86_64" to (p.qemuX86.canExecute() || p.qemuX86Headless.canExecute()),
            "qemu_i386" to p.qemuI386.canExecute(),
            "ovmf_x86_64" to p.hasX86Uefi(),
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
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private class TarReader(private val input: InputStream) {
        private var entries = 0
        private var totalBytes = 0L
        private var longName: String? = null
        private var longLink: String? = null
        private val globalPax = mutableMapOf<String, String>()
        private var nextPax = mutableMapOf<String, String>()

        fun extractTo(root: File) {
            val header = ByteArray(512)
            while (true) {
                readFully(header) ?: break
                if (header.all { it == 0.toByte() }) break
                verifyHeaderChecksum(header)

                val headerName = header.copyOfRange(0, 100).ascii()
                val prefix = header.copyOfRange(345, 500).ascii()
                val headerPath = if (prefix.isBlank()) headerName else prefix + "/" + headerName
                val headerLink = header.copyOfRange(157, 257).ascii()
                val mode = header.copyOfRange(100, 108).ascii().trim().toLongOrNull(8) ?: 0L
                val size = header.copyOfRange(124, 136).ascii().trim().toLongOrNull(8)
                    ?: error("Invalid tar entry size.")
                val type = header[156].toInt().toChar()

                require(size in 0..MAX_ENTRY_BYTES) { "Runtime archive entry is oversized." }
                when (type) {
                    'L' -> {
                        longName = readTextEntry(size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                        continue
                    }
                    'K' -> {
                        longLink = readTextEntry(size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                        continue
                    }
                    'x' -> {
                        nextPax.putAll(parsePax(readTextEntry(size)))
                        continue
                    }
                    'g' -> {
                        globalPax.putAll(parsePax(readTextEntry(size)))
                        continue
                    }
                }

                entries++
                check(entries <= MAX_ENTRIES) { "Runtime archive contains too many entries." }
                totalBytes += size
                check(totalBytes <= MAX_TOTAL_BYTES) { "Runtime archive expands beyond the permitted size." }

                val metadata = globalPax + nextPax
                nextPax.clear()
                val name = metadata["path"] ?: longName ?: headerPath
                val linkName = metadata["linkpath"] ?: longLink ?: headerLink
                longName = null
                longLink = null
                val output = safeChild(root, name)
                val outputFile = File(root, name)
                val kind = if (type == '7') '0' else type

                when (kind) {
                    '5' -> {
                        check(outputFile.mkdirs() || outputFile.isDirectory) {
                            "Cannot create runtime directory: " + name
                        }
                        skipExactly(size)
                    }
                    '0', '\u0000' -> {
                        outputFile.parentFile?.mkdirs()
                        require(!Files.exists(output.toPath())) { "Duplicate runtime archive path: " + name }
                        FileOutputStream(output).use { copyExactly(size, it) }
                        output.setReadable(true, false)
                        if ((mode and 0x49L) != 0L || name.startsWith("usr/bin/") || name.startsWith("bin/")) {
                            check(output.setExecutable(true, false)) {
                                "Cannot mark runtime executable: " + name
                            }
                        }
                    }
                    '2' -> {
                        require(linkName.isNotBlank() && !linkName.startsWith("/") &&
                            !linkName.contains('\u0000')
                        ) { "Unsafe runtime symbolic link: " + name }
                        outputFile.parentFile?.mkdirs()
                        require(!Files.exists(output)) { "Duplicate runtime archive path: " + name }
                        val target = output.toPath().parent.resolve(Paths.get(linkName)).normalize()
                        check(target.startsWith(root.canonicalFile.toPath().normalize())) {
                            "Runtime symbolic link escapes staging root: " + name
                        }
                        Files.createSymbolicLink(output.toPath(), Paths.get(linkName))
                        skipExactly(size)
                    }
                    '1' -> {
                        require(linkName.isNotBlank() && !linkName.startsWith("/") &&
                            !linkName.split('/').any { it == ".." }
                        ) { "Unsafe runtime hard link: " + name }
                        val target = safeChild(root, linkName)
                        outputFile.parentFile?.mkdirs()
                        check(Files.isRegularFile(target.toPath())) { "Runtime hard-link target is missing: " + linkName }
                        require(!Files.exists(output)) { "Duplicate runtime archive path: " + name }
                        Files.createLink(output.toPath(), target.toPath())
                        skipExactly(size)
                    }
                    else -> error("Unsupported special tar entry type '" + type + "' for " + name)
                }
                skipPadding(size)
            }
        }

        private fun verifyHeaderChecksum(header: ByteArray) {
            val stored = header.copyOfRange(148, 156).ascii().trim().toLongOrNull(8)
                ?: error("Tar header has no valid checksum.")
            var calculated = 0L
            for (index in header.indices) {
                calculated += if (index in 148..155) 32L else (header[index].toInt() and 0xff).toLong()
            }
            check(stored == calculated) { "Tar header checksum mismatch." }
        }

        private fun parsePax(bytes: ByteArray): Map<String, String> {
            val text = String(bytes, Charsets.UTF_8)
            val values = mutableMapOf<String, String>()
            var cursor = 0
            while (cursor < text.length) {
                val separator = text.indexOf(' ', cursor)
                check(separator > cursor) { "Malformed PAX header." }
                val length = text.substring(cursor, separator).toIntOrNull()
                    ?: error("Malformed PAX record length.")
                check(length > separator - cursor && cursor + length <= text.length) {
                    "Truncated PAX header."
                }
                val record = text.substring(separator + 1, cursor + length - 1)
                val split = record.indexOf('=')
                check(split > 0) { "Malformed PAX key/value." }
                values[record.substring(0, split)] = record.substring(split + 1)
                cursor += length
            }
            return values
        }

        private fun readTextEntry(length: Long): ByteArray {
            check(length <= 64 * 1024L) { "Tar metadata entry is too large." }
            val buffer = ByteArray(length.toInt())
            var offset = 0
            while (offset < buffer.size) {
                val count = input.read(buffer, offset, buffer.size - offset)
                check(count > 0) { "Truncated tar metadata entry." }
                offset += count
            }
            skipPadding(length)
            return buffer
        }

        private fun skipPadding(length: Long) {
            val padding = (512 - (length % 512)) % 512
            if (padding > 0) skipExactly(padding)
        }

        private fun readFully(buffer: ByteArray): ByteArray? {
            var count = 0
            while (count < buffer.size) {
                val n = input.read(buffer, count, buffer.size - count)
                if (n < 0) return if (count == 0) null else error("Truncated tar header.")
                if (n == 0) continue
                count += n
            }
            return buffer
        }

        private fun copyExactly(length: Long, output: FileOutputStream) {
            var remaining = length
            val buffer = ByteArray(128 * 1024)
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(count > 0) { "Truncated tar entry." }
                output.write(buffer, 0, count)
                remaining -= count
            }
        }

        private fun skipExactly(length: Long) {
            var remaining = length
            while (remaining > 0) {
                val count = input.skip(remaining)
                if (count > 0) {
                    remaining -= count
                } else {
                    check(input.read() >= 0) { "Truncated tar entry." }
                    remaining--
                }
            }
        }

        private fun ByteArray.ascii(): String =
            String(this, Charsets.US_ASCII).trimEnd('\u0000', ' ')
    }
}
