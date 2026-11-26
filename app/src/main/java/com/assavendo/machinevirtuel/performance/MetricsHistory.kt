package com.assavendo.machinevirtuel.performance

data class VmMetricsSample(
    val timestampMs: Long,
    val fps: Double? = null,
    val cpuGuestPercent: Double? = null,
    val guestCpuIoWaitPercent: Double? = null,
    val guestCpuInterruptPercent: Double? = null,
    val guestCpuTimeDeltaMs: Long? = null,
    val guestCpuIntervalMs: Long? = null,
    val cpuHostPercent: Double = 0.0,
    val vCpuUtilization: Double? = null,
    val ramGuestMb: Long? = null,
    val ramHostMb: Long = 0,
    val gpuPercent: Double? = null,
    val gpuMemoryMb: Long? = null,
    val temperatureC: Double? = null,
    val cpuFrequencyKHz: Long? = null,
    val throttling: Boolean = false,
    val ioReadBytesPerSec: Long = 0,
    val ioWriteBytesPerSec: Long = 0,
    val processIoReadBytesPerSec: Long? = null,
    val processIoWriteBytesPerSec: Long? = null,
    val systemCpuIoWaitPercent: Double? = null,
    val sampleIntervalMs: Long? = null,
    val networkRxBytesPerSec: Long = 0,
    val networkTxBytesPerSec: Long = 0,
    val audioUnderruns: Long? = null,
    val audioLatencyConfiguredMs: Double? = null,
    val cacheHitRate: Double? = null,
    val translationMs: Double? = null,
)

data class MetricsWindow(
    val minutes: Int,
    val samples: Int,
    val fpsAvg: Double?,
    val cpuHostAvg: Double,
    val cpuGuestAvg: Double?,
    val guestCpuIoWaitAvgPercent: Double?,
    val guestCpuInterruptAvgPercent: Double?,
    val guestCpuTimeDeltaAvgMs: Double?,
    val vCpuUtilizationAvg: Double?,
    val ramGuestAvgMb: Double?,
    val ramHostAvgMb: Double,
    val temperatureAvgC: Double?,
    val temperatureMaxC: Double?,
    val ioReadBytesPerSecAvg: Double,
    val ioWriteBytesPerSecAvg: Double,
    val processIoReadBytesPerSecAvg: Double?,
    val processIoWriteBytesPerSecAvg: Double?,
    val systemCpuIoWaitPercentAvg: Double?,
    val sampleIntervalMsAvg: Double?,
    val networkRxBytesPerSecAvg: Double,
    val networkTxBytesPerSecAvg: Double,
    val audioUnderrunsLast: Long?,
    val audioLatencyConfiguredMsAvg: Double?,
    val cacheHitRateAvg: Double?,
    val translationMsAvg: Double?,
    val throttlingSamples: Int,
)

class MetricsHistory(private val maxSamples: Int = 3600) {
    private val samples = ArrayDeque<VmMetricsSample>()

    @Synchronized fun add(sample: VmMetricsSample) {
        samples.addLast(sample)
        while (samples.size > maxSamples) samples.removeFirst()
    }

    @Synchronized fun last(minutes: Int): List<VmMetricsSample> {
        val threshold = System.currentTimeMillis() - minutes.coerceAtLeast(1) * 60_000L
        return samples.filter { it.timestampMs >= threshold }
    }

    @Synchronized fun all(): List<VmMetricsSample> = samples.toList()

    /** Fixed dashboard windows required by the specification: 1, 5 and 30 minutes. */
    @Synchronized fun windows(): List<MetricsWindow> = listOf(1, 5, 30).map { windowMinutes ->
        val values = last(windowMinutes)
        fun avgNullable(selector: (VmMetricsSample) -> Double?): Double? =
            values.mapNotNull(selector).takeIf { it.isNotEmpty() }?.average()

        fun avg(selector: (VmMetricsSample) -> Double): Double =
            if (values.isEmpty()) 0.0 else values.map(selector).average()

        val temperatures = values.mapNotNull { it.temperatureC }
        MetricsWindow(
            minutes = windowMinutes,
            samples = values.size,
            fpsAvg = avgNullable { it.fps },
            cpuHostAvg = avg { it.cpuHostPercent },
            cpuGuestAvg = avgNullable { it.cpuGuestPercent },
            guestCpuIoWaitAvgPercent = avgNullable { it.guestCpuIoWaitPercent },
            guestCpuInterruptAvgPercent = avgNullable { it.guestCpuInterruptPercent },
            guestCpuTimeDeltaAvgMs = avgNullable { it.guestCpuTimeDeltaMs?.toDouble() },
            vCpuUtilizationAvg = avgNullable { it.vCpuUtilization },
            ramGuestAvgMb = avgNullable { it.ramGuestMb?.toDouble() },
            ramHostAvgMb = avg { it.ramHostMb.toDouble() },
            temperatureAvgC = temperatures.takeIf { it.isNotEmpty() }?.average(),
            temperatureMaxC = temperatures.maxOrNull(),
            ioReadBytesPerSecAvg = avg { it.ioReadBytesPerSec.toDouble() },
            ioWriteBytesPerSecAvg = avg { it.ioWriteBytesPerSec.toDouble() },
            processIoReadBytesPerSecAvg = avgNullable { it.processIoReadBytesPerSec?.toDouble() },
            processIoWriteBytesPerSecAvg = avgNullable { it.processIoWriteBytesPerSec?.toDouble() },
            systemCpuIoWaitPercentAvg = avgNullable { it.systemCpuIoWaitPercent },
            sampleIntervalMsAvg = avgNullable { it.sampleIntervalMs?.toDouble() },
            networkRxBytesPerSecAvg = avg { it.networkRxBytesPerSec.toDouble() },
            networkTxBytesPerSecAvg = avg { it.networkTxBytesPerSec.toDouble() },
            audioUnderrunsLast = values.asReversed().firstNotNullOfOrNull { it.audioUnderruns },
            audioLatencyConfiguredMsAvg = avgNullable { it.audioLatencyConfiguredMs },
            cacheHitRateAvg = avgNullable { it.cacheHitRate },
            translationMsAvg = avgNullable { it.translationMs },
            throttlingSamples = values.count { it.throttling },
        )
    }

    @Synchronized
    fun updateLatest(transform: (VmMetricsSample) -> VmMetricsSample) {
        val latest = samples.removeLastOrNull() ?: return
        samples.addLast(transform(latest))
    }
}
