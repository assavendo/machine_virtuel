package com.assavendo.machinevirtuel.performance

import android.os.SystemClock
import java.security.MessageDigest

data class BenchmarkResult(
    val id: String,
    val durationMs: Long,
    val score: Double,
    val metadata: Map<String, String>,
)

class BenchmarkSuite(private val metricsCollector: HostMetricsCollector) {
    fun memoryCopy(sizeMb: Int = 128): BenchmarkResult {
        val sizeBytes = sizeMb.coerceIn(8, 512) * 1024 * 1024
        val source = ByteArray(1024 * 1024) { (it * 31).toByte() }
        val target = ByteArray(source.size)
        val start = SystemClock.elapsedRealtimeNanos()
        var remaining = sizeBytes
        var checksum = 0
        while (remaining > 0) {
            System.arraycopy(source, 0, target, 0, source.size)
            checksum = checksum xor target[remaining.toInt().coerceAtMost(target.lastIndex)].toInt()
            remaining -= source.size
        }
        if (checksum == Int.MIN_VALUE) error("unreachable")
        val durationMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000L
        val gbps = sizeBytes.toDouble() / durationMs.coerceAtLeast(1).toDouble() / 1_000_000.0
        return BenchmarkResult("memory-copy", durationMs, gbps, mapOf("sizeMb" to sizeMb.toString()))
    }

    fun cpuHash(iterations: Int = 2_000_000): BenchmarkResult {
        val payload = ByteArray(64) { it.toByte() }
        val digest = MessageDigest.getInstance("SHA-256")
        val start = SystemClock.elapsedRealtimeNanos()
        repeat(iterations) { digest.digest(payload) }
        val durationMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000L
        return BenchmarkResult(
            "cpu-sha256",
            durationMs,
            iterations.toDouble() / durationMs.coerceAtLeast(1).toDouble() * 1000.0,
            mapOf("iterations" to iterations.toString()),
        )
    }

    fun sustainedSamples(seconds: Int = 30): List<HostMetrics> {
        val end = SystemClock.elapsedRealtime() + seconds.coerceIn(5, 300) * 1000L
        val samples = mutableListOf<HostMetrics>()
        while (SystemClock.elapsedRealtime() < end) {
            samples += metricsCollector.sample()
            SystemClock.sleep(1000L)
        }
        return samples
    }
}
