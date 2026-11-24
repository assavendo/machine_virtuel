package com.assavendo.machinevirtuel.runtime

import android.content.Context
import android.os.StatFs
import com.assavendo.machinevirtuel.security.PackageSignatureVerifier
import com.assavendo.machinevirtuel.security.SecurityGuards
import com.assavendo.machinevirtuel.vm.*
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

object VmPackageManager {
    private const val FORMAT_VERSION = 2
    private const val MAX_ENTRIES = 50_000
    private const val MAX_MANIFEST_BYTES = 1_048_576L
    private const val MAX_TOTAL_BYTES = 4_398_046_511_104L // 4 TiB; also bounded by available device storage.

    fun export(context: Context, vm: VmConfig, output: File, includeSnapshots: Boolean = false): File {
        require(vm.state == VmState.OFF || vm.state == VmState.ERROR) {
            "L'export sécurisé exige une VM complètement arrêtée."
        }
        val disk = File(vm.diskPath).canonicalFile
        require(disk.isFile && disk.canRead()) { "Disque VM introuvable ou illisible." }
        val firmware = File(vm.firmwarePath).takeIf { it.isFile && it.canRead() }
        val parent = output.absoluteFile.parentFile
            ?: error("Le dossier de destination du package est invalide.")
        check(parent.mkdirs() || parent.isDirectory) { "Impossible de créer le dossier d'export." }
        require(output.canonicalFile != disk) { "Le package de sortie ne peut pas remplacer le disque VM." }

        val portableConfig = vm.copy(
            id = "portable-template",
            diskPath = "",
            firmwarePath = "",
            isoPath = "",
            tpmEnabled = false,
            tpmStatePath = "",
            sharedDirPath = "",
            tapName = "",
            state = VmState.OFF,
            lastStartedAt = null,
        )
        val manifest = JSONObject()
            .put("format_version", FORMAT_VERSION)
            .put("vm_id", vm.id)
            .put("name", vm.name)
            .put("architecture", vm.arch.name)
            .put("disk_format", vm.diskFormat.name)
            .put("disk_size_gib", vm.diskSizeGiB)
            .put("disk_size_bytes", disk.length())
            .put("disk_file_name", "disk." + DiskManager.extension(vm.diskFormat))
            .put("sha256_disk", ImageVerifier.sha256(disk))
            .put("nvram_present", firmware != null)
            .put("nvram_size_bytes", firmware?.length() ?: 0L)
            .put("sha256_nvram", firmware?.let { ImageVerifier.sha256(it) } ?: JSONObject.NULL)
            .put("vm_config", VmStore(context).encodeConfigForPackage(portableConfig))

        val temp = File(parent, output.name + ".pending-" + UUID.randomUUID())
        val backup = File(parent, output.name + ".backup-" + UUID.randomUUID())
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temp), 256 * 1024)).use { zip ->
                putJson(zip, "manifest.json", manifest)
                putFile(zip, "disk/" + manifest.getString("disk_file_name"), disk)
                if (firmware != null) putFile(zip, "firmware/nvram.fd", firmware)
                if (includeSnapshots) {
                    SnapshotManager.list(context.filesDir, vm.id).forEach { snapshot ->
                        require(snapshot.directory.name.matches(Regex("[A-Za-z0-9._-]{1,160}"))) {
                            "Nom de snapshot non sûr."
                        }
                        snapshot.directory.walkTopDown().filter { it.isFile }.forEach { file ->
                            val safe = SecurityGuards.canonicalChild(snapshot.directory, file)
                            val relative = snapshot.directory.toPath().relativize(safe.toPath()).toString()
                            putFile(zip, "snapshots/" + snapshot.directory.name + "/" + relative, safe)
                        }
                    }
                }
            }

            if (output.exists()) {
                check(output.renameTo(backup)) { "Impossible de sauvegarder l'ancien package." }
            }
            if (!temp.renameTo(output)) {
                if (backup.exists()) check(backup.renameTo(output)) {
                    "L'export a échoué et l'ancien package n'a pas pu être restauré."
                }
                error("Impossible de publier le package VM.")
            }
            backup.delete()
            // An old sidecar signature would authenticate different bytes after overwrite.
            File(output.absolutePath + ".sig").delete()
            File(output.absolutePath + ".pub").delete()
            return output
        } catch (t: Throwable) {
            temp.delete()
            if (backup.exists() && !output.exists()) backup.renameTo(output)
            throw t
        } finally {
            temp.delete()
            if (backup.exists() && output.exists()) backup.delete()
        }
    }

    fun importPackage(context: Context, packageFile: File): VmConfig {
        require(packageFile.isFile && packageFile.canRead()) { "Package VM introuvable ou illisible." }
        val signature = File(packageFile.absolutePath + ".sig")
        val publicKey = File(packageFile.absolutePath + ".pub")
        require(signature.isFile == publicKey.isFile) { "La paire de signature du package est incomplète." }
        if (signature.isFile && publicKey.isFile) {
            require(signature.length() in 1..4096 && publicKey.length() in 1..4096) {
                "Fichiers de signature anormalement volumineux."
            }
            require(PackageSignatureVerifier.verifyFile(packageFile, signature, publicKey)) {
                "Signature Ed25519 du package invalide."
            }
        }

        val id = UUID.randomUUID().toString()
        val staging = File(context.cacheDir, "vm-import-" + id).apply {
            check(mkdirs()) { "Impossible de créer le staging d'import." }
        }
        val vmRoot = File(context.filesDir, "vms")
        val tempVmDir = File(vmRoot, ".import-" + id)
        val finalVmDir = File(vmRoot, id)
        val snapshotsRoot = File(context.filesDir, "snapshots")
        val tempSnapshotsDir = File(snapshotsRoot, ".import-" + id)
        val finalSnapshotsDir = File(snapshotsRoot, id)
        var vmPublished = false
        var snapshotsPublished = false

        try {
            unzipSafe(packageFile, staging)
            val manifestFile = SecurityGuards.canonicalChild(staging, File(staging, "manifest.json"))
            require(manifestFile.isFile && manifestFile.length() in 1..MAX_MANIFEST_BYTES) {
                "Manifeste de package absent ou trop volumineux."
            }
            val manifest = JSONObject(manifestFile.readText())
            val version = manifest.optInt("format_version", -1)
            require(version in 1..FORMAT_VERSION) { "Version de package VM non supportée." }

            val originalVmId = manifest.optString("vm_id")
            require(originalVmId.matches(Regex("[A-Za-z0-9-]{1,64}"))) {
                "Identifiant VM source invalide."
            }
            val diskDir = File(staging, "disk")
            val diskEntries = diskDir.listFiles()?.filter { it.isFile }.orEmpty()
            require(diskEntries.size == 1) { "Package VM: exactement un disque est attendu." }
            val importedDisk = diskEntries.single()
            val expectedDiskSize = manifest.getLong("disk_size_bytes")
            require(expectedDiskSize > 0 && importedDisk.length() == expectedDiskSize) {
                "Taille du disque importé incohérente avec le manifeste."
            }
            val expectedDiskHash = manifest.getString("sha256_disk")
            require(expectedDiskHash.matches(Regex("[A-Fa-f0-9]{64}"))) { "SHA-256 disque invalide dans le manifeste." }
            check(ImageVerifier.verify(importedDisk, expectedDiskHash)) {
                "SHA-256 du disque importé invalide."
            }

            val firmware = File(staging, "firmware/nvram.fd").takeIf { it.isFile }
            if (manifest.optBoolean("nvram_present", firmware != null)) {
                require(firmware != null) { "La NVRAM référencée par le manifeste est absente." }
                require(firmware.length() == manifest.optLong("nvram_size_bytes", firmware.length())) {
                    "Taille NVRAM incohérente."
                }
                val nvramHash = manifest.optString("sha256_nvram")
                if (nvramHash.isNotBlank() && nvramHash != "null") {
                    require(nvramHash.matches(Regex("[A-Fa-f0-9]{64}"))) { "SHA-256 NVRAM invalide." }
                    check(ImageVerifier.verify(firmware, nvramHash)) { "SHA-256 NVRAM invalide." }
                }
            }

            val store = VmStore(context)
            val configJson = manifest.optJSONObject("vm_config")
            val vm = if (configJson != null) {
                store.decodeConfigFromPackage(configJson)
            } else {
                VmConfig(
                    name = manifest.optString("name", "Imported VM"),
                    guestOs = "Imported",
                    arch = GuestArch.valueOf(manifest.optString("architecture", GuestArch.ARM64.name)),
                    diskFormat = DiskFormat.valueOf(manifest.optString("disk_format", DiskFormat.RAW.name)),
                )
            }
            vm.id = id
            vm.name = manifest.optString("name", vm.name).take(120).ifBlank { "Imported VM" }
            vm.state = VmState.OFF
            vm.lastStartedAt = null
            vm.diskFormat = DiskFormat.valueOf(manifest.optString("disk_format", vm.diskFormat.name))
            vm.arch = GuestArch.valueOf(manifest.optString("architecture", vm.arch.name))
            vm.diskSizeGiB = manifest.optLong("disk_size_gib", vm.diskSizeGiB).coerceIn(1L, 4096L)
            vm.backend = BackendKind.QEMU_TCG
            vm.isoPath = ""
            vm.sharedDirPath = ""
            vm.tapName = ""
            vm.tpmEnabled = false
            vm.tpmStatePath = ""
            if (vm.networkMode == NetworkMode.TAP) vm.networkMode = NetworkMode.NAT

            val validation = VmSpecValidator.validate(vm).filter { it.fatal }
            require(validation.isEmpty()) {
                validation.joinToString("; ") { it.field + ": " + it.message }
            }

            check(vmRoot.mkdirs() || vmRoot.isDirectory) { "Répertoire VM indisponible." }
            check(tempVmDir.mkdirs()) { "Impossible de créer le répertoire VM temporaire." }
            val diskTarget = File(tempVmDir, "disk." + DiskManager.extension(vm.diskFormat))
            importedDisk.copyTo(diskTarget, overwrite = false)
            val varsTarget = File(tempVmDir, "nvram.fd")
            if (firmware != null) firmware.copyTo(varsTarget, overwrite = false)
            vm.diskPath = File(finalVmDir, diskTarget.name).absolutePath
            vm.firmwarePath = File(finalVmDir, "nvram.fd").absolutePath

            val stagedSnapshots = File(staging, "snapshots")
            if (stagedSnapshots.isDirectory) {
                check(snapshotsRoot.mkdirs() || snapshotsRoot.isDirectory) {
                    "Répertoire des snapshots indisponible."
                }
                check(tempSnapshotsDir.mkdirs()) { "Impossible de créer le staging des snapshots." }
                stagedSnapshots.listFiles()?.filter { it.isDirectory }?.forEach { sourceDir ->
                    require(sourceDir.name.matches(Regex("[A-Za-z0-9._-]{1,160}"))) {
                        "Nom de snapshot importé non sûr."
                    }
                    val sourceManifest = File(sourceDir, "manifest.json")
                    require(sourceManifest.isFile) { "Manifeste snapshot manquant." }
                    val metadata = JSONObject(sourceManifest.readText())
                    require(metadata.optString("vmId") == originalVmId) {
                        "Un snapshot du package appartient à une autre VM."
                    }
                    val snapshotDiskName = metadata.optString("diskFileName").takeIf { it.isNotBlank() }
                        ?: sourceDir.listFiles()?.firstOrNull { it.name.endsWith(".snapshot") }?.name
                        ?: error("Disque snapshot absent du package.")
                    require(File(snapshotDiskName).name == snapshotDiskName) { "Nom de disque snapshot invalide." }
                    val snapshotDisk = File(sourceDir, snapshotDiskName)
                    require(snapshotDisk.isFile) { "Disque snapshot manquant." }
                    val snapshotHash = metadata.getString("diskSha256")
                    require(snapshotHash.matches(Regex("[A-Fa-f0-9]{64}")) &&
                        ImageVerifier.verify(snapshotDisk, snapshotHash)
                    ) { "Empreinte d'un snapshot importé invalide." }

                    val destination = File(tempSnapshotsDir, sourceDir.name)
                    check(sourceDir.copyRecursively(destination, overwrite = false)) {
                        "Impossible de copier un snapshot importé."
                    }
                    metadata.put("vmId", id)
                    File(destination, "manifest.json").writeText(metadata.toString(2))
                }
            }

            check(tempVmDir.renameTo(finalVmDir)) { "Impossible de publier les fichiers de VM importés." }
            vmPublished = true
            if (tempSnapshotsDir.exists()) {
                check(tempSnapshotsDir.renameTo(finalSnapshotsDir)) {
                    "Impossible de publier les snapshots importés."
                }
                snapshotsPublished = true
            }
            store.save(store.list().toMutableList().apply { add(vm) })
            return vm
        } catch (t: Throwable) {
            if (snapshotsPublished) finalSnapshotsDir.deleteRecursively()
            if (vmPublished) finalVmDir.deleteRecursively()
            throw t
        } finally {
            staging.deleteRecursively()
            tempVmDir.deleteRecursively()
            tempSnapshotsDir.deleteRecursively()
        }
    }

    private fun unzipSafe(zipFile: File, target: File) {
        val available = StatFs(target.absolutePath).availableBytes
        val totalLimit = minOf(MAX_TOTAL_BYTES, (available * 9L) / 10L)
        require(totalLimit > 0L) { "Espace de stockage insuffisant pour importer ce package." }
        val seen = mutableSetOf<String>()
        var entries = 0
        var totalWritten = 0L
        ZipInputStream(zipFile.inputStream().buffered(128 * 1024)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                check(entries <= MAX_ENTRIES) { "Le package contient trop d'entrées." }
                require(!entry.name.startsWith("/") && !entry.name.contains('\\') &&
                    !entry.name.contains('\u0000') && !entry.name.matches(Regex("^[A-Za-z]:.*"))
                ) { "Chemin ZIP absolu ou invalide." }
                val normalized = entry.name.trimEnd('/')
                require(normalized.isNotBlank() && normalized.split('/').none { it == ".." || it == "." }) {
                    "Chemin ZIP avec traversée de répertoires."
                }
                require(seen.add(normalized)) { "Entrée ZIP dupliquée: " + normalized }
                val output = SecurityGuards.canonicalChild(target, File(target, normalized))
                if (entry.isDirectory) {
                    check(output.mkdirs() || output.isDirectory) { "Impossible de créer un dossier importé." }
                } else {
                    check(!output.exists()) { "Fichier importé déjà présent: " + normalized }
                    output.parentFile?.mkdirs()
                    var entryBytes = 0L
                    FileOutputStream(output).buffered(128 * 1024).use { out ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            entryBytes += count
                            totalWritten += count
                            check(totalWritten <= totalLimit) {
                                "Le package décompressé dépasse l'espace de sécurité disponible."
                            }
                            if (normalized == "manifest.json") {
                                check(entryBytes <= MAX_MANIFEST_BYTES) { "Manifeste ZIP trop volumineux." }
                            }
                            out.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun putJson(zip: ZipOutputStream, path: String, json: JSONObject) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(json.toString(2).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun putFile(zip: ZipOutputStream, path: String, file: File) {
        zip.putNextEntry(ZipEntry(path))
        file.inputStream().buffered(256 * 1024).use { it.copyTo(zip, 256 * 1024) }
        zip.closeEntry()
    }
}
