package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.host.HostCapabilities
import com.assavendo.machinevirtuel.performance.ResourceAdmissionController
import com.assavendo.machinevirtuel.vm.VmConfig
import com.assavendo.machinevirtuel.vm.VmState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourceAdmissionTest {
    private fun host(total: Long, available: Long) = HostCapabilities(
        supportedAbis = listOf("arm64-v8a"),
        cpuCount = 8,
        maxMemoryMb = total,
        totalMemoryMb = total,
        availableMemoryMb = available,
        vulkan = false,
        kvm = false,
        gunyah = false,
        genieZone = false,
        halla = false,
        avfHint = false,
        rootedHint = false,
    )

    @Test fun admitsVmWhenPhysicalAndAggregateBudgetsCoverItsRam() {
        val vm = VmConfig(name = "admissible", ramMb = 2048)
        val result = ResourceAdmissionController.check(vm, emptyList(), host(8192, 4096))
        assertTrue(result.allowed)
        assertTrue(result.recommendedRamMb >= vm.ramMb)
    }

    @Test fun rejectsVmWhenAndroidReserveLeavesInsufficientPhysicalMemory() {
        val vm = VmConfig(name = "too-large", ramMb = 2048)
        val result = ResourceAdmissionController.check(vm, emptyList(), host(8192, 1400))
        assertFalse(result.allowed)
        assertTrue(result.recommendedRamMb < vm.ramMb)
    }

    @Test fun subtractsOtherActiveVmCommitmentsFromAggregateBudget() {
        val active = VmConfig(name = "active", ramMb = 2048).apply { state = VmState.RUNNING }
        val vm = VmConfig(name = "new", ramMb = 2048)
        val result = ResourceAdmissionController.check(vm, listOf(active), host(8192, 7000))
        assertTrue(result.allowed)
        assertFalse(
            ResourceAdmissionController.check(vm.copy(ramMb = 4096), listOf(active), host(8192, 7000)).allowed
        )
    }

    @Test fun excludesTheVmBeingRestartedFromItsOwnCommittedRam() {
        val vm = VmConfig(name = "restart", ramMb = 2048).apply { state = VmState.ERROR }
        assertTrue(ResourceAdmissionController.check(vm, listOf(vm), host(8192, 4096)).allowed)
    }
}
