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
        val performanceBudget = max(1, topology.performanceCores.size)
        val efficiencyBudget = max(0, topology.efficiencyCores.size)
        val ramBudget = max(1L, availableMemoryMb)
        val targetRamPerVm = max(512L, ramBudget / max(1, active.size))
        return active
            .sortedWith(compareByDescending<VmConfig> {
                when (it.performancePreset) {
                    PerformancePreset.ULTRA_PERFORMANCE, PerformancePreset.GAMING -> 4
                    PerformancePreset.PERFORMANCE, PerformancePreset.DEVELOPMENT -> 3
                    PerformancePreset.SERVER -> 2
                    else -> 1
                }
            }.thenBy { it.id })
            .mapIndexed { index, vm ->
                val desired = min(vm.vCpu, max(1, performanceBudget + efficiencyBudget))
                val granted = min(desired, max(1, performanceBudget - index + efficiencyBudget))
                val cpuClass = if (index < performanceBudget) PerformanceClass.PERFORMANCE else PerformanceClass.EFFICIENCY
                val ramPenalty = if (vm.ramMb.toLong() > targetRamPerVm) "RAM élevée par rapport à la marge disponible." else "Marge mémoire acceptable."
                VmScheduleDecision(vm.id, max(1, granted), cpuClass, 100 - index, ramPenalty)
            }
    }
}
