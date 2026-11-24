package com.assavendo.machinevirtuel.vm

import com.assavendo.machinevirtuel.host.HostCapabilities

object BackendSelector {
    fun select(arch: GuestArch, caps: HostCapabilities): BackendKind {
        if (arch != GuestArch.ARM64) return BackendKind.QEMU_TCG
        return when {
            caps.kvm -> BackendKind.KVM
            caps.gunyah -> BackendKind.GUNYAH
            caps.genieZone -> BackendKind.GENIEZONE
            caps.halla -> BackendKind.HALLA
            else -> BackendKind.QEMU_TCG
        }
    }
}
