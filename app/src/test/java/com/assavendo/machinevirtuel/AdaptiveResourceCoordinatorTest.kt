package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.host.HostCapabilities
import com.assavendo.machinevirtuel.performance.AdaptiveResourceCoordinator
import com.assavendo.machinevirtuel.performance.CpuCore
import com.assavendo.machinevirtuel.performance.CpuTopology
import com.assavendo.machinevirtuel.performance.HostMetrics
import com.assavendo.machinevirtuel.performance.PerformanceClass
import com.assavendo.machinevirtuel.vm.PerformancePreset
import com.assavendo.machinevirtuel.vm.VmConfig
import com.assavendo.machinevirtuel.vm.VmState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptiveResourceCoordinatorTest {
    private fun host(available: Long = 4096) = HostCapabilities(
        supportedAbis = listOf("arm64-v8a"),
        cpuCount = 8,
        maxMemoryMb = 8192,
        totalMemoryMb = 8192,
        availableMemoryMb = available,
        vulkan = false,
        kvm = false,
        gunyah = false,
        genieZone = false,
        halla = false,
        avfHint = false,
        rootedHint = false,
        thermalStatus = 0,
    )

    private fun topology() = CpuTopology(
        cores = (0..7).map { CpuCore(it, 2_000_000, 100 + it, PerformanceClass.UNKNOWN) },
        performanceCores = (4..7).map { CpuCore(it, 2_500_000, 200, PerformanceClass.PERFORMANCE) },
        efficiencyCores = (0..3).map { CpuCore(it, 1_800_000, 100, PerformanceClass.EFFICIENCY) },
    )

    private fun metrics(
        available: Long = 4096,
        throttling: Boolean = false,
    ) = HostMetrics(
        timestampMs = 1L,
        processCpuPercent = 20.0,
        systemCpuPercent = 40.0,
        processRssMb = 500,
        availableMemoryMb = available,
        totalMemoryMb = 8192,
        thermalStatus = if (throttling) 5 else 0,
        skinTemperatureC = if (throttling) 48.0 else 35.0,
        isThrottling = throttling,
    )

    @Test
    fun thermalPressureRequestsConservativeRuntimePolicy() {
        val vm = VmConfig(
            name = "thermal",
            ramMb = 4096,
            vCpu = 4,
            balloonEnabled = true,
            performancePreset = PerformancePreset.ULTRA_PERFORMANCE,
            state = VmState.RUNNING,
        )
        val result = AdaptiveResourceCoordinator().decide(
            vm,
            listOf(vm),
            topology(),
            host(available = 1024),
            metrics(available = 1024, throttling = true),
        )
        assertTrue(result.recommendedVcpus <= vm.vCpu)
        assertTrue(result.recommendedRamMb <= vm.ramMb)
        assertTrue(result.reason.isNotBlank())
        assertEquals(result.recommendedVcpus != vm.vCpu, result.cpuChangeRequiresRestart)
    }

    @Test
    fun normalPressureDoesNotRequestBalloonUnnecessarily() {
        val vm = VmConfig(
            name = "normal",
            ramMb = 2048,
            vCpu = 2,
            balloonEnabled = true,
            state = VmState.RUNNING,
        )
        val result = AdaptiveResourceCoordinator().decide(
            vm,
            listOf(vm),
            topology(),
            host(),
            metrics(),
        )
        assertEquals(false, result.shouldRequestBalloon)
        assertEquals(vm.vCpu, result.configuredVcpus)
        assertEquals(vm.ramMb, result.configuredRamMb)
    }
}
