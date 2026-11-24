
package com.assavendo.machinevirtuel.runtime

import android.content.Context
import android.net.Uri
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
import com.assavendo.machinevirtuel.vm.VmStore
import com.assavendo.machinevirtuel.vm.VmState
import com.assavendo.machinevirtuel.vm.GuestArch

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

    /**
     * Installs a separately built ARM-hosted x86 full-system QEMU add-on.
     * The user must select an archive they built from scripts/build-qemu-android-x86-runtime.sh
     * or obtained from a trusted project release. SHA-256 inside the archive detects corruption,
     * but it is not a publisher signature; the UI must present this trust warning before import.
     */
    fun installOptionalX86(context: Context, archiveUri: Uri): RuntimePaths = synchronized(lock) {
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) {
            "L'extension QEMU x86 est compilée pour un hôte Android ARM64."
        }
        check(installing.compareAndSet(false, true)) { "Une installation du runtime est déjà en cours." }
        val paths = RuntimePaths(context)
        val staging = File(context.filesDir, "runtime-addons-staging")
        val extracted = File(staging, "root")
        val candidate = File(context.filesDir, "runtime-addons.next")
        val previous = File(context.filesDir, "runtime-addons.previous")
        val archive = File(context.cacheDir, "phoenixvm-x86-runtime-" + System.currentTimeMillis() + ".tar.xz")
        try {
            staging.deleteRecursively()
            candidate.deleteRecursively()
            check(extracted.mkdirs()) { "Impossible de créer le staging du runtime x86." }

            val input = context.contentResolver.openInputStream(archiveUri)
                ?: error("Impossible d'ouvrir l'archive sélectionnée.")
            input.use { source ->
                FileOutputStream(archive).use { target ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= 1_073_741_824L) {
                            "L'archive x86 dépasse la limite compressée de 1 Gio."
                        }
                        target.write(buffer, 0, count)
                    }
                    target.fd.sync()
                }
            }
            check(archive.isFile && archive.length() > 0L) { "Archive runtime x86 vide." }

            XZInputStream(BufferedInputStream(FileInputStream(archive), 128 * 1024)).use { xz ->
                TarReader(xz).extractTo(extracted)
            }
            verifyOptionalX86Package(extracted)
            check(extracted.renameTo(candidate)) {
                "Impossible de préparer atomiquement le runtime x86 vérifié."
            }
            File(candidate, ".addon-version").writeText("PhoenixVM x86 add-on format 1\n")

            if (paths.optionalRoot.exists()) {
                if (previous.exists()) {
                    require(File(previous, ".addon-version").isFile) {
                        "Une sauvegarde précédente non vérifiée existe; elle ne sera pas écrasée."
                    }
                    previous.deleteRecursively()
                }
                check(paths.optionalRoot.renameTo(previous)) {
                    "Impossible de sauvegarder l'extension x86 actuelle."
                }
            }
            if (!candidate.renameTo(paths.optionalRoot)) {
                if (previous.exists()) check(previous.renameTo(paths.optionalRoot)) {
                    "Échec d'installation et restauration de l'extension précédente impossible."
                }
                error("Impossible d'activer le runtime x86.")
            }

            val installed = RuntimePaths(context)
            if (!installed.hasX86SystemQemu() || !installed.hasX86Uefi() ||
                !installed.x86LibDir.isDirectory || !installed.qemuShareX86.isDirectory ||
                !File(installed.optionalRoot, ".addon-version").isFile
            ) {
                paths.optionalRoot.deleteRecursively()
                if (previous.exists()) check(previous.renameTo(paths.optionalRoot)) {
                    "Runtime x86 invalide; restauration de l'extension précédente impossible."
                }
                error("La vérification finale du runtime x86 a échoué.")
            }
            previous.deleteRecursively()
            installed
        } finally {
            archive.delete()
            staging.deleteRecursively()
            candidate.deleteRecursively()
            installing.set(false)
        }
    }

    /**
     * Installs a verified optional runtime tool bundle under runtime-addons/tools/<toolId>.
     * Archives must contain usr/... plus SHA256SUMS. The swap is isolated to the selected
     * add-on so the x86 runtime and the base ARM64 runtime cannot be damaged by an update.
     */
    fun installOptionalTool(context: Context, archiveUri: Uri, toolId: String): RuntimePaths = synchronized(lock) {
        require(toolId in setOf("virtiofsd", "swtpm")) { "Extension runtime inconnue." }
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) {
            "Les extensions runtime sont provisionnées pour un hôte Android ARM64."
        }
        check(installing.compareAndSet(false, true)) { "Une installation du runtime est déjà en cours." }

        val paths = RuntimePaths(context)
        val parent = File(paths.optionalRoot, "tools")
        val staging = File(parent, "$toolId-staging")
        val extracted = File(staging, "root")
        val candidate = File(parent, "$toolId.next")
        val previous = File(parent, "$toolId.previous")
        val archive = File(context.cacheDir, "phoenixvm-$toolId-" + System.currentTimeMillis() + ".tar.xz")
        val required = when (toolId) {
            "virtiofsd" -> listOf("usr/libexec/virtiofsd")
            "swtpm" -> listOf("usr/bin/swtpm")
            else -> error("Extension runtime inconnue.")
        }
        val markerValue = "PhoenixVM $toolId add-on format 1\n"

        try {
            staging.deleteRecursively()
            candidate.deleteRecursively()
            check(extracted.mkdirs()) { "Impossible de créer le staging de l'extension $toolId." }

            val source = context.contentResolver.openInputStream(archiveUri)
                ?: error("Impossible d'ouvrir l'archive sélectionnée.")
            source.use {
                FileOutputStream(archive).use { target ->
                    val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= 1_073_741_824L) {
                            "L'archive $toolId dépasse la limite compressée de 1 Gio."
                        }
                        target.write(buffer, 0, count)
                    }
                    target.fd.sync()
                }
            }

            XZInputStream(BufferedInputStream(FileInputStream(archive), 128 * 1024)).use { xz ->
                TarReader(xz).extractTo(extracted)
            }
            verifyOptionalToolPackage(extracted, required)

            check(extracted.renameTo(candidate)) {
                "Impossible de préparer atomiquement l'extension $toolId."
            }
            File(candidate, ".addon-version").writeText(markerValue)
            check(RuntimePaths(context).let { File(candidate, "usr").isDirectory }) {
                "L'extension $toolId ne contient pas d'arborescence usr/."
            }

            parent.mkdirs()
            if (File(parent, toolId).exists()) {
                if (previous.exists()) {
                    require(File(previous, ".addon-version").isFile) {
                        "Une sauvegarde précédente non vérifiée de $toolId existe; elle ne sera pas écrasée."
                    }
                    previous.deleteRecursively()
                }
                check(File(parent, toolId).renameTo(previous)) {
                    "Impossible de sauvegarder l'extension $toolId actuelle."
                }
            }

            val target = File(parent, toolId)
            if (!candidate.renameTo(target)) {
                if (previous.exists()) check(previous.renameTo(target)) {
                    "Échec d'installation et restauration de l'extension $toolId impossible."
                }
                error("Impossible d'activer l'extension $toolId.")
            }

            val installed = RuntimePaths(context)
            val ready = when (toolId) {
                "virtiofsd" -> installed.hasVirtiofsd() && installed.virtiofsd.parentFile?.isDirectory == true
                "swtpm" -> installed.hasSwtpm() && installed.swtpm.parentFile?.isDirectory == true
                else -> false
            }
            if (!ready) {
                target.deleteRecursively()
                if (previous.exists()) check(previous.renameTo(target)) {
                    "Extension $toolId invalide et restauration impossible."
                }
                error("La vérification finale de l'extension $toolId a échoué.")
            }
            previous.deleteRecursively()
            installed
        } finally {
            archive.delete()
            staging.deleteRecursively()
            candidate.deleteRecursively()
            installing.set(false)
        }
    }

    fun removeOptionalTool(context: Context, toolId: String) = synchronized(lock) {
        require(toolId in setOf("virtiofsd", "swtpm")) { "Extension runtime inconnue." }
        val hasActiveVm = VmStore(context).list().any {
            it.state == VmState.RUNNING || it.state == VmState.STARTING || it.state == VmState.PAUSED
        }
        require(!hasActiveVm) { "Arrêtez les VM avant de retirer une extension runtime." }

        val target = File(RuntimePaths(context).optionalRoot, "tools/$toolId")
        if (target.exists()) {
            require(File(target, ".addon-version").isFile) {
                "Le dossier $toolId ne possède pas de marqueur PhoenixVM; suppression refusée."
            }
            check(target.deleteRecursively()) { "Impossible de retirer l'extension $toolId." }
        }
    }

    private fun verifyOptionalToolPackage(root: File, requiredPaths: List<String>) {
        val manifest = safeChild(root, "SHA256SUMS")
        require(manifest.isFile && manifest.length() in 1..1_048_576L) {
            "Le manifeste SHA256SUMS de l'extension est absent ou trop volumineux."
        }
        val entries = manifest.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        require(entries.isNotEmpty() && entries.size <= MAX_ENTRIES) {
            "Le manifeste SHA256SUMS de l'extension est vide ou trop volumineux."
        }
        val seen = mutableSetOf<String>()
        for (line in entries) {
            val pair = line.split(Regex("\\s+"), limit = 2)
            require(pair.size == 2 && pair[0].matches(Regex("[A-Fa-f0-9]{64}"))) {
                "Ligne SHA-256 invalide dans l'extension."
            }
            val relative = pair[1].removePrefix("*")
            require(relative.startsWith("usr/") && seen.add(relative)) {
                "Chemin non autorisé ou dupliqué dans l'extension."
            }
            val file = safeChild(root, relative)
            check(file.isFile) { "Fichier absent de l'extension: " + relative }
            check(pair[0].equals(sha256(file), ignoreCase = true)) {
                "SHA-256 invalide pour l'extension: " + relative
            }
        }
        requiredPaths.forEach { path ->
            require(seen.contains(path)) { "Entrée essentielle absente de l'extension: " + path }
            val file = safeChild(root, path)
            check(file.isFile && file.length() > 0L) { "Fichier essentiel absent: " + path }
            if (path.startsWith("usr/bin/") || path.startsWith("usr/libexec/")) {
                check(file.setExecutable(true, false)) {
                    "Impossible d'activer l'exécutable de l'extension: " + path
                }
            }
        }
        check(safeChild(root, "usr/lib").isDirectory) {
            "Le répertoire usr/lib est requis pour isoler les dépendances natives de l'extension."
        }
    }

    fun removeOptionalX86(context: Context) = synchronized(lock) {
        val paths = RuntimePaths(context)
        val activeX86 = VmStore(context).list().any {
            it.arch != GuestArch.ARM64 &&
                (it.state == VmState.RUNNING || it.state == VmState.STARTING || it.state == VmState.PAUSED)
        }
        require(!activeX86) { "Arrêtez toutes les VM x86 avant de retirer le runtime." }
        if (paths.optionalRoot.exists()) {
            require(File(paths.optionalRoot, ".addon-version").isFile) {
                "Le dossier d'extension ne possède pas de marqueur PhoenixVM; suppression refusée."
            }
            check(paths.optionalRoot.deleteRecursively()) { "Impossible de retirer le runtime x86." }
        }
    }

    private fun verifyOptionalX86Package(root: File) {
        val manifest = safeChild(root, "SHA256SUMS")
        require(manifest.isFile && manifest.length() in 1..1_048_576L) {
            "Le manifeste SHA256SUMS est absent ou trop volumineux."
        }
        val entries = manifest.readLines()
        require(entries.isNotEmpty() && entries.size <= MAX_ENTRIES) {
            "Le manifeste SHA256SUMS est vide ou contient trop d'entrées."
        }
        val seen = mutableSetOf<String>()
        entries.forEach { line ->
            val pair = line.trim().split(Regex("\\s+"), limit = 2)
            require(pair.size == 2 && pair[0].matches(Regex("[A-Fa-f0-9]{64}"))) {
                "Ligne SHA-256 invalide dans l'extension x86."
            }
            val relative = pair[1].removePrefix("*")
            require(relative.startsWith("usr/") && seen.add(relative)) {
                "Chemin absent de usr/ ou dupliqué dans le manifeste x86."
            }
            val file = safeChild(root, relative)
            check(file.isFile) { "Fichier absent du runtime x86: " + relative }
            check(pair[0].equals(sha256(file), ignoreCase = true)) {
                "SHA-256 invalide pour le fichier runtime x86: " + relative
            }
        }
        val required = listOf(
            "usr/bin/qemu-system-x86_64",
            "usr/share/droidvm/edk2-x86_64-code.fd",
            "usr/share/droidvm/edk2-x86_64-vars.fd",
        )
        required.forEach { path ->
            check(seen.contains(path)) { "Entrée essentielle absente du manifeste x86: " + path }
            val file = safeChild(root, path)
            check(file.isFile && file.length() > 0L) { "Fichier essentiel x86 absent: " + path }
        }
        val qemu = safeChild(root, "usr/bin/qemu-system-x86_64")
        check(qemu.setExecutable(true, false)) { "Impossible d'activer l'exécutable QEMU x86_64." }
        val i386 = safeChild(root, "usr/bin/qemu-system-i386")
        if (i386.isFile) check(i386.setExecutable(true, false)) {
            "Impossible d'activer l'exécutable QEMU i386."
        }
        check(safeChild(root, "usr/lib").isDirectory) { "Les bibliothèques du runtime x86 sont absentes." }
        check(safeChild(root, "usr/share/qemu").isDirectory) { "Les données QEMU x86 sont absentes." }
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
                        require(!Files.exists(output.toPath())) { "Duplicate runtime archive path: " + name }
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
                        require(!Files.exists(output.toPath())) { "Duplicate runtime archive path: " + name }
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
