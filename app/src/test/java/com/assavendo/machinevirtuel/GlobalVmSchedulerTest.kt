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


    @Test
    fun assignmentsStayInsideOnlineAllowedCpusAndPreferPOrECoresByVmPolicy() {
        val high = VmConfig(
            name = "high",
            vCpu = 2,
            vmPriority = 90,
            performancePreset = PerformancePreset.ULTRA_PERFORMANCE,
            state = VmState.RUNNING,
        )
        val low = VmConfig(
            name = "low",
            vCpu = 2,
            vmPriority = 20,
            performancePreset = PerformancePreset.OFFICE,
            state = VmState.RUNNING,
        )
        val topology = CpuTopology(
            cores = listOf(
                CpuCore(1, 1_800_000, 100, PerformanceClass.EFFICIENCY),
                CpuCore(2, 2_800_000, 200, PerformanceClass.PERFORMANCE),
                CpuCore(3, 2_800_000, 200, PerformanceClass.PERFORMANCE),
                CpuCore(5, 1_800_000, 100, PerformanceClass.EFFICIENCY),
            ),
            performanceCores = listOf(
                CpuCore(2, 2_800_000, 200, PerformanceClass.PERFORMANCE),
                CpuCore(3, 2_800_000, 200, PerformanceClass.PERFORMANCE),
            ),
            efficiencyCores = listOf(
                CpuCore(1, 1_800_000, 100, PerformanceClass.EFFICIENCY),
                CpuCore(5, 1_800_000, 100, PerformanceClass.EFFICIENCY),
            ),
            allowedCpuIds = listOf(1, 2, 3, 5),
        )

        val decisions = GlobalVmScheduler().schedule(listOf(low, high), topology, 4096)
        val highDecision = decisions.first { it.vmId == high.id }
        val lowDecision = decisions.first { it.vmId == low.id }

        assertEquals(2, highDecision.recommendedVcpus)
        assertEquals(2, lowDecision.recommendedVcpus)
        assertTrue(highDecision.assignedCpuIds.all { it in setOf(2, 3) })
        assertTrue(lowDecision.assignedCpuIds.all { it in setOf(1, 5) })
        assertTrue(decisions.flatMap { it.assignedCpuIds }.all { it in topology.allowedCpuIds })
    }

    @Test
    fun keepsOneVcpuPerActiveVmAndMapsPriorityToUnprivilegedNiceValues() {
        val vms = (0..2).map { index ->
            VmConfig(
                name = "vm-$index",
                vCpu = 4,
                vmPriority = 100 - index * 40,
                state = VmState.RUNNING,
            )
        }
        val topology = CpuTopology(
            cores = listOf(
                CpuCore(3, 2_000_000, null, PerformanceClass.UNKNOWN),
                CpuCore(7, 2_000_000, null, PerformanceClass.UNKNOWN),
            ),
            performanceCores = emptyList(),
            efficiencyCores = emptyList(),
            allowedCpuIds = listOf(3, 7),
        )
        val decisions = GlobalVmScheduler().schedule(vms, topology, 4096)

        assertEquals(3, decisions.size)
        assertEquals(3, decisions.sumOf { it.recommendedVcpus })
        assertTrue(decisions.all { it.recommendedVcpus >= 1 })
        assertTrue(decisions.flatMap { it.assignedCpuIds }.all { it == 3 || it == 7 })
        assertEquals(10, GlobalVmScheduler.threadPriorityFor(0))
        assertEquals(0, GlobalVmScheduler.threadPriorityFor(100))
        assertTrue(GlobalVmScheduler.threadPriorityFor(80) < GlobalVmScheduler.threadPriorityFor(20))
        assertTrue(decisions.all { it.threadPriority in 0..10 })
    }


    @Test
    fun unknownCpuMaskProducesNoRecommendationAndPreservesRuntimeFallback() {
        val vm = VmConfig(name = "unknown-cpu-mask", vCpu = 4, state = VmState.STARTING)
        val result = GlobalVmScheduler().schedule(
            listOf(vm),
            CpuTopology(
                cores = emptyList(),
                performanceCores = emptyList(),
                efficiencyCores = emptyList(),
                allowedCpuIds = emptyList(),
            ),
            4096,
        )
        assertTrue(result.isEmpty())
    }

}
