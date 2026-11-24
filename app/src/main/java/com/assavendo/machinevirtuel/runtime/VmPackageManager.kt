package com.assavendo.machinevirtuel.runtime

import android.content.Context
import com.assavendo.machinevirtuel.security.PackageSignatureVerifier
import com.assavendo.machinevirtuel.security.SecurityGuards
import com.assavendo.machinevirtuel.vm.*
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

data class VmPackageManifest(
    val formatVersion: Int,
    val vmId: String,
    val name: String,
    val architecture: String,
    val diskFormat: String,
    val diskSizeBytes: Long,
    val firmware: String,
    val backendPreference: String,
    val memoryMb: Int,
    val vcpus: Int,
    val sha256Disk: String,
    val createdAt: Long,
)

object VmPackageManager {
    private const val FORMAT_VERSION = 1

    fun export(context: Context, vm: VmConfig, output: File, includeSnapshots: Boolean = false): File {
        require(vm.state != VmState.RUNNING && vm.state != VmState.STARTING) {
            "Export sécurisé exige une VM arrêtée."
        }
        val disk = File(vm.diskPath)
        require(disk.isFile) { "Disque VM introuvable." }
        val firmware = File(vm.firmwarePath)
        val manifest = VmPackageManifest(
            FORMAT_VERSION, vm.id, vm.name, vm.arch.name, vm.diskFormat.name,
            disk.length(), vm.firmwarePath, vm.backend.name, vm.ramMb, vm.vCpu,
            ImageVerifier.sha256(disk), vm.createdAt,
        )
        output.parentFile?.mkdirs()
        ZipOutputStream(output.outputStream().buffered(256 * 1024)).use { zip ->
            putJson(zip, "manifest.json", JSONObject().apply {
                put("format_version", manifest.formatVersion)
                put("vm_id", manifest.vmId)
                put("name", manifest.name)
                put("architecture", manifest.architecture)
                put("disk_format", manifest.diskFormat)
                put("disk_size_bytes", manifest.diskSizeBytes)
                put("firmware", manifest.firmware)
                put("backend_preference", manifest.backendPreference)
                put("memory_mb", manifest.memoryMb)
                put("vcpus", manifest.vcpus)
                put("sha256_disk", manifest.sha256Disk)
                put("created_at", manifest.createdAt)
            })
            putFile(zip, "disk/" + disk.name, disk)
            if (firmware.isFile) putFile(zip, "firmware/nvram.fd", firmware)
            if (includeSnapshots) {
                val root = File(context.filesDir, "snapshots/" + vm.id)
                root.walkTopDown().filter { it.isFile }.forEach {
                    val rel = root.toPath().relativize(it.toPath()).toString()
                    putFile(zip, "snapshots/" + rel, it)
                }
            }
        }
        return output
    }

    fun importPackage(context: Context, packageFile: File): VmConfig {
        require(packageFile.isFile) { "Package VM introuvable." }
        val signature = File(packageFile.absolutePath + ".sig")
        val publicKey = File(packageFile.absolutePath + ".pub")
        require(signature.isFile == publicKey.isFile) { "Package signature pair is incomplete." }
        if (signature.isFile && publicKey.isFile) {
            require(PackageSignatureVerifier.verifyFile(packageFile, signature, publicKey)) {
                "Signature Ed25519 du package invalide."
            }
        }
        val staging = File(context.cacheDir, "vm-import-" + UUID.randomUUID()).apply { mkdirs() }
        try {
            unzipSafe(packageFile, staging)
            val manifestFile = SecurityGuards.canonicalChild(staging, File(staging, "manifest.json"))
            val manifest = JSONObject(manifestFile.readText())
            require(manifest.optInt("format_version", -1) == FORMAT_VERSION) {
                "Version de package VM non supportée."
            }
            val diskEntries = staging.resolve("disk").listFiles()?.filter { it.isFile }.orEmpty()
            require(diskEntries.size == 1) { "Package VM: disque unique attendu." }
            val disk = diskEntries.first()
            check(ImageVerifier.verify(disk, manifest.getString("sha256_disk"))) {
                "SHA-256 du disque importé invalide."
            }

            val id = UUID.randomUUID().toString()
            val targetDir = File(context.filesDir, "vms/" + id).apply { mkdirs() }
            val targetDisk = File(targetDir, disk.name)
            disk.copyTo(targetDisk, overwrite = true)
            val nvram = staging.resolve("firmware/nvram.fd")
            val targetVars = File(targetDir, "nvram.fd")
            if (nvram.isFile) nvram.copyTo(targetVars, overwrite = true)

            val vm = VmConfig(
                id = id,
                name = manifest.optString("name", "Imported VM"),
                guestOs = "Imported",
                arch = GuestArch.valueOf(manifest.optString("architecture", GuestArch.ARM64.name)),
                ramMb = manifest.optInt("memory_mb", 2048),
                vCpu = manifest.optInt("vcpus", 2),
                diskPath = targetDisk.absolutePath,
                diskFormat = DiskFormat.valueOf(manifest.optString("disk_format", DiskFormat.RAW.name)),
                firmwarePath = targetVars.absolutePath,
                backend = BackendKind.QEMU_TCG,
                mode = VmMode.AUTO,
            )
            VmStore(context).save(VmStore(context).list().toMutableList().apply { add(vm) })
            return vm
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun unzipSafe(zipFile: File, target: File) {
        ZipInputStream(zipFile.inputStream().buffered(128 * 1024)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = SecurityGuards.canonicalChild(target, File(target, entry.name))
                if (entry.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    out.outputStream().buffered(128 * 1024).use { zip.copyTo(it, 128 * 1024) }
                }
                zip.closeEntry()
            }
        }
    }

    private fun putJson(zip: ZipOutputStream, path: String, json: JSONObject) =
        putBytes(zip, path, json.toString(2).toByteArray(Charsets.UTF_8))

    private fun putBytes(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun putFile(zip: ZipOutputStream, path: String, file: File) {
        zip.putNextEntry(ZipEntry(path))
        file.inputStream().buffered(256 * 1024).use { it.copyTo(zip, 256 * 1024) }
        zip.closeEntry()
    }
}
