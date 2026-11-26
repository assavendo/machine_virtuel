package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.host.HostCapabilities
import com.assavendo.machinevirtuel.vm.BackendKind
import com.assavendo.machinevirtuel.vm.BackendSelector
import com.assavendo.machinevirtuel.vm.GuestArch
import kotlin.test.Test
import kotlin.test.assertEquals

class BackendSelectorTest {
    private fun caps(
        kvm: Boolean,
        gunyah: Boolean = false,
        genieZone: Boolean = false,
        halla: Boolean = false,
    ) = HostCapabilities(
        supportedAbis = listOf("arm64-v8a"),
        cpuCount = 8,
        maxMemoryMb = 4096,
        vulkan = true,
        kvm = kvm,
        gunyah = gunyah,
        genieZone = genieZone,
        halla = halla,
        avfHint = false,
        rootedHint = false,
    )

    @Test
    fun arm64PrefersKvm() {
        assertEquals(BackendKind.KVM, BackendSelector.select(GuestArch.ARM64, caps(kvm = true)))
    }

    @Test
    fun x86FallsBackToQemu() {
        assertEquals(BackendKind.QEMU_TCG, BackendSelector.select(GuestArch.X86_64, caps(kvm = true)))
    }

    @Test
    fun arm64UsesGunyahForGenericHeadlessSelection() {
        assertEquals(
            BackendKind.GUNYAH,
            BackendSelector.select(GuestArch.ARM64, caps(kvm = false, gunyah = true)),
        )
    }
}
