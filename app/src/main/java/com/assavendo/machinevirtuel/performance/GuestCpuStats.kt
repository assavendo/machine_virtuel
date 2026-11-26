package com.assavendo.machinevirtuel.performance

import org.json.JSONArray
import org.json.JSONObject

data class GuestCpuCounters(
    val cpu: Int,
    val userMs: Long,
    val niceMs: Long,
    val systemMs: Long,
    val idleMs: Long,
    val ioWaitMs: Long,
    val irqMs: Long,
    val softIrqMs: Long,
    val stealMs: Long,
) {
    val totalMs: Long
        get() = userMs + niceMs + systemMs + idleMs + ioWaitMs + irqMs + softIrqMs + stealMs

    /** Guest and guestnice are excluded because Linux already includes them in user/nice. */
    val busyMs: Long
        get() = userMs + niceMs + systemMs + irqMs + softIrqMs + stealMs
}

data class GuestCpuMetrics(
    val cpuBusyPercent: Double,
    val ioWaitPercent: Double,
    val interruptPercent: Double,
    /** Sum of busy CPU milliseconds across all guest CPUs for this sampling interval. */
    val guestCpuTimeDeltaMs: Long,
    val intervalMs: Long,
    val coreCount: Int,
)

object GuestCpuStatsParser {
    fun parse(response: JSONObject): List<GuestCpuCounters>? {
        val values = response.optJSONArray("return") ?: return null
        val parsed = parseArray(values) ?: return null
        return parsed.takeIf { it.isNotEmpty() }
    }

    internal fun parseArray(values: JSONArray): List<GuestCpuCounters>? {
        val result = mutableListOf<GuestCpuCounters>()
        val seen = mutableSetOf<Int>()
        for (index in 0 until values.length()) {
            val entry = values.optJSONObject(index) ?: return null
            if (!entry.has("type") || entry.optString("type") != "linux") continue
            val stats = entry.optJSONObject("u")
                ?: entry.optJSONObject("data")
                ?: entry.optJSONObject("linux")
                ?: entry
            val cpu = requiredLong(stats, "cpu")?.toInt() ?: return null
            if (cpu !in 0..4095 || !seen.add(cpu)) return null
            val user = requiredLong(stats, "user") ?: return null
            val nice = requiredLong(stats, "nice") ?: return null
            val system = requiredLong(stats, "system") ?: return null
            val idle = requiredLong(stats, "idle") ?: return null
            val counters = GuestCpuCounters(
                cpu = cpu,
                userMs = user,
                niceMs = nice,
                systemMs = system,
                idleMs = idle,
                ioWaitMs = optionalLong(stats, "iowait") ?: return null,
                irqMs = optionalLong(stats, "irq") ?: return null,
                softIrqMs = optionalLong(stats, "softirq") ?: return null,
                stealMs = optionalLong(stats, "steal") ?: return null,
            )
            if (counters.totalMs < 0L) return null
            result += counters
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun requiredLong(json: JSONObject, field: String): Long? {
        if (!json.has(field) || json.isNull(field)) return null
        val value = runCatching { json.get(field) }.getOrNull() as? Number ?: return null
        return value.toLong().takeIf { it >= 0L }
    }

    private fun optionalLong(json: JSONObject, field: String): Long? {
        if (!json.has(field) || json.isNull(field)) return 0L
        return requiredLong(json, field)
    }
}

/**
 * Computes deltas from cumulative per-core QGA counters. CPU hotplug or counter reset
 * discards only the affected core; the first sample establishes a baseline and is null.
 */
class GuestCpuStatsTracker {
    private var previous: Map<Int, GuestCpuCounters> = emptyMap()
    private var previousTimestampMs: Long? = null

    @Synchronized
    fun update(current: List<GuestCpuCounters>, timestampMs: Long): GuestCpuMetrics? {
        if (current.isEmpty()) {
            reset()
            return null
        }
        val nowByCpu = current.associateBy { it.cpu }
        val previousMap = previous
        val previousTime = previousTimestampMs
        previous = nowByCpu
        previousTimestampMs = timestampMs
        if (previousMap.isEmpty() || previousTime == null || timestampMs <= previousTime) return null

        val deltas = current.mapNotNull { now ->
            val before = previousMap[now.cpu] ?: return@mapNotNull null
            val user = delta(now.userMs, before.userMs) ?: return@mapNotNull null
            val nice = delta(now.niceMs, before.niceMs) ?: return@mapNotNull null
            val system = delta(now.systemMs, before.systemMs) ?: return@mapNotNull null
            val idle = delta(now.idleMs, before.idleMs) ?: return@mapNotNull null
            val ioWait = delta(now.ioWaitMs, before.ioWaitMs) ?: return@mapNotNull null
            val irq = delta(now.irqMs, before.irqMs) ?: return@mapNotNull null
            val softIrq = delta(now.softIrqMs, before.softIrqMs) ?: return@mapNotNull null
            val steal = delta(now.stealMs, before.stealMs) ?: return@mapNotNull null
            GuestCpuCounters(now.cpu, user, nice, system, idle, ioWait, irq, softIrq, steal)
        }
        if (deltas.isEmpty()) return null
        val total = deltas.sumOf { it.totalMs }
        if (total <= 0L) return null
        val busy = deltas.sumOf { it.busyMs }
        val ioWait = deltas.sumOf { it.ioWaitMs }
        val interrupts = deltas.sumOf { it.irqMs + it.softIrqMs }
        return GuestCpuMetrics(
            cpuBusyPercent = (busy.toDouble() / total.toDouble() * 100.0).coerceIn(0.0, 100.0),
            ioWaitPercent = (ioWait.toDouble() / total.toDouble() * 100.0).coerceIn(0.0, 100.0),
            interruptPercent = (interrupts.toDouble() / total.toDouble() * 100.0).coerceIn(0.0, 100.0),
            guestCpuTimeDeltaMs = busy,
            intervalMs = timestampMs - previousTime,
            coreCount = deltas.size,
        )
    }

    @Synchronized
    fun reset() {
        previous = emptyMap()
        previousTimestampMs = null
    }

    private fun delta(current: Long, previous: Long): Long? =
        (current - previous).takeIf { it >= 0L }
}
