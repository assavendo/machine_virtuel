package com.assavendo.machinevirtuel.performance

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File
import kotlin.math.max

data class HostMetrics(
    val timestampMs: Long,
    val processCpuPercent: Double,
    val systemCpuPercent: Double,
    val processRssMb: Long,
    val availableMemoryMb: Long,
    val totalMemoryMb: Long = 0,
    val thermalStatus: Int,
    val skinTemperatureC: Double?,
    val isThrottling: Boolean,
    val cpuFrequencyKHz: Long? = null,
    val memoryPressureSomeUs: Long? = null,
    val ioReadBytesPerSec: Long = 0,
    val ioWriteBytesPerSec: Long = 0,
    val networkRxBytesPerSec: Long = 0,
    val networkTxBytesPerSec: Long = 0,
    /** Backend-process block I/O; null means /proc did not expose trustworthy counters. */
    val processIoReadBytesPerSec: Long? = null,
    val processIoWriteBytesPerSec: Long? = null,
    /** System CPU iowait time as a percent of elapsed CPU ticks. */
    val systemCpuIoWaitPercent: Double? = null,
    /** Actual elapsed interval represented by the current sample. */
    val sampleIntervalMs: Long? = null,
)

internal data class SystemCpuTimes(
    val totalTicks: Long,
    val idleTicks: Long,
    val ioWaitTicks: Long,
)

internal data class ProcessIoCounters(
    val readBytes: Long,
    val writeBytes: Long,
)

/** Pure parsers are shared with JVM tests so /proc edge cases stay deterministic. */
internal object ProcMetricsParser {
    fun systemCpuTimes(line: String): SystemCpuTimes? {
        if (!line.startsWith("cpu ")) return null
        val values = line.trim().split(Regex("\\s+")).drop(1).map { it.toLongOrNull() ?: return null }
        if (values.size < 5) return null
        // Linux guest/guest_nice ticks are already included in user/nice; exclude them
        // to avoid double counting. The first eight fields represent total scheduler time.
        val total = values.take(8).sum()
        val idle = values[3]
        val ioWait = values[4]
        if (total <= 0L || idle < 0L || ioWait < 0L) return null
        return SystemCpuTimes(total, idle, ioWait)
    }

    fun processIo(content: String): ProcessIoCounters? {
        var readBytes: Long? = null
        var writeBytes: Long? = null
        content.lineSequence().forEach { line ->
            val key = line.substringBefore(':', "").trim()
            val value = line.substringAfter(':', "").trim().toLongOrNull() ?: return@forEach
            when (key) {
                "read_bytes" -> readBytes = value
                "write_bytes" -> writeBytes = value
            }
        }
        val read = readBytes ?: return null
        val written = writeBytes ?: return null
        if (read < 0L || written < 0L) return null
        return ProcessIoCounters(read, written)
    }
}

