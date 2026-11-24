package com.assavendo.machinevirtuel.hypervisor

import com.assavendo.machinevirtuel.host.HostCapabilities
import com.assavendo.machinevirtuel.runtime.RuntimePaths
import com.assavendo.machinevirtuel.vm.BackendKind
import com.assavendo.machinevirtuel.vm.VmConfig
import java.io.File

data class BackendCapabilities(
    val hardwareAccelerated: Boolean,
    val fullSystem: Boolean = true,
    val gpu: Boolean = false,
    val network: Boolean = false,
    val audio: Boolean = false,
)

data class BackendLaunchSpec(
    val command: List<String>,
    val environment: Map<String, String>,
    val qmpSocket: File?,
    val controlSocket: File?,
)

interface HypervisorBackend {
    val kind: BackendKind

    fun detect(host: HostCapabilities, vm: VmConfig): Boolean
    fun capabilities(host: HostCapabilities): BackendCapabilities

    fun createVm(vm: VmConfig) {}
    fun startVm(vm: VmConfig): BackendLaunchSpec =
        throw UnsupportedOperationException("startVm must be implemented by the concrete backend")
    fun stopVm(vm: VmConfig): Boolean = false
    fun pauseVm(vm: VmConfig): Boolean = false
    fun resumeVm(vm: VmConfig): Boolean = false
    fun setMemory(vm: VmConfig, memoryMb: Int): Boolean = false
    fun setVcpus(vm: VmConfig, vcpus: Int): Boolean = false
    fun attachDevice(vm: VmConfig, descriptor: String): Boolean = false
    fun detachDevice(vm: VmConfig, descriptor: String): Boolean = false
    fun metrics(vm: VmConfig): Map<String, Number> = emptyMap()
    fun destroyVm(vm: VmConfig) {}

    fun launchSpec(
        paths: RuntimePaths,
        vm: VmConfig,
        qmp: File,
        serial: File,
        log: File,
    ): BackendLaunchSpec

    fun supportsPauseResume(): Boolean = false
    fun supportsResize(): Boolean = false
}
