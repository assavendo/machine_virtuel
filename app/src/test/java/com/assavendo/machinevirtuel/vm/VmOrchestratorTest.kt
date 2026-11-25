package com.assavendo.machinevirtuel.vm

import com.assavendo.machinevirtuel.host.HostCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VmOrchestratorTest {
    private fun host(
        kvm: Boolean = false,
        gunyah: Boolean = false,
        genieZone: Boolean = false,
        halla: Boolean = false,
        avf: Boolean = false,
        thermal: Int = 0,
    ) = HostCapabilities(
        manufacturer = "Test",
        model = "Phoenix",
        soc = "TestSoC",
        apiLevel = 36,
        kernelVersion = "test",
        cpuCount = 8,
        totalMemoryMb = 8192,
        availableMemoryMb = 6144,
        supportedAbis = listOf("arm64-v8a"),
        vulkan = true,
        openGlEs = true,
        usbHost = true,
        kvm = kvm,
        gunyah = gunyah,
        genieZone = genieZone,
        halla = halla,
        avfAvailable = avf,
        avfHint = avf,
        rootedHint = false,
        selinuxEnforcing = true,
        thermalStatus = thermal,
    )

    private fun vm(arch: GuestArch = GuestArch.ARM64) =
        VmConfig(
            name = "test",
            guestOs = if (arch == GuestArch.ARM64) "Linux ARM64" else "Linux x86_64",
            arch = arch,
        )

    @Test
    fun arm64_prefers_available_hardware_backend() {
        val result = VmOrchestrator().choose(vm(), host(kvm = true, gunyah = true))
        assertEquals(BackendKind.KVM, result.selected)
    }

    @Test
    fun x86_remains_full_system_tcg() {
        val result = VmOrchestrator().choose(vm(GuestArch.X86_64), host(kvm = true, gunyah = true))
        assertEquals(BackendKind.QEMU_TCG, result.selected)
        assertTrue(result.candidates.all { it.backend == BackendKind.QEMU_TCG })
    }

    @Test
    fun hot_host_keeps_a_safe_software_fallback() {
        val result = VmOrchestrator().choose(vm(), host(thermal = 5))
        assertEquals(BackendKind.QEMU_TCG, result.selected)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun expert_request_wins_only_when_available() {
        val requested = vm().copy(mode = VmMode.EXPERT, backend = BackendKind.GUNYAH)
        val result = VmOrchestrator().choose(requested, host(kvm = true, gunyah = false))
        assertEquals(BackendKind.KVM, result.selected)
        assertTrue(result.warnings.any { it.contains("indisponible", ignoreCase = true) })
    }
}
