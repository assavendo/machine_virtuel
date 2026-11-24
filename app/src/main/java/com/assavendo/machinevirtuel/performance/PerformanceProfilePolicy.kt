package com.assavendo.machinevirtuel.performance

import com.assavendo.machinevirtuel.vm.PerformancePreset
import com.assavendo.machinevirtuel.vm.PerformanceProfile
import com.assavendo.machinevirtuel.vm.VmConfig

data class ProfileTuning(
    val cpuShare: Int,
    val ramPercent: Int,
    val gpuQuality: Int,
    val prefetch: Int,
    val monitoringIntervalMs: Long,
    val allowBackground: Boolean,
)

object PerformanceProfilePolicy {
    fun resolve(vm: VmConfig): ProfileTuning {
        val base = when (vm.performancePreset) {
            PerformancePreset.COMPATIBILITY -> ProfileTuning(80, 50, 70, 30, 1000, true)
            PerformancePreset.BALANCED -> ProfileTuning(90, 60, 85, 50, 1000, true)
            PerformancePreset.PERFORMANCE -> ProfileTuning(100, 70, 95, 70, 500, true)
            PerformancePreset.ULTRA_PERFORMANCE -> ProfileTuning(100, 80, 100, 100, 250, false)
            PerformancePreset.BATTERY_SAVER -> ProfileTuning(60, 45, 60, 20, 2000, true)
            PerformancePreset.GAMING -> ProfileTuning(100, 75, 100, 90, 250, false)
            PerformancePreset.OFFICE -> ProfileTuning(75, 55, 75, 40, 1500, true)
            PerformancePreset.SERVER -> ProfileTuning(95, 75, 90, 85, 500, false)
            PerformancePreset.DEVELOPMENT -> ProfileTuning(95, 70, 90, 75, 500, true)
        }
        return when (vm.profile) {
            PerformanceProfile.STABLE -> base.copy(cpuShare = minOf(base.cpuShare, 85))
            PerformanceProfile.PERFORMANCE -> base.copy(cpuShare = maxOf(base.cpuShare, 95))
            PerformanceProfile.EXTREME -> base.copy(cpuShare = 100, gpuQuality = 100, prefetch = 100)
            else -> base
        }
    }
}
