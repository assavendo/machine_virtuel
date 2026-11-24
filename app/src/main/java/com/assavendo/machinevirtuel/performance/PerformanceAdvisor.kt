package com.assavendo.machinevirtuel.performance

import com.assavendo.machinevirtuel.vm.PerformancePreset
import com.assavendo.machinevirtuel.vm.VmConfig
import kotlin.math.max
import kotlin.math.min

data class PerformanceAdvice(
    val currentBackend: String,
    val recommendedVcpus: Int,
    val recommendedRamMb: Int,
    val recommendedGpuMode: String,
    val thermalState: String,
    val actions: List<String>,
)

class PerformanceAdvisor {
    fun advise(vm: VmConfig, metrics: HostMetrics, hostRamMb: Long, backendLabel: String, gpuAccelerated: Boolean): PerformanceAdvice {
        val safeRam = min(vm.ramMb.toLong(), max(512L, hostRamMb * 70 / 100)).toInt()
        val thermal = when {
            metrics.thermalStatus >= 5 -> "critical"
            metrics.thermalStatus >= 3 -> "moderate"
            else -> "normal"
        }
        val vcpus = when {
            metrics.isThrottling -> max(1, vm.vCpu - 1)
            vm.performancePreset == PerformancePreset.BATTERY_SAVER -> max(1, vm.vCpu / 2)
            else -> vm.vCpu
        }
        val gpu = when {
            gpuAccelerated && thermal == "normal" -> "Vulkan/Gfxstream ou backend accéléré actuel"
            thermal == "critical" -> "Réduire la résolution / conserver le fallback"
            else -> "Mode compatible"
        }
        val actions = buildList {
            if (safeRam < vm.ramMb) add("Réduire la RAM guest à une valeur compatible avec la marge Android.")
            if (vcpus < vm.vCpu) add("Réduire temporairement le nombre de vCPU pour limiter le throttling.")
            if (!gpuAccelerated) add("Aucune accélération GPU confirmée : ne pas annoncer de GPU natif.")
            if (metrics.isThrottling) add("Le SoC est en throttling ; privilégier la performance soutenue.")
        }
        return PerformanceAdvice(backendLabel, vcpus, safeRam, gpu, thermal, actions)
    }
}
