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
        val vm = seed.copy(
            ramMb = 2048.coerceAtMost((caps.availableMemoryMb * 70 / 100).toInt().coerceAtLeast(1024)),
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
        DiskManager.ensureVmStorage(context.filesDir, config)
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
        val sourceDisk = File(source.diskPath)
        require(sourceDisk.isFile) { "Disque source introuvable." }
        val targetDir = File(context.filesDir, "vms/" + clone.id).apply { mkdirs() }
        val targetDisk = File(targetDir, "disk." + DiskManager.extension(source.diskFormat))
        sourceDisk.copyTo(targetDisk, overwrite = false)
        clone.diskPath = targetDisk.absolutePath
        clone.firmwarePath = File(targetDir, "nvram.fd").absolutePath
        File(source.firmwarePath).takeIf { it.isFile }?.copyTo(File(clone.firmwarePath), overwrite = true)
        store.save(store.list().toMutableList().apply { add(clone) })
        return clone
    }

    fun setIso(id: String, path: String): VmConfig? = store.update(id) { it.isoPath = path }
    fun markStarting(id: String): VmConfig? = store.update(id) { it.state = VmState.STARTING }
    fun stop(id: String) = store.update(id) { it.state = VmState.OFF }

    fun delete(id: String) {
        val vm = store.list().firstOrNull { it.id == id }
        store.save(store.list().filterNot { it.id == id })
        cache.clear(id)
        vm?.let {
            File(it.diskPath).takeIf { file -> file.isFile }?.delete()
            File(it.firmwarePath).takeIf { file -> file.isFile }?.delete()
            File(it.tpmStatePath).takeIf { file -> file.isFile }?.delete()
        }
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
