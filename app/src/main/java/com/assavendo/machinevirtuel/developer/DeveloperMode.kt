package com.assavendo.machinevirtuel.developer

import android.content.Context
import com.assavendo.machinevirtuel.benchmark.BenchmarkEngine
import com.assavendo.machinevirtuel.host.HostCapabilityScanner
import com.assavendo.machinevirtuel.performance.VmMetricsRegistry
import com.assavendo.machinevirtuel.runtime.DiagnosticExporter
import com.assavendo.machinevirtuel.runtime.VmLogger
import com.assavendo.machinevirtuel.vm.VmManager
import java.io.File

data class DeveloperReport(
    val vmId: String,
    val host: String,
    val timings: Map<String, Long>,
    val benchmarkCpuScore: Double?,
    val benchmarkMemoryScore: Double?,
    val metrics: Int,
    val runtimeRoot: String,
    val logs: String,
)

class DeveloperTools(private val context: Context) {
    private val manager = VmManager(context)
    private val benchmark = BenchmarkEngine()

    fun report(vmId: String): DeveloperReport {
        val start = System.nanoTime()
        val caps = HostCapabilityScanner(context).scan()
        val capsNs = (System.nanoTime() - start) / 1_000_000L
        val cpuStart = System.nanoTime()
        val cpu = runCatching { benchmark.cpuSingleThread() }.getOrNull()
        val cpuMs = (System.nanoTime() - cpuStart) / 1_000_000L
        val memStart = System.nanoTime()
        val mem = runCatching { benchmark.memoryCopy(16, 4) }.getOrNull()
        val memMs = (System.nanoTime() - memStart) / 1_000_000L
        val log = VmLogger(context, vmId).readLast()
        return DeveloperReport(
            vmId = vmId,
            host = caps.toString(),
            timings = mapOf("capabilities_ms" to capsNs, "cpu_benchmark_ms" to cpuMs, "memory_benchmark_ms" to memMs),
            benchmarkCpuScore = cpu?.score,
            benchmarkMemoryScore = mem?.score,
            metrics = VmMetricsRegistry.last(vmId, 30).size,
            runtimeRoot = manager.runtimeRoot(),
            logs = log,
        )
    }

    fun exportReport(vmId: String): File {
        val report = report(vmId)
        val output = File(context.filesDir, "developer/reports/" + vmId + "-" + System.currentTimeMillis() + ".txt")
        output.parentFile?.mkdirs()
        output.writeText(buildString {
            append("PhoenixVM developer report\n")
            append("vm=").append(report.vmId).append('\n')
            append("host=").append(report.host).append('\n')
            append("runtime=").append(report.runtimeRoot).append('\n')
            append("timings=").append(report.timings).append('\n')
            append("cpu_score=").append(report.benchmarkCpuScore).append('\n')
            append("memory_score=").append(report.benchmarkMemoryScore).append('\n')
            append("metrics_samples=").append(report.metrics).append('\n')
            append("--- logs ---\n").append(report.logs)
        })
        return output
    }

    fun exportDiagnostics(): File =
        DiagnosticExporter.export(context, File(context.filesDir, "developer/diagnostics/diagnostics-" + System.currentTimeMillis() + ".zip"))
}
