package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.performance.HostMetrics
import com.assavendo.machinevirtuel.performance.PerformanceAdvisor
import com.assavendo.machinevirtuel.vm.VmConfig
import kotlin.test.Test
import kotlin.test.assertTrue

class PerformanceAdvisorTest {
    private fun metrics() = HostMetrics(
        timestampMs = 1L,
        processCpuPercent = 10.0,
        systemCpuPercent = 20.0,
        processRssMb = 100,
        availableMemoryMb = 4096,
        totalMemoryMb = 8192,
        thermalStatus = 0,
        skinTemperatureC = 35.0,
        isThrottling = false,
    )

    @Test
    fun hostVulkanDoesNotCountAsVmGpuAcceleration() {
        val advice = PerformanceAdvisor().advise(
            vm = VmConfig(name = "gpu"),
            metrics = metrics(),
            hostRamMb = 8192,
            backendLabel = "QEMU TCG",
            gpuAccelerationVerified = false,
            hostVulkanAvailable = true,
        )
        assertTrue(advice.recommendedGpuMode.contains("non confirmée", ignoreCase = true))
        assertTrue(advice.actions.any { it.contains("Vulkan", ignoreCase = true) })
    }
}
