package com.assavendo.machinevirtuel.performance

import com.assavendo.machinevirtuel.host.HostCapabilities
import com.assavendo.machinevirtuel.vm.VmConfig
import com.assavendo.machinevirtuel.vm.VmState
import kotlin.math.min

data class AdaptiveResourceDecision(
    val vmId: String,
    val recommendedVcpus: Int,
    val configuredVcpus: Int,
    val recommendedRamMb: Int,
    val configuredRamMb: Int,
    val cpuChangeRequiresRestart: Boolean,
    val shouldRequestBalloon: Boolean,
    val reason: String,
)

class AdaptiveResourceCoordinator(
    private val scheduler: GlobalVmScheduler = GlobalVmScheduler(),
    private val optimizer: PerformanceOptimizer = PerformanceOptimizer(),
) {
    fun decide(
        vm: VmConfig,
        allVms: List<VmConfig>,
        topology: CpuTopology,
        host: HostCapabilities,
        metrics: HostMetrics,
    ): AdaptiveResourceDecision {
        val schedule = scheduler
            .schedule(allVms, topology, host.availableMemoryMb)
            .firstOrNull { it.vmId == vm.id }

        val optimization = optimizer.decide(vm, metrics)
        val scheduledVcpus = schedule?.recommendedVcpus ?: vm.vCpu.coerceIn(1, host.cpuCount.coerceAtLeast(1))
        val recommendedVcpus = min(scheduledVcpus, optimization.vCpu).coerceAtLeast(1)
        val recommendedRam = min(vm.ramMb, optimization.ramMb).coerceAtLeast(256)
        val shouldBalloon = vm.balloonEnabled &&
            recommendedRam < vm.ramMb &&
            metrics.availableMemoryMb < maxOf(512L, host.availableMemoryMb / 8L)

        val reason = buildString {
            append(schedule?.reason ?: "Aucune décision multi-VM active pour cette VM.")
            append(" ")
            append(optimization.reason)
            if (recommendedVcpus < vm.vCpu) {
                append(" La réduction CPU sera prise en compte au prochain démarrage ; aucun hot-unplug non garanti n'est effectué.")
            }
            if (shouldBalloon) {
                append(" Une récupération mémoire guest peut être demandée via QMP.")
            }
        }

        return AdaptiveResourceDecision(
            vmId = vm.id,
            recommendedVcpus = recommendedVcpus,
            configuredVcpus = vm.vCpu,
            recommendedRamMb = recommendedRam,
            configuredRamMb = vm.ramMb,
            cpuChangeRequiresRestart = recommendedVcpus != vm.vCpu,
            shouldRequestBalloon = shouldBalloon,
            reason = reason.trim(),
        )
    }
}
