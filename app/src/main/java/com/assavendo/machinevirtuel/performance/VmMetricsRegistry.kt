package com.assavendo.machinevirtuel.performance

import java.util.concurrent.ConcurrentHashMap

object VmMetricsRegistry {
    private val histories = ConcurrentHashMap<String, MetricsHistory>()

    fun record(vmId: String, host: HostMetrics, translationMs: Double = 0.0, cacheHitRate: Double = 0.0) {
        val history = histories.getOrPut(vmId) { MetricsHistory() }
        history.add(
            VmMetricsSample(
                timestampMs = host.timestampMs,
                cpuHostPercent = host.processCpuPercent,
                ramHostMb = host.processRssMb,
                temperatureC = host.skinTemperatureC,
                cpuFrequencyKHz = host.cpuFrequencyKHz,
                throttling = host.isThrottling,
                ioReadBytesPerSec = host.ioReadBytesPerSec,
                ioWriteBytesPerSec = host.ioWriteBytesPerSec,
                networkRxBytesPerSec = host.networkRxBytesPerSec,
                networkTxBytesPerSec = host.networkTxBytesPerSec,
                translationMs = translationMs,
                cacheHitRate = cacheHitRate,
            )
        )
    }

    fun last(vmId: String, minutes: Int = 1): List<VmMetricsSample> =
        histories[vmId]?.last(minutes).orEmpty()

    fun clear(vmId: String) {
        histories.remove(vmId)
    }

    fun clearAll() {
        histories.clear()
    }
}
