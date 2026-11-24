package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.performance.*
import com.assavendo.machinevirtuel.vm.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlobalVmSchedulerTest {
    @Test fun prioritizesGamingVm() {
        val a = VmConfig(name = "office", performancePreset = PerformancePreset.OFFICE, state = VmState.RUNNING)
        val b = VmConfig(name = "gaming", performancePreset = PerformancePreset.GAMING, state = VmState.RUNNING)
        val cores = CpuTopology(
            cores = (0..7).map { CpuCore(it, 2000000, 100 + it, PerformanceClass.UNKNOWN) },
            performanceCores = (4..7).map { CpuCore(it, 2500000, 200, PerformanceClass.PERFORMANCE) },
            efficiencyCores = (0..3).map { CpuCore(it, 1800000, 100, PerformanceClass.EFFICIENCY) }
        )
        val result = GlobalVmScheduler().schedule(listOf(a, b), cores, 4096)
        assertEquals(b.id, result.first().vmId)
        assertTrue(result.first().recommendedVcpus >= 1)
    }
    @Test fun respectsConfiguredVmPriority() {
        val low = VmConfig(
            name = "low",
            performancePreset = PerformancePreset.GAMING,
            vmPriority = 20,
            state = VmState.RUNNING,
        )
        val high = VmConfig(
            name = "high",
            performancePreset = PerformancePreset.OFFICE,
            vmPriority = 90,
            state = VmState.RUNNING,
        )
        val topology = CpuTopology(
            cores = (0..3).map { CpuCore(it, 2000000, 100, PerformanceClass.UNKNOWN) },
            performanceCores = (0..3).map { CpuCore(it, 2500000, 200, PerformanceClass.PERFORMANCE) },
            efficiencyCores = emptyList(),
        )
        val result = GlobalVmScheduler().schedule(listOf(low, high), topology, 4096)
        assertEquals(high.id, result.first().vmId)
        assertEquals(90, result.first().priority)
    }

}
