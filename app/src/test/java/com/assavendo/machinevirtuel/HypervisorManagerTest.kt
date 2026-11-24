package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.host.HostCapabilities
import com.assavendo.machinevirtuel.hypervisor.CrosvmBackend
import com.assavendo.machinevirtuel.hypervisor.HypervisorManager
import com.assavendo.machinevirtuel.hypervisor.QemuBackend
import com.assavendo.machinevirtuel.vm.BackendKind
import com.assavendo.machinevirtuel.vm.GuestArch
import com.assavendo.machinevirtuel.vm.VmConfig
import com.assavendo.machinevirtuel.vm.NetworkMode
import com.assavendo.machinevirtuel.vm.VmSpecValidator
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HypervisorManagerTest {
    private val base = HostCapabilities(
        supportedAbis = listOf("arm64-v8a"),
        cpuCount = 8,
        maxMemoryMb = 6144,
        vulkan = true,
        kvm = false,
        gunyah = false,
        genieZone = false,
        halla = false,
        avfHint = false,
        rootedHint = false,
    )

    @Test
    fun tcgIsAlwaysAvailableAsSoftwareFallback() {
        val vm = VmConfig(name = "guest", arch = GuestArch.X86_64)
        assertTrue(HypervisorManager(base).availableFor(vm).any { it.kind == BackendKind.QEMU_TCG })
    }

    @Test
    fun kvmIsNotAdvertisedWithoutDeviceAccess() {
        val vm = VmConfig(name = "guest", arch = GuestArch.ARM64)
        assertFalse(QemuBackend(BackendKind.KVM).detect(base, vm))
        assertFalse(CrosvmBackend(BackendKind.KVM).detect(base, vm))
    }
}

    @Test
    fun crosvmRequiresHeadlessModeAndNonNatNetworking() {
        val vm = VmConfig(
            name = "guest",
            arch = GuestArch.ARM64,
            backend = BackendKind.KVM,
            headless = false,
            networkMode = NetworkMode.NAT,
        )
        val issues = VmSpecValidator.validate(vm)
        assertTrue(issues.any { it.field == "headless" && it.fatal })
        assertTrue(issues.any { it.field == "networkMode" && it.fatal })
    }

    @Test
    fun crosvmIsAllowedForHeadlessTapGuest() {
        val vm = VmConfig(
            name = "guest",
            arch = GuestArch.ARM64,
            backend = BackendKind.KVM,
            headless = true,
            networkMode = NetworkMode.TAP,
        )
        val issues = VmSpecValidator.validate(vm)
        assertFalse(issues.any { it.field == "headless" && it.fatal })
        assertFalse(issues.any { it.field == "networkMode" && it.fatal })
    }
