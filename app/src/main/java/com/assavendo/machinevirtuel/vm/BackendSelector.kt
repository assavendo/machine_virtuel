package com.assavendo.machinevirtuel.vm

import com.assavendo.machinevirtuel.host.HostCapabilities

object BackendSelector {
    /**
     * Legacy backend discovery has no display/network intent in its API. Model it as
     * a headless TAP-compatible guest so it may report vendor hypervisors whose
     * crosvm bridge is restricted to that mode. Real VM creation/startup must use
     * VmOrchestrator with the persisted VmConfig, which includes the actual mode.
     */
    fun select(arch: GuestArch, caps: HostCapabilities): BackendKind {
        val seed = VmConfig(
            name = "selection",
            arch = arch,
            headless = true,
            networkMode = NetworkMode.TAP,
            tapName = "tap0",
        )
        return VmOrchestrator().choose(seed, caps).selected
    }
}
