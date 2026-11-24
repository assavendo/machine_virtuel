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
)

class HostMetricsCollector(
    private val context: Context,
    private val targetPid: Long = android.os.Process.myPid().toLong(),
) {
    private data class CpuTimes(val total: Long, val idle: Long)

    private val clockTicksPerSecond = runCatching {
        Os.sysconf(OsConstants._SC_CLK_TCK).takeIf { it > 0L } ?: 100L
    }.getOrDefault(100L)
    private var lastProcessCpuNs: Long? = null
    private var lastWallNs = 0L
    private var lastSystemCpu: CpuTimes? = null
    private var lastIo = readIoBytes()
    private var lastNetwork = readNetworkBytes()
    private var lastIoWallNs = 0L

    fun sample(): HostMetrics {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val processCpuNs = readProcessCpuTimeNs()
        val wallDelta = nowNs - lastWallNs
        val previousProcessCpu = lastProcessCpuNs
        val cpuCount = max(1, Runtime.getRuntime().availableProcessors())
        val processPercent = if (processCpuNs != null && previousProcessCpu != null &&
            wallDelta > 0L && processCpuNs >= previousProcessCpu
        ) {
            ((processCpuNs - previousProcessCpu).toDouble() / wallDelta.toDouble() /
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
        val elapsedForRates = if (lastIoWallNs > 0L) {
            (nowNs - lastIoWallNs) / 1_000_000_000.0
        } else 0.0
        val ioReadRate = if (elapsedForRates > 0.0) {
            ((io.first - lastIo.first) / elapsedForRates).coerceAtLeast(0.0).toLong()
        } else 0L
        val ioWriteRate = if (elapsedForRates > 0.0) {
            ((io.second - lastIo.second) / elapsedForRates).coerceAtLeast(0.0).toLong()
        } else 0L
        val rxRate = if (elapsedForRates > 0.0) {
            ((net.first - lastNetwork.first) / elapsedForRates).coerceAtLeast(0.0).toLong()
        } else 0L
        val txRate = if (elapsedForRates > 0.0) {
            ((net.second - lastNetwork.second) / elapsedForRates).coerceAtLeast(0.0).toLong()
        } else 0L
        lastIo = io
        lastNetwork = net
        lastIoWallNs = nowNs

        return HostMetrics(
            timestampMs = System.currentTimeMillis(),
            processCpuPercent = processPercent.coerceIn(0.0, 100.0),
            systemCpuPercent = readSystemCpuPercent(),
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
        )
    }

    private fun readProcessCpuTimeNs(): Long? {
        val file = File("/proc/" + targetPid + "/stat")
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
        val status = File("/proc/" + targetPid + "/status")
        if (!status.isFile) return 0L
        val line = runCatching {
            status.useLines { lines -> lines.firstOrNull { it.startsWith("VmRSS:") } }
        }.getOrNull() ?: return 0L
        val kb = line.trim().split(Regex("\\s+")).getOrNull(1)?.toLongOrNull() ?: return 0L
        return kb / 1024L
    }

    private fun readSystemCpuPercent(): Double {
        val current = readSystemCpuTimes() ?: return 0.0
        val previous = lastSystemCpu
        lastSystemCpu = current
        if (previous == null) return 0.0
        val totalDelta = current.total - previous.total
        val idleDelta = current.idle - previous.idle
        if (totalDelta <= 0L || idleDelta < 0L) return 0.0
        return ((totalDelta - idleDelta).coerceAtLeast(0L).toDouble() /
            totalDelta.toDouble() * 100.0).coerceIn(0.0, 100.0)
    }

    private fun readSystemCpuTimes(): CpuTimes? {
        val line = runCatching {
            File("/proc/stat").useLines { lines -> lines.firstOrNull { it.startsWith("cpu ") } }
        }.getOrNull() ?: return null
        val values = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 4) return null
        val idle = values[3] + (values.getOrNull(4) ?: 0L)
        return CpuTimes(values.sum(), idle)
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
