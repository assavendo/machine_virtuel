package com.assavendo.machinevirtuel.performance

import com.assavendo.machinevirtuel.vm.PerformancePreset
import com.assavendo.machinevirtuel.vm.VmConfig
import com.assavendo.machinevirtuel.vm.VmState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class VmScheduleDecision(
    val vmId: String,
    val recommendedVcpus: Int,
    val cpuClass: PerformanceClass,
    val priority: Int,
    val reason: String,
    /** One online, process-allowed logical CPU per guest vCPU, when topology is known. */
    val assignedCpuIds: List<Int> = emptyList(),
    /** Android/Linux nice priority mapped conservatively to the range -4..10. */
    val threadPriority: Int = 0,
)

class GlobalVmScheduler {
    fun schedule(
        vms: List<VmConfig>,
        topology: CpuTopology,
        availableMemoryMb: Long,
    ): List<VmScheduleDecision> {
        if (vms.isEmpty()) return emptyList()
        val active = vms.filter { it.state == VmState.RUNNING || it.state == VmState.STARTING }
        if (active.isEmpty()) return emptyList()

        val cpuIds = topology.allowedCpuIds.distinct().ifEmpty { topology.cores.map { it.id }.distinct() }
        val cpuBudget = cpuIds.size.coerceAtLeast(1)
        val ramBudget = max(1L, availableMemoryMb)
        val targetRamPerVm = max(512L, ramBudget / active.size)
        val ordered = active.sortedWith(
            compareByDescending<VmConfig> { it.vmPriority.coerceIn(0, 100) }
                .thenByDescending { presetWeight(it.performancePreset) }
                .thenBy { it.id }
        )

        // Every active VM keeps at least one vCPU. Remaining physical slots are assigned
        // by priority, bounded by each VM's requested count and the process cpuset.
        val allocations = LinkedHashMap<String, Int>()
        ordered.forEach { allocations[it.id] = 1 }
        var freeSlots = (cpuBudget - ordered.size).coerceAtLeast(0)
        ordered.forEach { vm ->
            if (freeSlots == 0) return@forEach
            val requested = vm.vCpu.coerceAtLeast(1).coerceAtMost(cpuBudget)
            val extra = min((requested - 1).coerceAtLeast(0), freeSlots)
            allocations[vm.id] = 1 + extra
            freeSlots -= extra
        }

        val coresById = topology.cores.associateBy { it.id }
        val usage = cpuIds.associateWith { 0 }.toMutableMap()
        val performanceIds = cpuIds.filter { coresById[it]?.performanceClass == PerformanceClass.PERFORMANCE }
        val efficiencyIds = cpuIds.filter { coresById[it]?.performanceClass == PerformanceClass.EFFICIENCY }
        val unknownIds = cpuIds.filter { coresById[it]?.performanceClass == PerformanceClass.UNKNOWN }

        return ordered.map { vm ->
            val wantsPerformance = vm.vmPriority >= 60 || vm.performancePreset in setOf(
                PerformancePreset.PERFORMANCE,
                PerformancePreset.ULTRA_PERFORMANCE,
                PerformancePreset.GAMING,
                PerformancePreset.DEVELOPMENT,
            )
            val pool = if (wantsPerformance) {
                (performanceIds + unknownIds + efficiencyIds).ifEmpty { cpuIds }
            } else {
                (efficiencyIds + unknownIds + performanceIds).ifEmpty { cpuIds }
            }
            val assigned = buildList {
                repeat(allocations.getValue(vm.id)) {
                    val chosen = pool.minWithOrNull(
                        compareBy<Int> { usage[it] ?: 0 }
                            .thenBy { pool.indexOf(it) }
                    ) ?: return@repeat
                    add(chosen)
                    usage[chosen] = (usage[chosen] ?: 0) + 1
                }
            }
            val cpuClass = assigned.firstOrNull()?.let { coresById[it]?.performanceClass }
                ?: PerformanceClass.UNKNOWN
            val ramMessage = if (vm.ramMb.toLong() > targetRamPerVm) {
                "RAM élevée par rapport à la marge disponible."
            } else {
                "Marge mémoire acceptable."
            }
            val affinityMessage = if (assigned.isEmpty()) {
                "Topologie CPU indisponible : affinité non imposée."
            } else {
                "Cœurs autorisés affectés : " + assigned.joinToString(",") + "."
            }
            val priority = vm.vmPriority.coerceIn(0, 100)
            VmScheduleDecision(
                vmId = vm.id,
                recommendedVcpus = allocations.getValue(vm.id),
                cpuClass = cpuClass,
                priority = priority,
                reason = ramMessage + " " + affinityMessage,
                assignedCpuIds = assigned,
                threadPriority = threadPriorityFor(priority),
            )
        }
    }

    private fun presetWeight(preset: PerformancePreset): Int = when (preset) {
        PerformancePreset.ULTRA_PERFORMANCE, PerformancePreset.GAMING -> 4
        PerformancePreset.PERFORMANCE, PerformancePreset.DEVELOPMENT -> 3
        PerformancePreset.SERVER -> 2
        else -> 1
    }

    companion object {
        /** Higher VM priority maps to a more favourable thread nice value. */
        fun threadPriorityFor(vmPriority: Int): Int {
            val bounded = vmPriority.coerceIn(0, 100)
            return (10.0 - (bounded / 100.0) * 14.0).roundToInt().coerceIn(-4, 10)
        }
    }
}
