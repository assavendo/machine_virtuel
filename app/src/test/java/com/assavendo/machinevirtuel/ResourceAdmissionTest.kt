package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.host.HostCapabilities
import com.assavendo.machinevirtuel.performance.ResourceAdmissionController
import com.assavendo.machinevirtuel.vm.VmConfig
import kotlin.test.Test
import kotlin.test.assertFalse

class ResourceAdmissionTest {
    @Test fun lowMemoryHostRejectsNewVm() {
        val host = HostCapabilities(
            supportedAbis = listOf("arm64-v8a"),
            cpuCount = 8,
            totalMemoryMb = 4096,
            availableMemoryMb = 100,
            vulkan = true,
            kvm = false,
            gunyah = false,
            genieZone = false,
            halla = false,
            avfHint = false,
            avfAvailable = false,
            rootedHint = false,
            thermalStatus = 0
        )
        val result = ResourceAdmissionController.check(
            VmConfig(name = "vm", ramMb = 2048),
            emptyList(),
            host
        )
        assertFalse(result.allowed)
    }
}
