package com.assavendo.machinevirtuel.performance

data class VmMetricsSample(
    val timestampMs: Long,
    val fps: Double? = null,
    val cpuGuestPercent: Double = 0.0,
    val cpuHostPercent: Double = 0.0,
    val vCpuUtilization: Double = 0.0,
    val ramGuestMb: Long = 0,
    val ramHostMb: Long = 0,
    val gpuPercent: Double? = null,
    val gpuMemoryMb: Long? = null,
    val temperatureC: Double? = null,
    val cpuFrequencyKHz: Long? = null,
    val throttling: Boolean = false,
    val ioReadBytesPerSec: Long = 0,
    val ioWriteBytesPerSec: Long = 0,
    val networkRxBytesPerSec: Long = 0,
    val networkTxBytesPerSec: Long = 0,
    val audioUnderruns: Long = 0,
    val cacheHitRate: Double = 0.0,
    val translationMs: Double = 0.0,
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
}
