package com.assavendo.machinevirtuel.vm

import android.content.Context
import com.assavendo.machinevirtuel.cache.TranslationCacheManager
import com.assavendo.machinevirtuel.host.HostCapabilityScanner
import com.assavendo.machinevirtuel.performance.HostMetricsCollector
import com.assavendo.machinevirtuel.performance.PerformanceAdvisor
import com.assavendo.machinevirtuel.runtime.DiagnosticExporter
import com.assavendo.machinevirtuel.runtime.DiskManager
import com.assavendo.machinevirtuel.runtime.ImageVerifier
import com.assavendo.machinevirtuel.runtime.RecoveryManager
import com.assavendo.machinevirtuel.runtime.RecoveryPlan
import com.assavendo.machinevirtuel.runtime.RuntimeDiagnostics
import com.assavendo.machinevirtuel.runtime.RuntimeInstaller
import com.assavendo.machinevirtuel.runtime.RuntimePaths
import com.assavendo.machinevirtuel.runtime.SnapshotManager
import com.assavendo.machinevirtuel.runtime.VmPackageManager
import java.io.File
import java.util.UUID

class VmManager(private val context: Context) {
    private val store = VmStore(context)
    private val scanner = HostCapabilityScanner(context)
    private val cache = TranslationCacheManager(context)
    private val orchestrator = VmOrchestrator()
    private val metrics = HostMetricsCollector(context)
    private val advisor = PerformanceAdvisor()

    fun list(): List<VmConfig> = store.list()
    fun capabilities() = scanner.scan()
    fun cacheStats() = cache.stats()

    fun createDefault(name: String = "Linux ARM64 VM"): VmConfig {
        val caps = capabilities()
        val seed = VmConfig(name = name, guestOs = "Linux ARM64", arch = GuestArch.ARM64)
        val decision = orchestrator.choose(seed, caps)
        val budget = com.assavendo.machinevirtuel.performance.ResourceAdmissionController
            .check(seed.copy(ramMb = 2048), store.list(), caps)
        val selectedRam = budget.recommendedRamMb
        require(selectedRam >= 512) {
            "Mémoire disponible insuffisante pour créer une VM par défaut en sécurité."
        }
        val vm = seed.copy(
            ramMb = selectedRam,
            vCpu = minOf(4, caps.cpuCount.coerceAtLeast(1)),
            mode = VmMode.AUTO,
            backend = decision.selected,
        )
        DiskManager.ensureVmStorage(context.filesDir, vm)
        store.save(store.list().toMutableList().apply { add(vm) })
        return vm
    }

    fun create(config: VmConfig): VmConfig {
        val issues = VmSpecValidator.validate(config).filter { it.fatal }
        check(issues.isEmpty()) { issues.joinToString("; ") { it.field + ": " + it.message } }
        val runtime = if (config.diskFormat == DiskFormat.RAW) null else RuntimeInstaller.ensure(context)
        DiskManager.ensureVmStorage(context.filesDir, config, runtime)
        store.save(store.list().toMutableList().apply { add(config) })
        return config
    }

    fun clone(id: String, newName: String): VmConfig {
        val source = store.list().firstOrNull { it.id == id } ?: error("VM introuvable.")
        require(source.state == VmState.OFF) { "Le clonage exige une VM arrêtée." }
        val clone = source.copy(
            id = UUID.randomUUID().toString(),
            name = newName.ifBlank { source.name + " Clone" },
            createdAt = System.currentTimeMillis(),
            lastStartedAt = null,
            state = VmState.OFF,
        )
        val sourceDir = File(context.filesDir, "vms/" + source.id).canonicalFile
        val sourceDisk = File(source.diskPath).canonicalFile
        require(sourceDisk.isFile) { "Disque source introuvable." }
        val allowedRoot = File(context.filesDir, "vms").canonicalFile
        require(sourceDir.toPath().startsWith(allowedRoot.toPath()) && sourceDir.isDirectory) {
            "Répertoire source de VM invalide."
        }

        val targetDir = File(context.filesDir, "vms/" + clone.id).apply { mkdirs() }
        val copied = mutableMapOf<String, String>()

        fun copyManagedArtifact(path: String, targetName: String): String {
            if (path.isBlank()) return ""
            val source = File(path).canonicalFile
            if (!source.exists()) return path
            if (!source.toPath().startsWith(sourceDir.toPath())) return path
            val existing = copied[source.absolutePath]
            if (existing != null) return existing
            val target = File(targetDir, targetName)
            if (source.isDirectory) {
                source.copyRecursively(target, overwrite = false)
            } else {
                source.copyTo(target, overwrite = false)
            }
            copied[source.absolutePath] = target.absolutePath
            return target.absolutePath
        }

        val targetDisk = File(targetDir, "disk." + DiskManager.extension(source.diskFormat))
        sourceDisk.copyTo(targetDisk, overwrite = false)
        clone.diskPath = targetDisk.absolutePath
        clone.firmwarePath = copyManagedArtifact(source.firmwarePath, "nvram.fd")
        clone.kernelPath = copyManagedArtifact(source.kernelPath, "kernel.Image")
        clone.initrdPath = copyManagedArtifact(source.initrdPath, "initrd")
        clone.dtbPath = copyManagedArtifact(source.dtbPath, "guest.dtb")
        clone.androidSystemPath = copyManagedArtifact(source.androidSystemPath, "android-system.img")
        clone.androidVendorPath = copyManagedArtifact(source.androidVendorPath, "android-vendor.img")
        clone.androidUserdataPath = copyManagedArtifact(source.androidUserdataPath, "android-userdata.img")
        clone.tpmStatePath = copyManagedArtifact(source.tpmStatePath, "tpm-state")
        store.save(store.list().toMutableList().apply { add(clone) })
        return clone
    }

