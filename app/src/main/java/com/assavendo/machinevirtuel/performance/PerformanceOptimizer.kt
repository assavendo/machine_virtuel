package com.assavendo.machinevirtuel.performance

import com.assavendo.machinevirtuel.vm.PerformancePreset
import com.assavendo.machinevirtuel.vm.PerformanceProfile
import com.assavendo.machinevirtuel.vm.VmConfig
import kotlin.math.max
import kotlin.math.min

data class OptimizationDecision(
    val vCpu: Int,
    val ramMb: Int,
    val gpuQuality: Int,
    val prefetchAggressiveness: Int,
    val cpuShare: Int,
    val monitoringIntervalMs: Long,
    val reason: String,
)

class PerformanceOptimizer {
    fun decide(vm: VmConfig, metrics: HostMetrics): OptimizationDecision {
        val topology = DeviceTopology.read()
        val physicalBudget = max(1, topology.cores.size)
        val profile = PerformanceProfilePolicy.resolve(vm)
        val cpuLimit = min(vm.vCpu, physicalBudget)
        val safeCpu = when (vm.profile) {
            PerformanceProfile.STABLE -> min(cpuLimit, max(1, physicalBudget / 2))
            PerformanceProfile.AUTO, PerformanceProfile.PERFORMANCE, PerformanceProfile.EXTREME -> cpuLimit
        }

        return when {
            metrics.isThrottling -> OptimizationDecision(
                vCpu = max(1, safeCpu - 1),
                ramMb = (vm.ramMb * 0.85).toInt().coerceAtLeast(768),
                gpuQuality = min(profile.gpuQuality, 70),
                prefetchAggressiveness = min(profile.prefetch, 40),
                cpuShare = min(profile.cpuShare, 85),
                monitoringIntervalMs = min(profile.monitoringIntervalMs, 500),
                reason = "Réduction soutenue : throttling thermique détecté.",
            )
            metrics.availableMemoryMb < 768L -> OptimizationDecision(
                vCpu = max(1, safeCpu - 1),
                ramMb = (vm.ramMb * 0.75).toInt().coerceAtLeast(768),
                gpuQuality = min(profile.gpuQuality, 65),
                prefetchAggressiveness = min(profile.prefetch, 30),
                cpuShare = min(profile.cpuShare, 80),
                monitoringIntervalMs = 500,
                reason = "Pression mémoire Android élevée.",
            )
            vm.profile == PerformanceProfile.EXTREME || vm.performancePreset == PerformancePreset.ULTRA_PERFORMANCE -> OptimizationDecision(
                vCpu = safeCpu,
                ramMb = vm.ramMb,
                gpuQuality = 100,
                prefetchAggressiveness = 100,
                cpuShare = 100,
                monitoringIntervalMs = 250,
                reason = "Profil ultra performance : ressources maximisées sous réserve des limites du host.",
            )
            else -> OptimizationDecision(
                vCpu = safeCpu,
                ramMb = vm.ramMb,
                gpuQuality = profile.gpuQuality,
                prefetchAggressiveness = profile.prefetch,
                cpuShare = profile.cpuShare,
                monitoringIntervalMs = profile.monitoringIntervalMs,
                reason = "Profil adaptatif sans alerte thermique ou mémoire.",
            )
        }
    }
}
