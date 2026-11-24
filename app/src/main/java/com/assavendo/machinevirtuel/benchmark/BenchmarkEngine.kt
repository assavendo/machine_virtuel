package com.assavendo.machinevirtuel.benchmark

import com.assavendo.machinevirtuel.performance.HostMetrics
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureNanoTime

data class BenchmarkEnvironment(
    val model: String,
    val manufacturer: String,
    val androidApi: Int,
    val kernel: String,
    val supportedAbis: List<String>,
    val backend: String,
    val vmConfig: String,
)

data class BenchmarkResult(
    val name: String,
    val score: Double,
    val durationMs: Long,
    val metadata: Map<String, String>,
)

class BenchmarkEngine {
    fun cpuSingleThread(iterations: Int = 200_000): BenchmarkResult {
        val start = System.nanoTime()
        var digest = ByteArray(32)
        repeat(iterations) { digest = MessageDigest.getInstance("SHA-256").digest(digest) }
        val elapsed = (System.nanoTime() - start) / 1_000_000L
        return BenchmarkResult("cpu-single-sha256", iterations.toDouble() / max(1, elapsed), elapsed, mapOf("iterations" to iterations.toString()))
    }

    fun memoryCopy(sizeMb: Int = 64, rounds: Int = 20): BenchmarkResult {
        val size = max(1, min(sizeMb, 512)) * 1024 * 1024
        val src = ByteArray(size)
        val dst = ByteArray(size)
        val elapsed = measureNanoTime { repeat(rounds) { System.arraycopy(src, 0, dst, 0, src.size) } }
        val seconds = elapsed / 1_000_000_000.0
        val gbps = (size.toDouble() * rounds / seconds) / (1024.0 * 1024.0 * 1024.0)
        return BenchmarkResult("memory-copy", gbps, elapsed / 1_000_000L, mapOf("size_mb" to (size / 1024 / 1024).toString()))
    }

    fun sustainedHost(minutes: Int, sampler: () -> HostMetrics): List<HostMetrics> {
        require(minutes in 1..60)
        val end = System.currentTimeMillis() + minutes * 60_000L
        val output = mutableListOf<HostMetrics>()
        while (System.currentTimeMillis() < end) {
            output += sampler()
            Thread.sleep(1000L)
        }
        return output
    }
}
