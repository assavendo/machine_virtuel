package com.assavendo.machinevirtuel.hypervisor

import com.assavendo.machinevirtuel.host.HostCapabilities
import com.assavendo.machinevirtuel.runtime.CrosvmCommandBuilder
import com.assavendo.machinevirtuel.runtime.QemuCommandBuilder
import com.assavendo.machinevirtuel.runtime.RuntimePaths
import com.assavendo.machinevirtuel.vm.BackendKind
import com.assavendo.machinevirtuel.vm.GuestArch
import com.assavendo.machinevirtuel.vm.VmConfig
import java.io.File

class QemuBackend(private val backendKind: BackendKind) : HypervisorBackend {
    override val kind: BackendKind = backendKind

    override fun detect(host: HostCapabilities, vm: VmConfig): Boolean =
        when (backendKind) {
            BackendKind.KVM -> host.kvm && vm.arch == GuestArch.ARM64
            BackendKind.QEMU_TCG -> true
            else -> false
        }

    override fun capabilities(host: HostCapabilities): BackendCapabilities =
        BackendCapabilities(
            hardwareAccelerated = backendKind == BackendKind.KVM,
            fullSystem = true,
            gpu = true,
            network = true,
            audio = false,
        )

    override fun launchSpec(
        paths: RuntimePaths,
        vm: VmConfig,
        qmp: File,
        serial: File,
        log: File,
    ): BackendLaunchSpec = BackendLaunchSpec(
        command = QemuCommandBuilder.build(paths, vm.copy(backend = backendKind), qmp, serial, log),
        environment = mapOf(
            "LD_LIBRARY_PATH" to paths.libDir.absolutePath,
            "QEMU_AUDIO_DRV" to "none",
        ),
        qmpSocket = qmp,
    )

    override fun supportsPauseResume() = true
}

class CrosvmBackend(private val backendKind: BackendKind) : HypervisorBackend {
    override val kind: BackendKind = backendKind

    override fun detect(host: HostCapabilities, vm: VmConfig): Boolean =
        when (backendKind) {
            BackendKind.GUNYAH -> host.gunyah && vm.arch == GuestArch.ARM64
            BackendKind.GENIEZONE -> host.genieZone && vm.arch == GuestArch.ARM64
            BackendKind.HALLA -> host.halla && vm.arch == GuestArch.ARM64
            BackendKind.KVM -> host.kvm && vm.arch == GuestArch.ARM64
            else -> false
        }

    override fun capabilities(host: HostCapabilities): BackendCapabilities =
        BackendCapabilities(
            hardwareAccelerated = true,
            fullSystem = true,
            // crosvm is now wired as a real launch backend, but PhoenixVM does not yet
            // provision an Android display/audio/network bridge for it. Reporting those
            // capabilities as enabled here would be misleading.
            gpu = false,
            network = false,
            audio = false,
        )

    override fun launchSpec(
        paths: RuntimePaths,
        vm: VmConfig,
        qmp: File,
        serial: File,
        log: File,
    ): BackendLaunchSpec = BackendLaunchSpec(
        command = CrosvmCommandBuilder.build(paths, vm.copy(backend = backendKind)),
        environment = mapOf("LD_LIBRARY_PATH" to paths.libDir.absolutePath),
        qmpSocket = null,
    )
}

class HypervisorManager(private val host: HostCapabilities) {
    private val backends: List<HypervisorBackend> = listOf(
        CrosvmBackend(BackendKind.GUNYAH),
        CrosvmBackend(BackendKind.GENIEZONE),
        CrosvmBackend(BackendKind.HALLA),
        CrosvmBackend(BackendKind.KVM),
        QemuBackend(BackendKind.KVM),
        QemuBackend(BackendKind.QEMU_TCG),
    )

    fun availableFor(vm: VmConfig): List<HypervisorBackend> =
        backends.filter { it.detect(host, vm) }

    fun capabilities(kind: BackendKind): BackendCapabilities =
        backends.firstOrNull { it.kind == kind }?.capabilities(host)
            ?: BackendCapabilities(hardwareAccelerated = false)
}
