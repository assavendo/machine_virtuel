package com.assavendo.machinevirtuel.performance

import com.assavendo.machinevirtuel.vm.PerformanceProfile
import com.assavendo.machinevirtuel.vm.VmConfig

data class OptimizationDecision(
    val vCpu: Int,
    val ramMb: Int,
    val gpuQuality: Int,
    val prefetchAggressiveness: Int,
    val reason: String,
)

class PerformanceOptimizer {
    fun decide(vm: VmConfig, metrics: HostMetrics): OptimizationDecision {
        val cpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val safeCpu = when (vm.profile) {
            PerformanceProfile.AUTO -> minOf(vm.vCpu, cpuCount)
            PerformanceProfile.STABLE -> minOf(vm.vCpu, maxOf(1, cpuCount / 2))
            PerformanceProfile.PERFORMANCE -> minOf(vm.vCpu, cpuCount)
            PerformanceProfile.EXTREME -> minOf(vm.vCpu, cpuCount)
        }

        return when {
            metrics.isThrottling -> OptimizationDecision(
                vCpu = maxOf(1, safeCpu - 1),
                ramMb = (vm.ramMb * 0.85).toInt().coerceAtLeast(768),
                gpuQuality = 70,
                prefetchAggressiveness = 40,
                reason = "Réduction soutenue : throttling thermique détecté.",
            )
            metrics.availableMemoryMb < 512L -> OptimizationDecision(
                vCpu = maxOf(1, safeCpu - 1),
                ramMb = (vm.ramMb * 0.75).toInt().coerceAtLeast(768),
                gpuQuality = 65,
                prefetchAggressiveness = 30,
                reason = "Pression mémoire Android élevée.",
            )
            vm.profile == PerformanceProfile.EXTREME -> OptimizationDecision(
                vCpu = safeCpu,
                ramMb = vm.ramMb,
                gpuQuality = 100,
                prefetchAggressiveness = 100,
                reason = "Profil EXTREME : ressources maximisées sous réserve des limites du host.",
            )
            else -> OptimizationDecision(
                vCpu = safeCpu,
                ramMb = vm.ramMb,
                gpuQuality = 90,
                prefetchAggressiveness = 70,
                reason = "Profil stable/performance sans alerte thermique.",
            )
        }
    }
}
