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

    @Test fun interactiveVmNeverSelectsHeadlessOnlyVendorBackends() {
        val caps = HostCapabilities(
            supportedAbis = listOf("arm64-v8a"), cpuCount = 8, maxMemoryMb = 8192,
            vulkan = true, kvm = false, gunyah = true, genieZone = true, halla = true,
            avfHint = false, avfAvailable = false, rootedHint = false
        )
        val vm = VmConfig(name = "interactive", arch = GuestArch.ARM64)
        val decision = VmOrchestrator().choose(vm, caps)
        assertEquals(BackendKind.QEMU_TCG, decision.selected)
        assertTrue(decision.candidates.none {
            it.backend in setOf(BackendKind.GUNYAH, BackendKind.GENIEZONE, BackendKind.HALLA)
        })
    }

    @Test fun headlessVmCanSelectVendorBackendOnlyOutsideNat() {
        val caps = HostCapabilities(
            supportedAbis = listOf("arm64-v8a"), cpuCount = 8, maxMemoryMb = 8192,
            vulkan = true, kvm = false, gunyah = true, genieZone = false, halla = false,
            avfHint = false, avfAvailable = false, rootedHint = false
        )
        val vm = VmConfig(
            name = "headless", arch = GuestArch.ARM64, headless = true,
            networkMode = NetworkMode.TAP, tapName = "tap0"
        )
        assertEquals(BackendKind.GUNYAH, VmOrchestrator().choose(vm, caps).selected)
    }

    @Test fun avfHintDoesNotSelectAnUnimplementedLaunchBackend() {
        val vm = VmConfig(name = "avf-hint", arch = GuestArch.ARM64)
        val decision = VmOrchestrator().choose(vm, host(false, avf = true))
        assertEquals(BackendKind.QEMU_TCG, decision.selected)
        assertTrue(decision.warnings.any { it.contains("AVF") })
    }
}
