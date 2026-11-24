package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.host.HostCapabilities
import com.assavendo.machinevirtuel.vm.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VmOrchestratorTest {
    private fun host(kvm: Boolean, avf: Boolean = false, thermal: Int = 0) = HostCapabilities(
        supportedAbis = listOf("arm64-v8a"), cpuCount = 8, maxMemoryMb = 8192,
        vulkan = true, kvm = kvm, gunyah = false, genieZone = false, halla = false,
        avfHint = avf, avfAvailable = avf, rootedHint = false, thermalStatus = thermal
    )

    @Test fun arm64PrefersHardwareBackend() {
        val vm = VmConfig(name = "a", arch = GuestArch.ARM64)
        assertEquals(BackendKind.KVM, VmOrchestrator().choose(vm, host(true)).selected)
    }

    @Test fun x86AlwaysUsesQemuTcg() {
        val vm = VmConfig(name = "x86", arch = GuestArch.X86_64)
        assertEquals(BackendKind.QEMU_TCG, VmOrchestrator().choose(vm, host(true)).selected)
    }

    @Test fun thermalStateProducesWarning() {
        val vm = VmConfig(name = "hot", arch = GuestArch.ARM64)
        assertTrue(VmOrchestrator().choose(vm, host(true, thermal = 5)).warnings.isNotEmpty())
    }
}
