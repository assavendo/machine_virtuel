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
        val host = capabilities()
        val admission = ResourceAdmissionController.check(config, current, host)
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

            // The initial cold snapshot must finish (or fail closed to "no auto snapshot")
            // before the VM record becomes visible and startable in the dashboard.
            if (config.autoSnapshotOnInstall) {
                runCatching {
                    SnapshotManager.createColdSnapshot(context.filesDir, config, "install")
                    SnapshotManager.enforceRetention(context.filesDir, config)
                }.onFailure {
                    config.autoSnapshotOnInstall = false
                }
            }

            store.insertUnique(config) { latest ->
                val latestAdmission = ResourceAdmissionController.check(config, latest, host)
                check(latestAdmission.allowed) {
                    "Création refusée après revalidation des ressources : " + latestAdmission.reason
                }
            }
            persisted = true
            return config
        } catch (error: Throwable) {
            if (!persisted) {
                if (vmDir.exists()) vmDir.deleteRecursively()
                File(context.filesDir, "snapshots/" + config.id).deleteRecursively()
            }
            throw error
        }
    }

    fun clone(id: String, newName: String): VmConfig {
        val host = capabilities()
        return store.mutate { items ->
            val source = items.firstOrNull { it.id == id } ?: error("VM introuvable.")
            require(source.state == VmState.OFF) { "Le clonage exige une VM dont l'état OFF est confirmé." }
            val cloneName = newName.trim().ifBlank { source.name + " Clone" }
            require(cloneName.length in 1..80) { "Le nom du clone doit contenir entre 1 et 80 caractères." }
            require(items.none { it.name.equals(cloneName, ignoreCase = true) }) {
                "Une VM porte déjà ce nom ; les noms doivent être uniques."
            }
            val clone = source.copy(
                id = UUID.randomUUID().toString(),
                name = cloneName,
                createdAt = System.currentTimeMillis(),
                lastStartedAt = null,
                state = VmState.OFF,
            )
            val sourceDir = File(context.filesDir, "vms/" + source.id).canonicalFile
            val sourceDisk = File(source.diskPath).canonicalFile
            require(sourceDisk.isFile && sourceDisk.canRead()) { "Disque source introuvable ou illisible." }
            val allowedRoot = File(context.filesDir, "vms").canonicalFile
            require(sourceDir.toPath().startsWith(allowedRoot.toPath()) && sourceDir.isDirectory) {
                "Répertoire source de VM invalide."
            }
            val targetDir = File(context.filesDir, "vms/" + clone.id).canonicalFile
            require(targetDir.toPath().startsWith(allowedRoot.toPath()) && targetDir != allowedRoot) {
                "Répertoire cible du clone invalide."
            }
            check(!targetDir.exists() && targetDir.mkdirs()) {
                "Impossible de créer le répertoire temporaire du clone."
            }
            val copied = mutableMapOf<String, String>()
            fun copyManagedArtifact(path: String, targetName: String): String {
                if (path.isBlank()) return ""
                val sourceFile = File(path).canonicalFile
                if (!sourceFile.exists()) return path
                if (!sourceFile.toPath().startsWith(sourceDir.toPath())) return path
                copied[sourceFile.absolutePath]?.let { return it }
                val target = File(targetDir, targetName).canonicalFile
                require(target.toPath().startsWith(targetDir.toPath())) { "Chemin d'artefact du clone invalide." }
                if (sourceFile.isDirectory) {
                    check(sourceFile.copyRecursively(target, overwrite = false)) { "Impossible de copier un artefact du clone." }
                } else sourceFile.copyTo(target, overwrite = false)
                copied[sourceFile.absolutePath] = target.absolutePath
                return target.absolutePath
            }
            try {
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
                val admission = ResourceAdmissionController.check(clone, items, host)
                require(admission.allowed) { "Clonage refusé par le contrôle de ressources : " + admission.reason }
                items.add(clone)
                clone
            } catch (failure: Throwable) {
                targetDir.deleteRecursively()
                throw failure
            }
        }
    }

    fun setIso(id: String, path: String): VmConfig? = store.update(id) {
        require(it.state == VmState.OFF) { "Une ISO ne peut être modifiée que lorsque la VM est à l'état OFF confirmé." }
        val iso = File(path).canonicalFile
        require(iso.isFile && iso.canRead() && iso.length() > 0L) { "L'image ISO est absente, vide ou illisible." }
        it.isoPath = iso.absolutePath
    }

    fun updateAudio(
        id: String,
        enabled: Boolean,
        muted: Boolean,
        frequencyHz: Int,
        channels: Int,
        bufferMs: Int,
    ): VmConfig? = store.update(id) {
        require(it.state == VmState.OFF) {
            "La configuration audio exige l'état OFF confirmé ; ERROR peut encore correspondre à un processus vivant."
        }
        require(frequencyHz in 8_000..192_000)
        require(channels in 1..8)
        require(bufferMs in 2..500)
        it.audioEnabled = enabled
        it.audioMuted = muted
        it.audioFrequencyHz = frequencyHz
        it.audioChannels = channels
        it.audioBufferMs = bufferMs
    }

    fun attachUsb(id: String, config: UsbPassthroughConfig): VmConfig? =
        store.update(id) {
            require(it.state == VmState.OFF) { "Le passthrough USB ne peut être modifié que lorsque la VM est à l'état OFF confirmé." }
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
            require(it.state == VmState.OFF) { "Le passthrough USB ne peut être modifié que lorsque la VM est à l'état OFF confirmé." }
            it.usbPassthrough = it.usbPassthrough.filterNot { existing ->
                existing.vendorId == config.vendorId &&
                    existing.productId == config.productId &&
                    existing.serialNumber == config.serialNumber
            }
        }

    fun markStarting(id: String): VmConfig? = store.update(id) { it.state = VmState.STARTING }
    fun stop(id: String) = store.update(id) { it.state = VmState.OFF }

    fun delete(id: String) {
        val removed = store.mutate { items ->
            val vm = items.firstOrNull { it.id == id } ?: return@mutate null
            // ERROR can still represent a live process whose termination was not confirmed.
            // Only OFF is safe for destructive removal; RECOVERY/ERROR must be reconciled first.
            require(vm.state == VmState.OFF) {
                "La VM doit être arrêtée proprement et confirmée OFF avant sa suppression."
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
            items.removeAll { it.id == id }
            vm
        } ?: return
        cache.clear(removed.id)
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
        val sampledMetrics = metrics.sample()
        val host = capabilities()
        return store.mutate { items ->
            val vm = items.firstOrNull { it.id == id } ?: error("VM introuvable.")
            require(vm.state == VmState.OFF) { "Appliquer une optimisation de ressources exige l'état OFF confirmé." }
            val decision = optimizer.decide(vm, sampledMetrics)
            val admission = ResourceAdmissionController.check(
                vm.copy(ramMb = decision.ramMb, vCpu = decision.vCpu),
                items,
                host,
            )
            require(admission.allowed) {
                "Optimisation refusée par le contrôle de ressources : " + admission.reason
            }
            vm.vCpu = decision.vCpu
            vm.ramMb = decision.ramMb
            vm.monitoringIntervalMs = decision.monitoringIntervalMs
            vm
        }
    }

    fun optimize(id: String) = advisor.advise(
        store.list().firstOrNull { it.id == id } ?: error("VM introuvable."),
        metrics.sample(),
        capabilities().availableMemoryMb,
        selectBackend(id).selected.label,
        gpuAccelerationVerified = false,
        hostVulkanAvailable = capabilities().vulkan,
    )

    fun snapshot(id: String, label: String): File =
        store.mutate { items ->
            val vm = items.firstOrNull { it.id == id } ?: error("VM introuvable.")
            require(vm.state == VmState.OFF) { "Une capture à froid exige l'état OFF confirmé." }
            val dir = SnapshotManager.createColdSnapshot(context.filesDir, vm, label)
            SnapshotManager.enforceRetention(context.filesDir, vm)
            dir
        }

    fun restoreSnapshot(id: String, snapshotDir: File) {
        store.mutate { items ->
            val vm = items.firstOrNull { it.id == id } ?: error("VM introuvable.")
            require(vm.state == VmState.OFF) { "Une restauration à froid exige l'état OFF confirmé." }
            SnapshotManager.restoreColdSnapshot(vm, snapshotDir)
        }
    }

    fun exportVm(id: String, output: File, includeSnapshots: Boolean = true): File =
        store.mutate { items ->
            val vm = items.firstOrNull { it.id == id } ?: error("VM introuvable.")
            require(vm.state == VmState.OFF) { "L'export sécurisé exige l'état OFF confirmé." }
            VmPackageManager.export(context, vm, output, includeSnapshots)
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
