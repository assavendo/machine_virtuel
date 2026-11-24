package com.assavendo.machinevirtuel.runtime

import com.assavendo.machinevirtuel.vm.VmConfig
import com.assavendo.machinevirtuel.vm.VmState
import java.io.File
import java.security.MessageDigest
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
    fun createColdSnapshot(base: File, vm: VmConfig, label: String): File {
        require(vm.state != VmState.RUNNING && vm.state != VmState.STARTING) {
            "Cold snapshot requires a stopped VM."
        }
        val source = File(vm.diskPath)
        require(source.isFile) { "Disque VM introuvable." }
        val safe = label.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "snapshot" }
        val id = System.currentTimeMillis().toString()
        val dir = File(base, "snapshots/" + vm.id + "/" + id + "-" + safe).apply { mkdirs() }
        val targetDisk = File(dir, source.name + ".snapshot")
        source.copyTo(targetDisk, overwrite = false)
        val nvram = File(vm.firmwarePath)
        if (nvram.isFile) nvram.copyTo(File(dir, "nvram.fd"), overwrite = false)
        val meta = JSONObject()
            .put("id", id)
            .put("vmId", vm.id)
            .put("name", safe)
            .put("createdAt", System.currentTimeMillis())
            .put("protected", false)
            .put("diskSha256", sha256(targetDisk))
        File(dir, "manifest.json").writeText(meta.toString(2))
        return dir
    }

    fun list(base: File, vmId: String): List<SnapshotInfo> {
        val root = File(base, "snapshots/" + vmId)
        return root.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            runCatching {
                val meta = JSONObject(File(dir, "manifest.json").readText())
                SnapshotInfo(
                    id = meta.getString("id"),
                    vmId = meta.getString("vmId"),
                    name = meta.getString("name"),
                    directory = dir,
                    createdAt = meta.getLong("createdAt"),
                    protected = meta.optBoolean("protected", false),
                    diskSha256 = meta.getString("diskSha256"),
                )
            }.getOrNull()
        }?.sortedByDescending { it.createdAt }.orEmpty()
    }

    fun protect(snapshotDir: File, value: Boolean) {
        val manifest = File(snapshotDir, "manifest.json")
        require(manifest.isFile) { "Snapshot manifest introuvable." }
        val meta = JSONObject(manifest.readText()).put("protected", value)
        manifest.writeText(meta.toString(2))
    }

    fun delete(snapshotDir: File) {
        val manifest = File(snapshotDir, "manifest.json")
        if (manifest.isFile && JSONObject(manifest.readText()).optBoolean("protected", false)) {
            error("Snapshot protégé.")
        }
        require(snapshotDir.canonicalFile.toPath().nameCount > 2) { "Répertoire snapshot non sûr." }
        snapshotDir.deleteRecursively()
    }

    fun enforceRetention(base: File, vm: VmConfig) {
        val snapshots = list(base, vm.id)
        val keep = vm.maxSnapshots.coerceAtLeast(1)
        snapshots.drop(keep).filterNot { it.protected }.forEach { delete(it.directory) }
    }

    fun restoreColdSnapshot(vm: VmConfig, snapshotDir: File) {
        require(vm.state != VmState.RUNNING && vm.state != VmState.STARTING) {
            "Restore requires a stopped VM."
        }
        val manifest = File(snapshotDir, "manifest.json")
        require(manifest.isFile) { "Snapshot manifest introuvable." }
        val meta = JSONObject(manifest.readText())
        val snapshotDisk = snapshotDir.listFiles()?.firstOrNull { it.name.endsWith(".snapshot") }
            ?: error("Disque snapshot introuvable.")
        check(meta.getString("diskSha256").equals(sha256(snapshotDisk), ignoreCase = true)) {
            "Snapshot corrompu: SHA-256 invalide."
        }
        val target = File(vm.diskPath)
        val temp = File(target.parentFile, target.name + ".restore.tmp")
        snapshotDisk.copyTo(temp, overwrite = true)
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "Impossible de restaurer le disque." }
        }
        val snapshotNvram = File(snapshotDir, "nvram.fd")
        if (snapshotNvram.isFile) {
            val nvram = File(vm.firmwarePath)
            val tempNvram = File(nvram.parentFile, nvram.name + ".restore.tmp")
            snapshotNvram.copyTo(tempNvram, overwrite = true)
            if (!tempNvram.renameTo(nvram)) {
                nvram.delete()
                check(tempNvram.renameTo(nvram)) { "Impossible de restaurer NVRAM." }
            }
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
