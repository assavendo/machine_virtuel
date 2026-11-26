package com.assavendo.machinevirtuel.performance

import java.util.concurrent.ConcurrentHashMap

object VmMetricsRegistry {
    private val histories = ConcurrentHashMap<String, MetricsHistory>()

    fun record(
        vmId: String,
        host: HostMetrics,
        fps: Double? = null,
        vCpuUtilization: Double? = null,
        effectiveVcpus: Int? = null,
        cacheHitRate: Double? = null,
        translationMs: Double? = null,
        audioUnderruns: Long? = null,
        audioLatencyConfiguredMs: Double? = null,
        guestCpuMetrics: GuestCpuMetrics? = null,
    ) {
        val history = histories.getOrPut(vmId) { MetricsHistory() }
        history.add(
            VmMetricsSample(
                timestampMs = host.timestampMs,
                fps = fps,
                cpuGuestPercent = guestCpuMetrics?.cpuBusyPercent,
                guestCpuIoWaitPercent = guestCpuMetrics?.ioWaitPercent,
                guestCpuInterruptPercent = guestCpuMetrics?.interruptPercent,
                guestCpuTimeDeltaMs = guestCpuMetrics?.guestCpuTimeDeltaMs,
                guestCpuIntervalMs = guestCpuMetrics?.intervalMs,
                guestCpuCoreCount = guestCpuMetrics?.coreCount,
                cpuHostPercent = host.processCpuPercent,
                vCpuUtilization = vCpuUtilization,
                vCpuCountRuntime = effectiveVcpus,
                ramGuestMb = null,
                ramHostMb = host.processRssMb,
                temperatureC = host.skinTemperatureC,
                cpuFrequencyKHz = host.cpuFrequencyKHz,
                throttling = host.isThrottling,
                ioReadBytesPerSec = host.ioReadBytesPerSec,
                ioWriteBytesPerSec = host.ioWriteBytesPerSec,
                processIoReadBytesPerSec = host.processIoReadBytesPerSec,
                processIoWriteBytesPerSec = host.processIoWriteBytesPerSec,
                systemCpuIoWaitPercent = host.systemCpuIoWaitPercent,
                sampleIntervalMs = host.sampleIntervalMs,
                networkRxBytesPerSec = host.networkRxBytesPerSec,
                networkTxBytesPerSec = host.networkTxBytesPerSec,
                audioUnderruns = audioUnderruns,
                audioLatencyConfiguredMs = audioLatencyConfiguredMs,
                cacheHitRate = cacheHitRate,
                translationMs = translationMs,
            )
        )
    }

    fun recordFps(vmId: String, fps: Double) {
        histories.getOrPut(vmId) { MetricsHistory() }.updateLatest { it.copy(fps = fps.coerceAtLeast(0.0)) }
    }

    fun last(vmId: String, minutes: Int = 1): List<VmMetricsSample> =
        histories[vmId]?.last(minutes).orEmpty()

    fun windows(vmId: String): List<MetricsWindow> =
        histories[vmId]?.windows().orEmpty()

    fun clear(vmId: String) {
        histories.remove(vmId)
    }

    fun clearAll() {
        histories.clear()
    }
}
