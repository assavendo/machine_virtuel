package com.assavendo.machinevirtuel.vm

import com.assavendo.machinevirtuel.host.HostCapabilities

object BackendSelector {
    fun select(arch: GuestArch, caps: HostCapabilities): BackendKind {
        val seed = VmConfig(name = "selection", arch = arch)
        return VmOrchestrator().choose(seed, caps).selected
    }
}
