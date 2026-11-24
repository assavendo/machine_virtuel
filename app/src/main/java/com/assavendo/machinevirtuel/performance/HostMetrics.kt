package com.assavendo.machinevirtuel.performance

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.os.ThermalManager
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

class HostMetricsCollector(private val context: Context) {
    private var lastCpuTimeNs = 0L
    private var lastWallNs = 0L
    private var lastIo = readIoBytes()
    private var lastNetwork = readNetworkBytes()
    private var lastIoWallNs = 0L

    fun sample(): HostMetrics {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val procCpuNs = Debug.threadCpuTimeNanos()
        val processDelta = procCpuNs - lastCpuTimeNs
        val wallDelta = nowNs - lastWallNs
        val cpuCount = max(1, Runtime.getRuntime().availableProcessors())
        val processPercent = if (wallDelta > 0 && lastWallNs > 0) {
            (processDelta.toDouble() / wallDelta.toDouble()) * 100.0 * cpuCount
        } else 0.0
        lastCpuTimeNs = procCpuNs
        lastWallNs = nowNs

        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        activity.getMemoryInfo(memory)

        val thermalStatus = if (Build.VERSION.SDK_INT >= 29) {
            context.getSystemService(ThermalManager::class.java)?.currentThermalStatus ?: 0
        } else 0
        val severeThreshold = if (Build.VERSION.SDK_INT >= 29) ThermalManager.THERMAL_STATUS_SEVERE else Int.MAX_VALUE

        val io = readIoBytes()
        val net = readNetworkBytes()
        val elapsedForRates = if (lastIoWallNs > 0) (nowNs - lastIoWallNs) / 1_000_000_000.0 else 0.0
        val ioReadRate = if (elapsedForRates > 0) ((io.first - lastIo.first) / elapsedForRates).coerceAtLeast(0.0).toLong() else 0L
        val ioWriteRate = if (elapsedForRates > 0) ((io.second - lastIo.second) / elapsedForRates).coerceAtLeast(0.0).toLong() else 0L
        val rxRate = if (elapsedForRates > 0) ((net.first - lastNetwork.first) / elapsedForRates).coerceAtLeast(0.0).toLong() else 0L
        val txRate = if (elapsedForRates > 0) ((net.second - lastNetwork.second) / elapsedForRates).coerceAtLeast(0.0).toLong() else 0L
        lastIo = io
        lastNetwork = net
        lastIoWallNs = nowNs

        return HostMetrics(
            timestampMs = System.currentTimeMillis(),
            processCpuPercent = processPercent.coerceAtLeast(0.0),
            systemCpuPercent = readSystemCpuPercent(),
            processRssMb = Debug.getPss().toLong() / 1024L,
            availableMemoryMb = memory.availMem / (1024L * 1024L),
            totalMemoryMb = memory.totalMem / (1024L * 1024L),
            thermalStatus = thermalStatus,
            skinTemperatureC = readSkinTemperature(),
            isThrottling = thermalStatus >= severeThreshold,
            cpuFrequencyKHz = readCpuFrequency(),
            memoryPressureSomeUs = readMemoryPressureSomeUs(),
            ioReadBytesPerSec = ioReadRate,
            ioWriteBytesPerSec = ioWriteRate,
            networkRxBytesPerSec = rxRate,
            networkTxBytesPerSec = txRate,
        )
    }

    private fun readSystemCpuPercent(): Double {
        val line = runCatching { File("/proc/stat").useLines { it.firstOrNull { line -> line.startsWith("cpu ") } } }.getOrNull()
            ?: return 0.0
        val v = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (v.size < 4) return 0.0
        val idle = v[3]
        val total = v.sum()
        return if (total > 0) ((total - idle).toDouble() / total.toDouble()) * 100.0 else 0.0
    }

    private fun readCpuFrequency(): Long? {
        val root = File("/sys/devices/system/cpu")
        return root.listFiles()?.asSequence()
            ?.mapNotNull { cpu -> File(cpu, "cpufreq/scaling_cur_freq").takeIf { it.isFile }?.let { runCatching { it.readText().trim().toLong() }.getOrNull() } }
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
            val p = line.trim().split(Regex("\\s+"))
            if (p.size >= 10) {
                val sectorsRead = p[5].toLongOrNull() ?: 0L
                val sectorsWritten = p[9].toLongOrNull() ?: 0L
                reads += sectorsRead * 512L
                writes += sectorsWritten * 512L
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
            val value = file.readText().trim().toDoubleOrNull() ?: continue
            return if (value > 1000.0) value / 1000.0 else value
        }
        return null
    }
}
