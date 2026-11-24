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
)

interface HypervisorBackend {
    val kind: BackendKind
    fun detect(host: HostCapabilities, vm: VmConfig): Boolean
    fun capabilities(host: HostCapabilities): BackendCapabilities
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