    fun setIso(id: String, path: String): VmConfig? = store.update(id) { it.isoPath = path }
    fun markStarting(id: String): VmConfig? = store.update(id) { it.state = VmState.STARTING }
    fun stop(id: String) = store.update(id) { it.state = VmState.OFF }

    fun delete(id: String) {
        val vm = store.list().firstOrNull { it.id == id } ?: return
        require(vm.state == VmState.OFF || vm.state == VmState.ERROR) {
            "Arrêtez la VM avant de la supprimer."
        }
        val managedDir = File(context.filesDir, "vms/" + id).canonicalFile
        val allowedRoot = File(context.filesDir, "vms").canonicalFile
        require(managedDir.toPath().startsWith(allowedRoot.toPath()) && managedDir != allowedRoot) {
            "Répertoire de VM non sûr."
        }
        val disk = vm.diskPath.takeIf { it.isNotBlank() }?.let(::File)?.canonicalFile
        val firmware = vm.firmwarePath.takeIf { it.isNotBlank() }?.let(::File)?.canonicalFile
        val tpm = vm.tpmStatePath.takeIf { it.isNotBlank() }?.let(::File)?.canonicalFile
        fun deleteManaged(file: File?) {
            if (file != null && file.toPath().startsWith(managedDir.toPath())) {
                file.deleteRecursively()
            }
        }
        // Do not delete user-selected files outside PhoenixVM's private VM directory.
        deleteManaged(disk)
        deleteManaged(firmware)
        deleteManaged(tpm)
        managedDir.deleteRecursively()
        File(context.filesDir, "snapshots/" + id).deleteRecursively()
        File(context.filesDir, "logs/" + id).deleteRecursively()
        cache.clear(id)
        store.save(store.list().filterNot { it.id == id })
    }

    fun selectBackend(id: String): OrchestrationDecision {
        val vm = store.list().firstOrNull { it.id == id } ?: error("VM introuvable.")
        return orchestrator.choose(vm, capabilities())
    }

    fun optimize(id: String) = advisor.advise(
        store.list().firstOrNull { it.id == id } ?: error("VM introuvable."),
        metrics.sample(),
        capabilities().availableMemoryMb,
        selectBackend(id).selected.label,
        capabilities().vulkan,
    )

    fun snapshot(id: String, label: String): File {
        val vm = store.list().firstOrNull { it.id == id } ?: error("VM introuvable.")
        val dir = SnapshotManager.createColdSnapshot(context.filesDir, vm, label)
        SnapshotManager.enforceRetention(context.filesDir, vm)
        return dir
    }

    fun restoreSnapshot(id: String, snapshotDir: File) {
        val vm = store.list().firstOrNull { it.id == id } ?: error("VM introuvable.")
        SnapshotManager.restoreColdSnapshot(vm, snapshotDir)
    }

    fun exportVm(id: String, output: File, includeSnapshots: Boolean = true): File {
        val vm = store.list().firstOrNull { it.id == id } ?: error("VM introuvable.")
        return VmPackageManager.export(context, vm, output, includeSnapshots)
    }

    fun importVm(packageFile: File): VmConfig = VmPackageManager.importPackage(context, packageFile)

    fun recover(id: String, errorMessage: String): RecoveryPlan? {
        val vm = store.list().firstOrNull { it.id == id } ?: return null
        return RecoveryManager.plan(context.filesDir, vm, errorMessage)
    }

    fun exportDiagnostics(output: File): File = DiagnosticExporter.export(context, output)
    fun verifyImage(file: File, sha256: String): Boolean = ImageVerifier.verify(file, sha256)
    fun runtimeRoot(): String = RuntimePaths(context).root.absolutePath
    fun runtimeReady(): Boolean = runCatching { RuntimeInstaller.ensure(context); true }.getOrDefault(false)
    fun runtimeDiagnostics() = RuntimeDiagnostics.run(context)
}
