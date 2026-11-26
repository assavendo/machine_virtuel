package com.assavendo.machinevirtuel.performance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VmMetricsRegistryTest {
    @Test
    fun recordsBackendProcessIoAndKeepsUnavailableGuestMetricsUnknown() {
        VmMetricsRegistry.clearAll()
        try {
            VmMetricsRegistry.record(
                vmId = "vm-cpu-telemetry",
                host = HostMetrics(
                    timestampMs = System.currentTimeMillis(),
                    processCpuPercent = 37.5,
                    systemCpuPercent = 42.0,
                    processRssMb = 768,
                    availableMemoryMb = 4096,
                    totalMemoryMb = 8192,
                    thermalStatus = 0,
                    skinTemperatureC = 36.5,
                    isThrottling = false,
                    processIoReadBytesPerSec = 12_000,
                    processIoWriteBytesPerSec = 34_000,
                    systemCpuIoWaitPercent = 7.5,
                    sampleIntervalMs = 1000,
                ),
                effectiveVcpus = 2,
            )

            val sample = VmMetricsRegistry.last("vm-cpu-telemetry").single()
            assertEquals(37.5, sample.cpuHostPercent)
            assertEquals(2, sample.vCpuCountRuntime)
            assertEquals(12_000L, sample.processIoReadBytesPerSec)
            assertEquals(34_000L, sample.processIoWriteBytesPerSec)
            assertEquals(7.5, sample.systemCpuIoWaitPercent)
            assertEquals(1000L, sample.sampleIntervalMs)
            assertNull(sample.cpuGuestPercent)
            assertNull(sample.ramGuestMb)

            val minute = VmMetricsRegistry.windows("vm-cpu-telemetry").first()
            assertEquals(12_000.0, minute.processIoReadBytesPerSecAvg)
            assertEquals(34_000.0, minute.processIoWriteBytesPerSecAvg)
            assertEquals(7.5, minute.systemCpuIoWaitPercentAvg)
        } finally {
            VmMetricsRegistry.clearAll()
        }
    }

    @Test
    fun recordsOnlyRealGuestAgentCpuWaitAndInterruptSamples() {
        VmMetricsRegistry.clearAll()
        try {
            VmMetricsRegistry.record(
                vmId = "vm-linux-agent",
                host = HostMetrics(
                    timestampMs = System.currentTimeMillis(),
                    processCpuPercent = 12.0,
                    systemCpuPercent = 20.0,
                    processRssMb = 512,
                    availableMemoryMb = 4096,
                    thermalStatus = 0,
                    skinTemperatureC = null,
                    isThrottling = false,
                ),
                guestCpuMetrics = GuestCpuMetrics(
                    cpuBusyPercent = 52.17,
                    ioWaitPercent = 8.69,
                    interruptPercent = 4.34,
                    guestCpuTimeDeltaMs = 60,
                    intervalMs = 1000,
                    coreCount = 1,
                ),
            )

            val sample = VmMetricsRegistry.last("vm-linux-agent").single()
            assertEquals(52.17, sample.cpuGuestPercent)
            assertEquals(8.69, sample.guestCpuIoWaitPercent)
            assertEquals(4.34, sample.guestCpuInterruptPercent)
            assertEquals(60L, sample.guestCpuTimeDeltaMs)
            assertEquals(1000L, sample.guestCpuIntervalMs)
            assertEquals(1, sample.guestCpuCoreCount)

            val window = VmMetricsRegistry.windows("vm-linux-agent").first()
            assertEquals(8.69, window.guestCpuIoWaitAvgPercent)
            assertEquals(4.34, window.guestCpuInterruptAvgPercent)
            assertEquals(60.0, window.guestCpuTimeDeltaAvgMs)
        } finally {
            VmMetricsRegistry.clearAll()
        }
    }

}
