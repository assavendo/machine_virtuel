package com.assavendo.machinevirtuel.performance

import com.assavendo.machinevirtuel.vm.PerformancePreset
import com.assavendo.machinevirtuel.vm.VmConfig
import com.assavendo.machinevirtuel.vm.VmState
import kotlin.math.max
import kotlin.math.min

data class VmScheduleDecision(
    val vmId: String,
    val recommendedVcpus: Int,
    val cpuClass: PerformanceClass,
    val priority: Int,
    val reason: String,
)

class GlobalVmScheduler {
    fun schedule(vms: List<VmConfig>, topology: CpuTopology, availableMemoryMb: Long): List<VmScheduleDecision> {
        if (vms.isEmpty()) return emptyList()
        val active = vms.filter { it.state == VmState.RUNNING || it.state == VmState.STARTING }
        val topologyKnown = topology.performanceCores.isNotEmpty() || topology.efficiencyCores.isNotEmpty()
        val logicalBudget = max(1, topology.logicalCpuCount)
        val performanceBudget = if (topologyKnown) topology.performanceCores.size else logicalBudget
        val totalBudget = if (topologyKnown) {
            max(1, topology.performanceCores.size + topology.efficiencyCores.size)
        } else {
            logicalBudget
        }
        val ramBudget = max(1L, availableMemoryMb)
        val targetRamPerVm = max(512L, ramBudget / max(1, active.size))
        val ordered = active.sortedWith(
            compareByDescending<VmConfig> { it.vmPriority.coerceIn(0, 100) }
                .thenByDescending {
                    when (it.performancePreset) {
                        PerformancePreset.ULTRA_PERFORMANCE, PerformancePreset.GAMING -> 4
                        PerformancePreset.PERFORMANCE, PerformancePreset.DEVELOPMENT -> 3
                        PerformancePreset.SERVER -> 2
                        else -> 1
                    }
                }
                .thenBy { it.id }
        )
        val baseShare = max(1, totalBudget / max(1, ordered.size))
        val remainder = if (ordered.size <= totalBudget) totalBudget % ordered.size else 0

        return ordered.mapIndexed { index, vm ->
            val share = baseShare + if (index < remainder) 1 else 0
            val granted = min(vm.vCpu.coerceAtLeast(1), share).coerceAtLeast(1)
            val cpuClass = if (index < performanceBudget) PerformanceClass.PERFORMANCE else PerformanceClass.EFFICIENCY
            val ramPenalty = if (vm.ramMb.toLong() > targetRamPerVm) {
                "RAM élevée par rapport à la marge disponible."
            } else {
                "Marge mémoire acceptable."
            }
            val effectivePriority = vm.vmPriority.coerceIn(0, 100)
            VmScheduleDecision(vm.id, granted, cpuClass, effectivePriority, ramPenalty)
        }
    }
}
