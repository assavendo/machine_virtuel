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
            )

            val sample = VmMetricsRegistry.last("vm-cpu-telemetry").single()
            assertEquals(37.5, sample.cpuHostPercent)
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
}