class HostMetricsCollector(
    private val context: Context,
    private val targetPid: Long? = android.os.Process.myPid().toLong(),
) {
    private val clockTicksPerSecond = runCatching {
        Os.sysconf(OsConstants._SC_CLK_TCK).takeIf { it > 0L } ?: 100L
    }.getOrDefault(100L)
    private var lastProcessCpuNs: Long? = null
    private var lastWallNs = 0L
    private var lastSystemCpu: SystemCpuTimes? = null
    private var lastIo = readIoBytes()
    private var lastNetwork = readNetworkBytes()
    private var lastSystemIoWallNs = 0L
    private var lastProcessIo = readProcessIoBytes()
    private var lastProcessIoWallNs = SystemClock.elapsedRealtimeNanos()

    fun sample(): HostMetrics {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val sampleDeltaNs = if (lastWallNs > 0L) nowNs - lastWallNs else 0L
        val processCpuNs = readProcessCpuTimeNs()
        val previousProcessCpu = lastProcessCpuNs
        val cpuCount = max(1, Runtime.getRuntime().availableProcessors())
        val processPercent = if (processCpuNs != null && previousProcessCpu != null &&
            sampleDeltaNs > 0L && processCpuNs >= previousProcessCpu
        ) {
            ((processCpuNs - previousProcessCpu).toDouble() / sampleDeltaNs.toDouble() /
                cpuCount.toDouble()) * 100.0
        } else 0.0
        if (processCpuNs != null) lastProcessCpuNs = processCpuNs
        lastWallNs = nowNs

        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        activity.getMemoryInfo(memory)

        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            runCatching { power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE }
                .getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }

        val io = readIoBytes()
        val net = readNetworkBytes()
        val elapsedForSystemRates = if (lastSystemIoWallNs > 0L) {
            (nowNs - lastSystemIoWallNs) / 1_000_000_000.0
        } else 0.0
        val ioReadRate = rate(io.first, lastIo.first, elapsedForSystemRates)
        val ioWriteRate = rate(io.second, lastIo.second, elapsedForSystemRates)
        val rxRate = rate(net.first, lastNetwork.first, elapsedForSystemRates)
        val txRate = rate(net.second, lastNetwork.second, elapsedForSystemRates)
        lastIo = io
        lastNetwork = net
        lastSystemIoWallNs = nowNs

        val processIo = readProcessIoBytes()
        val elapsedForProcessIo = (nowNs - lastProcessIoWallNs) / 1_000_000_000.0
        val processReadRate = if (processIo != null && lastProcessIo != null) {
            rate(processIo.readBytes, lastProcessIo!!.readBytes, elapsedForProcessIo)
        } else null
        val processWriteRate = if (processIo != null && lastProcessIo != null) {
            rate(processIo.writeBytes, lastProcessIo!!.writeBytes, elapsedForProcessIo)
        } else null
        if (processIo != null) lastProcessIo = processIo
        lastProcessIoWallNs = nowNs

        val cpuMetrics = readSystemCpuMetrics()

        return HostMetrics(
            timestampMs = System.currentTimeMillis(),
            processCpuPercent = processPercent.coerceIn(0.0, 100.0),
            systemCpuPercent = cpuMetrics.first,
            processRssMb = readProcessRssMb(),
            availableMemoryMb = memory.availMem / (1024L * 1024L),
            totalMemoryMb = memory.totalMem / (1024L * 1024L),
            thermalStatus = thermalStatus,
            skinTemperatureC = readSkinTemperature(),
            isThrottling = thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE,
            cpuFrequencyKHz = readCpuFrequency(),
            memoryPressureSomeUs = readMemoryPressureSomeUs(),
            ioReadBytesPerSec = ioReadRate,
            ioWriteBytesPerSec = ioWriteRate,
            networkRxBytesPerSec = rxRate,
            networkTxBytesPerSec = txRate,
            processIoReadBytesPerSec = processReadRate,
            processIoWriteBytesPerSec = processWriteRate,
            systemCpuIoWaitPercent = cpuMetrics.second,
            sampleIntervalMs = sampleDeltaNs.takeIf { it > 0L }?.div(1_000_000L),
        )
    }

    private fun rate(current: Long, previous: Long, seconds: Double): Long {
        if (seconds <= 0.0 || current < previous) return 0L
        return ((current - previous) / seconds).coerceAtLeast(0.0).toLong()
    }

    private fun readProcessCpuTimeNs(): Long? {
        val pid = targetPid ?: return null
        val file = File("/proc/$pid/stat")
        if (!file.isFile) return null
        val line = runCatching { file.readText() }.getOrNull() ?: return null
        val endOfCommand = line.lastIndexOf(')')
        if (endOfCommand < 0 || endOfCommand + 1 >= line.length) return null
        val fields = line.substring(endOfCommand + 1).trim().split(Regex("\\s+"))
        if (fields.size <= 12) return null
        val userTicks = fields[11].toLongOrNull() ?: return null
        val systemTicks = fields[12].toLongOrNull() ?: return null
        val totalTicks = userTicks + systemTicks
        return totalTicks * 1_000_000_000L / clockTicksPerSecond
    }

    private fun readProcessRssMb(): Long {
        val pid = targetPid ?: return 0L
        val status = File("/proc/$pid/status")
        if (!status.isFile) return 0L
        val line = runCatching {
            status.useLines { lines -> lines.firstOrNull { it.startsWith("VmRSS:") } }
        }.getOrNull() ?: return 0L
        val kb = line.trim().split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: return 0L
        return kb / 1024L
    }

    private fun readProcessIoBytes(): ProcessIoCounters? {
        val pid = targetPid ?: return null
        val file = File("/proc/$pid/io")
        if (!file.isFile) return null
        val content = runCatching { file.readText() }.getOrNull() ?: return null
        return ProcMetricsParser.processIo(content)
    }

    private fun readSystemCpuMetrics(): Pair<Double, Double?> {
        val current = readSystemCpuTimes() ?: return 0.0 to null
        val previous = lastSystemCpu
        lastSystemCpu = current
        if (previous == null) return 0.0 to null
        val totalDelta = current.totalTicks - previous.totalTicks
        val idleDelta = current.idleTicks - previous.idleTicks
        val ioWaitDelta = current.ioWaitTicks - previous.ioWaitTicks
        if (totalDelta <= 0L || idleDelta < 0L || ioWaitDelta < 0L) return 0.0 to null
        val busyTicks = (totalDelta - idleDelta - ioWaitDelta).coerceAtLeast(0L)
        val busyPercent = busyTicks.toDouble() / totalDelta.toDouble() * 100.0
        val ioWaitPercent = ioWaitDelta.toDouble() / totalDelta.toDouble() * 100.0
        return busyPercent.coerceIn(0.0, 100.0) to ioWaitPercent.coerceIn(0.0, 100.0)
    }

    private fun readSystemCpuTimes(): SystemCpuTimes? {
        val line = runCatching {
            File("/proc/stat").useLines { lines -> lines.firstOrNull { it.startsWith("cpu ") } }
        }.getOrNull() ?: return null
        return ProcMetricsParser.systemCpuTimes(line)
    }

    private fun readCpuFrequency(): Long? {
        val root = File("/sys/devices/system/cpu")
        return root.listFiles()?.asSequence()
            ?.filter { it.name.matches(Regex("cpu[0-9]+")) }
            ?.mapNotNull { cpu ->
                File(cpu, "cpufreq/scaling_cur_freq").takeIf { it.isFile }
                    ?.let { runCatching { it.readText().trim().toLong() }.getOrNull() }
            }
            ?.maxOrNull()
    }

    private fun readMemoryPressureSomeUs(): Long? {
        val file = File("/proc/pressure/memory")
        if (!file.isFile) return null
        val line = file.readLines().firstOrNull { it.startsWith("some ") } ?: return null
        return Regex("total=(\\d+)").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    private fun readIoBytes(): Pair<Long, Long> {
        val file = File("/proc/diskstats")
        if (!file.isFile) return 0L to 0L
        var reads = 0L
        var writes = 0L
        file.forEachLine { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 10) {
                reads += (parts[5].toLongOrNull() ?: 0L) * 512L
                writes += (parts[9].toLongOrNull() ?: 0L) * 512L
            }
        }
        return reads to writes
    }

    private fun readNetworkBytes(): Pair<Long, Long> {
        val file = File("/proc/net/dev")
        if (!file.isFile) return 0L to 0L
        var rx = 0L
        var tx = 0L
        file.readLines().drop(2).forEach { line ->
            val parts = line.substringAfter(':', "").trim().split(Regex("\\s+"))
            if (parts.size >= 9) {
                rx += parts[0].toLongOrNull() ?: 0L
                tx += parts[8].toLongOrNull() ?: 0L
            }
        }
        return rx to tx
    }

    private fun readSkinTemperature(): Double? {
        val candidates = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/devices/virtual/thermal/thermal_zone0/temp",
        )
        for (path in candidates) {
            val file = File(path)
            if (!file.isFile) continue
            val value = runCatching { file.readText().trim().toDoubleOrNull() }.getOrNull() ?: continue
            return if (value > 1000.0) value / 1000.0 else value
        }
        return null
    }
}
