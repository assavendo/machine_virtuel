package com.assavendo.machinevirtuel.vm

import android.content.Context
import android.os.StatFs
import com.assavendo.machinevirtuel.cache.TranslationCacheManager
import com.assavendo.machinevirtuel.host.HostCapabilityScanner
import com.assavendo.machinevirtuel.performance.HostMetricsCollector
import com.assavendo.machinevirtuel.performance.PerformanceAdvisor
import com.assavendo.machinevirtuel.performance.PerformanceOptimizer
import com.assavendo.machinevirtuel.performance.ResourceAdmissionController
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
    private val optimizer = PerformanceOptimizer()

    fun list(): List<VmConfig> = store.list()
    fun capabilities() = scanner.scan()
    fun cacheStats() = cache.stats()

    fun createDefault(name: String = "Linux ARM64 VM"): VmConfig {
        val caps = capabilities()
        val seed = VmConfig(name = name, guestOs = "Linux ARM64", arch = GuestArch.ARM64)
        val decision = orchestrator.choose(seed, caps)
        val budget = ResourceAdmissionController
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
        vm.autoSnapshotOnInstall = false
        return create(vm)
    }

    fun create(config: VmConfig): VmConfig {
        config.name = config.name.trim()
        require(config.name.length in 1..80) { "Le nom de la VM doit contenir entre 1 et 80 caractères." }
        require(runCatching { UUID.fromString(config.id) }.isSuccess) {
            "L'identifiant de la VM doit être un UUID valide."
        }
        require(config.state == VmState.OFF) { "Une nouvelle VM doit être créée à l'état OFF." }

        val current = store.list()
        require(current.none { it.id == config.id }) { "Une VM utilise déjà cet identifiant." }
        require(current.none { it.name.equals(config.name, ignoreCase = true) }) {
            "Une VM porte déjà ce nom ; les noms doivent être uniques."
        }
        val issues = VmSpecValidator.validate(config).filter { it.fatal }
        check(issues.isEmpty()) {
            issues.joinToString("; ") { it.field + ": " + it.message }
        }
        val admission = ResourceAdmissionController.check(config, current, capabilities())
        check(admission.allowed) {
            "Création refusée par l'admission des ressources : " + admission.reason
        }

        val vmRoot = File(context.filesDir, "vms").canonicalFile
        val vmDir = File(vmRoot, config.id).canonicalFile
        require(vmDir.toPath().startsWith(vmRoot.toPath()) && vmDir != vmRoot) {
            "Le chemin de stockage de la VM est invalide."
        }
        if (config.diskPath.isBlank()) {
            val requiredBytes = Math.multiplyExact(config.diskSizeGiB, 1024L * 1024L * 1024L)
            val availableBytes = StatFs(context.filesDir.absolutePath).availableBytes
            require(requiredBytes <= availableBytes) {
                "Espace de stockage insuffisant pour créer le disque virtuel : " +
                    config.diskSizeGiB + " Gio demandés, " +
                    (availableBytes / (1024L * 1024L)) + " Mio disponibles."
            }
        }

        val suppliedArtifacts = listOf(
            config.diskPath, config.isoPath, config.kernelPath, config.initrdPath,
            config.dtbPath, config.androidSystemPath, config.androidVendorPath,
            config.androidUserdataPath, config.tpmStatePath,
        ).filter { it.isNotBlank() }
        suppliedArtifacts.forEach { path ->
            val artifact = File(path).canonicalFile
            require(artifact.toPath().startsWith(vmDir.toPath())) {
                "Les médias de création doivent être copiés dans le stockage privé de cette VM."
            }
        }

        val runtime = if (config.diskFormat != DiskFormat.RAW && config.diskPath.isBlank()) {
            RuntimeInstaller.ensure(context)
        } else {
            null
        }
        var persisted = false
        try {
            DiskManager.ensureVmStorage(context.filesDir, config, runtime)
            store.save(current.toMutableList().apply { add(config) })
            persisted = true
            if (config.autoSnapshotOnInstall) {
                runCatching {
                    SnapshotManager.createColdSnapshot(context.filesDir, config, "install")
                    SnapshotManager.enforceRetention(context.filesDir, config)
                }.onFailure {
                    store.update(config.id) { it.autoSnapshotOnInstall = false }
                }
            }
            return config
        } catch (error: Throwable) {
            if (!persisted && vmDir.exists()) vmDir.deleteRecursively()
            throw error
        }
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

    fun updateAudio(
        id: String,
        enabled: Boolean,
        muted: Boolean,
        frequencyHz: Int,
        channels: Int,
        bufferMs: Int,
    ): VmConfig? {
        val vm = store.list().firstOrNull { it.id == id } ?: error("VM introuvable.")
        require(vm.state == VmState.OFF || vm.state == VmState.ERROR) {
            "La configuration audio doit être modifiée lorsque la VM est arrêtée."
        }
        require(frequencyHz in 8_000..192_000)
        require(channels in 1..8)
        require(bufferMs in 2..500)
        return store.update(id) {
            it.audioEnabled = enabled
            it.audioMuted = muted
            it.audioFrequencyHz = frequencyHz
            it.audioChannels = channels
            it.audioBufferMs = bufferMs
        }
    }

    fun attachUsb(id: String, config: UsbPassthroughConfig): VmConfig? =
        store.update(id) {
            require(it.usbPassthrough.none { existing ->
                existing.vendorId == config.vendorId &&
                    existing.productId == config.productId &&
                    existing.serialNumber == config.serialNumber
            }) { "Ce périphérique USB est déjà attaché à cette VM." }
            require(it.usbPassthrough.size < 8) {
                "Une VM ne peut pas attacher plus de 8 périphériques USB hôte."
            }
            it.usbPassthrough = it.usbPassthrough + config
        }

    fun detachUsb(id: String, config: UsbPassthroughConfig): VmConfig? =
        store.update(id) {
            it.usbPassthrough = it.usbPassthrough.filterNot { existing ->
                existing.vendorId == config.vendorId &&
                    existing.productId == config.productId &&
                    existing.serialNumber == config.serialNumber
            }
        }
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

    fun optimizationDecision(id: String): com.assavendo.machinevirtuel.performance.OptimizationDecision {
        val vm = store.list().firstOrNull { it.id == id } ?: error("VM introuvable.")
        return optimizer.decide(vm, metrics.sample())
    }

    fun applyOptimization(id: String): VmConfig {
        val vm = store.list().firstOrNull { it.id == id } ?: error("VM introuvable.")
        require(vm.state == VmState.OFF || vm.state == VmState.ERROR) {
            "Appliquer une optimisation de ressources exige une VM arrêtée."
        }
        val decision = optimizer.decide(vm, metrics.sample())
        val admission = ResourceAdmissionController.check(
            vm.copy(ramMb = decision.ramMb, vCpu = decision.vCpu),
            store.list(),
            capabilities(),
        )
        require(admission.allowed) {
            "Optimisation refusée par le contrôle de ressources : " + admission.reason
        }
        return store.update(id) {
            it.vCpu = decision.vCpu
            it.ramMb = decision.ramMb
            it.monitoringIntervalMs = decision.monitoringIntervalMs
        } ?: error("VM introuvable.")
    }

    fun optimize(id: String) = advisor.advise(
        store.list().firstOrNull { it.id == id } ?: error("VM introuvable."),
        metrics.sample(),
        capabilities().availableMemoryMb,
        selectBackend(id).selected.label,
        gpuAccelerationVerified = false,
        hostVulkanAvailable = capabilities().vulkan,
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
