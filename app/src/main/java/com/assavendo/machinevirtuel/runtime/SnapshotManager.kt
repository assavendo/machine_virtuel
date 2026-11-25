package com.assavendo.machinevirtuel.runtime

import com.assavendo.machinevirtuel.vm.VmConfig
import com.assavendo.machinevirtuel.vm.VmState
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

data class SnapshotInfo(
    val id: String,
    val vmId: String,
    val name: String,
    val directory: File,
    val createdAt: Long,
    val protected: Boolean,
    val diskSha256: String,
)

object SnapshotManager {
    private const val FORMAT_VERSION = 2

    fun createColdSnapshot(base: File, vm: VmConfig, label: String): File {
        require(vm.state == VmState.OFF) {
            "Une capture à froid exige l'état OFF confirmé ; ERROR peut encore correspondre à un processus vivant."
        }
        require(vm.id.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "Identifiant VM invalide." }
        val source = File(vm.diskPath).canonicalFile
        require(source.isFile && source.canRead()) { "Disque VM introuvable ou illisible." }

        val safe = label.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifBlank { "snapshot" }
        val id = UUID.randomUUID().toString()
        val snapshotRoot = File(base, "snapshots/" + vm.id).apply {
            check(mkdirs() || isDirectory) { "Impossible de créer le répertoire snapshots." }
        }
        val staging = File(snapshotRoot, ".pending-" + id).apply {
            check(mkdirs()) { "Impossible de créer le répertoire temporaire du snapshot." }
        }
        val finalDir = File(snapshotRoot, System.currentTimeMillis().toString() + "-" + safe + "-" + id.take(8))

        try {
            val diskName = source.name + ".snapshot"
            val targetDisk = File(staging, diskName)
            source.copyTo(targetDisk, overwrite = false)
            val nvram = File(vm.firmwarePath).takeIf { it.isFile && it.canRead() }
            val targetNvram = nvram?.let {
                File(staging, "nvram.fd").also { target -> it.copyTo(target, overwrite = false) }
            }

            val createdAt = System.currentTimeMillis()
            val manifest = JSONObject()
                .put("format_version", FORMAT_VERSION)
                .put("id", id)
                .put("vmId", vm.id)
                .put("name", safe)
                .put("createdAt", createdAt)
                .put("protected", false)
                .put("diskFileName", diskName)
                .put("diskSizeBytes", targetDisk.length())
                .put("diskSha256", sha256(targetDisk))
                .put("nvramFileName", if (targetNvram != null) "nvram.fd" else JSONObject.NULL)
                .put("nvramSha256", targetNvram?.let { sha256(it) } ?: JSONObject.NULL)
            writeDurable(File(staging, "manifest.json"), manifest.toString(2))
            try {
                Files.move(
                    staging.toPath(),
                    finalDir.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: Exception) {
                Files.move(
                    staging.toPath(),
                    finalDir.toPath(),
                )
            }
            check(finalDir.isDirectory) { "Impossible de publier le snapshot complet." }
            return finalDir
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    fun list(base: File, vmId: String): List<SnapshotInfo> {
        require(vmId.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "Identifiant VM invalide." }
        val root = File(base, "snapshots/" + vmId)
        return root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".pending-") }?.mapNotNull { dir ->
            runCatching {
                val canonical = requireSnapshotDirectory(vmId, dir)
                val meta = JSONObject(File(canonical, "manifest.json").readText())
                require(meta.getString("vmId") == vmId)
                SnapshotInfo(
                    id = meta.getString("id"),
                    vmId = vmId,
                    name = meta.getString("name"),
                    directory = canonical,
                    createdAt = meta.getLong("createdAt"),
                    protected = meta.optBoolean("protected", false),
                    diskSha256 = meta.getString("diskSha256"),
                )
            }.getOrNull()
        }?.sortedByDescending { it.createdAt }.orEmpty()
    }

    fun protect(snapshotDir: File, value: Boolean) {
        val vmId = snapshotDir.canonicalFile.parentFile?.name
            ?: error("Répertoire snapshot invalide.")
        val canonical = requireSnapshotDirectory(vmId, snapshotDir)
        val manifest = File(canonical, "manifest.json")
        require(manifest.isFile) { "Snapshot manifest introuvable." }
        val meta = JSONObject(manifest.readText())
        require(meta.optString("vmId") == vmId) { "Le snapshot ne correspond pas à cette VM." }
        meta.put("protected", value)
        writeDurable(manifest, meta.toString(2))
    }

    fun delete(snapshotDir: File) {
        val vmId = snapshotDir.canonicalFile.parentFile?.name
            ?: error("Répertoire snapshot invalide.")
        val canonical = requireSnapshotDirectory(vmId, snapshotDir)
        val manifest = File(canonical, "manifest.json")
        require(manifest.isFile) { "Snapshot manifest introuvable." }
        if (JSONObject(manifest.readText()).optBoolean("protected", false)) {
            error("Snapshot protégé.")
        }
        check(canonical.deleteRecursively()) { "Impossible de supprimer le snapshot." }
    }

    fun enforceRetention(base: File, vm: VmConfig) {
        val keep = vm.maxSnapshots.coerceAtLeast(1)
        val unprotected = list(base, vm.id).filterNot { it.protected }
        unprotected.drop(keep).forEach { delete(it.directory) }
    }

    fun restoreColdSnapshot(vm: VmConfig, snapshotDir: File) {
        require(vm.state == VmState.OFF) {
            "La restauration exige l'état OFF confirmé ; ERROR peut encore correspondre à un processus vivant."
        }
        val canonical = requireSnapshotDirectory(vm.id, snapshotDir)
        val manifest = File(canonical, "manifest.json")
        require(manifest.isFile) { "Snapshot manifest introuvable." }
        val meta = JSONObject(manifest.readText())
        require(meta.optString("vmId") == vm.id) { "Ce snapshot appartient à une autre VM." }

        val diskName = meta.optString("diskFileName").takeIf { it.isNotBlank() }
            ?: canonical.listFiles()?.firstOrNull { it.name.endsWith(".snapshot") }?.name
            ?: error("Nom de disque snapshot absent.")
        require(diskName.isNotBlank() && File(diskName).name == diskName) {
            "Nom de disque snapshot invalide."
        }
        val snapshotDisk = File(canonical, diskName)
        require(snapshotDisk.isFile) { "Disque snapshot introuvable." }
        val expectedDiskSize = meta.optLong("diskSizeBytes", snapshotDisk.length())
        check(snapshotDisk.length() == expectedDiskSize) {
            "Snapshot corrompu: taille disque invalide."
        }
        check(meta.getString("diskSha256").equals(sha256(snapshotDisk), ignoreCase = true)) {
            "Snapshot corrompu: SHA-256 du disque invalide."
        }

        val nvramName = meta.optString("nvramFileName").takeIf { it.isNotBlank() && it != "null" }
            ?: if (File(canonical, "nvram.fd").isFile) "nvram.fd" else null
        val snapshotNvram = nvramName?.let {
            require(it == File(it).name) { "Nom NVRAM snapshot invalide." }
            File(canonical, it).also { file ->
                require(file.isFile) { "NVRAM snapshot introuvable." }
                val expected = meta.optString("nvramSha256")
                if (expected.isNotBlank() && expected != "null") {
                    require(expected.matches(Regex("[A-Fa-f0-9]{64}"))) { "SHA-256 NVRAM snapshot invalide." }
                    check(expected.equals(sha256(file), ignoreCase = true)) {
                        "Snapshot corrompu: SHA-256 NVRAM invalide."
                    }
                } else {
                    require(meta.optInt("format_version", 1) < FORMAT_VERSION) {
                        "SHA-256 NVRAM absent d'un snapshot récent."
                    }
                }
            }
        }

        val diskTarget = File(vm.diskPath).canonicalFile
        require(diskTarget.parentFile?.isDirectory == true) { "Répertoire du disque cible indisponible." }
        val diskTemp = File(diskTarget.parentFile, diskTarget.name + ".restore-" + UUID.randomUUID())
        val diskBackup = File(diskTarget.parentFile, diskTarget.name + ".backup-" + UUID.randomUUID())

        val nvramTarget = if (snapshotNvram != null) File(vm.firmwarePath).canonicalFile else null
        require(nvramTarget == null || nvramTarget.parentFile?.isDirectory == true) {
            "Répertoire NVRAM cible indisponible."
        }
        require(nvramTarget == null || nvramTarget != diskTarget) { "Le disque et la NVRAM ne peuvent pas partager le même fichier." }
        val nvramTemp = nvramTarget?.let { File(it.parentFile, it.name + ".restore-" + UUID.randomUUID()) }
        val nvramBackup = nvramTarget?.let { File(it.parentFile, it.name + ".backup-" + UUID.randomUUID()) }

        var diskBackedUp = false
        var diskInstalled = false
        var nvramBackedUp = false
        var nvramInstalled = false

        try {
            snapshotDisk.copyTo(diskTemp, overwrite = false)
            check(sha256(diskTemp).equals(meta.getString("diskSha256"), ignoreCase = true)) {
                "Copie temporaire du disque snapshot invalide."
            }
            if (snapshotNvram != null && nvramTemp != null) {
                snapshotNvram.copyTo(nvramTemp, overwrite = false)
                check(sha256(nvramTemp).equals(meta.getString("nvramSha256"), ignoreCase = true)) {
                    "Copie temporaire NVRAM invalide."
                }
            }

            if (diskTarget.exists()) {
                check(diskTarget.renameTo(diskBackup)) { "Impossible de sauvegarder le disque actuel." }
                diskBackedUp = true
            }
            if (nvramTarget != null && nvramBackup != null && nvramTarget.exists()) {
                check(nvramTarget.renameTo(nvramBackup)) { "Impossible de sauvegarder la NVRAM actuelle." }
                nvramBackedUp = true
            }

            check(diskTemp.renameTo(diskTarget)) { "Impossible d'installer le disque restauré." }
            diskInstalled = true
            if (nvramTarget != null && nvramTemp != null) {
                check(nvramTemp.renameTo(nvramTarget)) { "Impossible d'installer la NVRAM restaurée." }
                nvramInstalled = true
            }

            diskBackup.delete()
            nvramBackup?.delete()
        } catch (t: Throwable) {
            if (diskInstalled) diskTarget.delete()
            if (nvramInstalled) nvramTarget?.delete()
            if (diskBackedUp) check(diskBackup.renameTo(diskTarget)) { "Rollback disque impossible: " + diskBackup.absolutePath }
            if (nvramBackedUp && nvramBackup != null && nvramTarget != null) {
                check(nvramBackup.renameTo(nvramTarget)) { "Rollback NVRAM impossible: " + nvramBackup.absolutePath }
            }
            throw t
        } finally {
            diskTemp.delete()
            nvramTemp?.delete()
        }
    }

    private fun requireSnapshotDirectory(vmId: String, directory: File): File {
        require(vmId.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "Identifiant VM invalide." }
        val canonical = directory.canonicalFile
        val root = canonical.parentFile ?: error("Répertoire snapshot invalide.")
        require(root.name == vmId && root.parentFile?.name == "snapshots") {
            "Le snapshot doit appartenir au répertoire snapshots de la VM."
        }
        require(canonical != root && canonical.isDirectory) { "Répertoire snapshot invalide." }
        return canonical
    }

    private fun writeDurable(file: File, value: String) {
        file.parentFile?.let { check(it.mkdirs() || it.isDirectory) }
        FileOutputStream(file).use { output ->
            output.write(value.toByteArray(Charsets.UTF_8))
            output.flush()
            output.channel.force(true)
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(128 * 1024).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
