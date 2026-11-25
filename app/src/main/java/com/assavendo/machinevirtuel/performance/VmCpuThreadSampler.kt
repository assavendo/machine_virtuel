package com.assavendo.machinevirtuel.performance

import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Measures the host CPU time consumed by QEMU's vCPU threads.
 *
 * This is vCPU host-thread utilization, not guest CPU utilization. Without a
 * guest agent or paravirtualized guest counters, guest CPU time is unknown.
 */
class VmCpuThreadSampler {
    private val clockTicksPerSecond = runCatching {
        Os.sysconf(OsConstants._SC_CLK_TCK).takeIf { it > 0L } ?: 100L
    }.getOrDefault(100L)

    private var lastWallNs = 0L
    private var lastCpuTicks = 0L
    private var lastThreadIds: Set<Long> = emptySet()

    fun sample(threadIds: List<Long>): Double? {
        val ids = threadIds.toSet()
        if (ids.isEmpty()) return null

        val cpuTicks = ids.sumOf { readThreadCpuTicks(it) ?: 0L }
        if (cpuTicks <= 0L) return null

        val nowNs = SystemClock.elapsedRealtimeNanos()
        val wallDeltaNs = if (lastWallNs == 0L) 0L else nowNs - lastWallNs
        val previousTicks = lastCpuTicks
        val baselineValid =
            wallDeltaNs > 0L &&
                lastThreadIds == ids &&
                previousTicks > 0L &&
                cpuTicks >= previousTicks

        lastWallNs = nowNs
        lastCpuTicks = cpuTicks
        lastThreadIds = ids

        if (!baselineValid) return null

        val cpuSeconds = (cpuTicks - previousTicks) / clockTicksPerSecond.toDouble()
        val wallSeconds = wallDeltaNs / 1_000_000_000.0
        if (wallSeconds <= 0.0) return null

        val utilization = cpuSeconds / wallSeconds / ids.size.toDouble() * 100.0
        return utilization.coerceIn(0.0, 100.0)
    }

    fun reset() {
        lastWallNs = 0L
        lastCpuTicks = 0L
        lastThreadIds = emptySet()
    }

    private fun readThreadCpuTicks(tid: Long): Long? {
        val file = File("/proc/$tid/stat")
        if (!file.isFile) return null
        val line = runCatching { file.readText() }.getOrNull() ?: return null
        val endOfCommand = line.lastIndexOf(')')
        if (endOfCommand < 0 || endOfCommand + 1 >= line.length) return null
        val fields = line.substring(endOfCommand + 1).trim().split(Regex("\s+"))
        if (fields.size <= 12) return null
        val userTicks = fields[11].toLongOrNull() ?: return null
        val systemTicks = fields[12].toLongOrNull() ?: return null
        return userTicks + systemTicks
    }
}
