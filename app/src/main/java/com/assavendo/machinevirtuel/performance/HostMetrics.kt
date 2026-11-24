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
    val thermalStatus: Int,
    val skinTemperatureC: Double?,
    val isThrottling: Boolean,
)

class HostMetricsCollector(private val context: Context) {
    private var lastCpuTimeNs = 0L
    private var lastWallNs = 0L

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

        val thermalStatus =
            if (Build.VERSION.SDK_INT >= 29) {
                context.getSystemService(ThermalManager::class.java)?.currentThermalStatus ?: 0
            } else {
                0
            }

        val severeThreshold =
            if (Build.VERSION.SDK_INT >= 29) ThermalManager.THERMAL_STATUS_SEVERE else Int.MAX_VALUE

        return HostMetrics(
            timestampMs = System.currentTimeMillis(),
            processCpuPercent = processPercent.coerceAtLeast(0.0),
            systemCpuPercent = 0.0,
            processRssMb = Debug.getPss().toLong() / 1024L,
            availableMemoryMb = memory.availMem / (1024L * 1024L),
            thermalStatus = thermalStatus,
            skinTemperatureC = readSkinTemperature(),
            isThrottling = thermalStatus >= severeThreshold,
        )
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
