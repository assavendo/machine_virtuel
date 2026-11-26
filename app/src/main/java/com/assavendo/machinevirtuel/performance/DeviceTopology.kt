package com.assavendo.machinevirtuel.performance

import java.io.File

data class CpuCore(
    val id: Int,
    val maxFrequencyKHz: Long?,
    val capacity: Int?,
    val performanceClass: PerformanceClass,
)

enum class PerformanceClass { PERFORMANCE, EFFICIENCY, UNKNOWN }

data class CpuTopology(
    val cores: List<CpuCore>,
    val performanceCores: List<CpuCore>,
    val efficiencyCores: List<CpuCore>,
    val allowedCpuIds: List<Int> = cores.map { it.id },
) {
    val logicalCpuCount: Int get() = allowedCpuIds.distinct().size
}

/**
 * Reads only CPUs that are online and available to this process. Android devices may
 * expose offline CPUs and cpuset restrictions; neither should enter a VM affinity mask.
 */
object DeviceTopology {
    fun read(
        root: File = File("/sys/devices/system/cpu"),
        procStatus: File = File("/proc/self/status"),
        cgroupFiles: List<File> = listOf(
            File("/sys/fs/cgroup/cpuset.cpus.effective"),
            File("/sys/fs/cgroup/cpuset/cpuset.cpus"),
            File("/dev/cpuset/foreground/cpus"),
            File("/dev/cpuset/cpus"),
        ),
    ): CpuTopology {
        val discovered = root.listFiles()
            ?.mapNotNull { Regex("cpu(\\d+)").matchEntire(it.name)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            ?.distinct()
            ?.sorted()
            .orEmpty()
        if (discovered.isEmpty()) return CpuTopology(emptyList(), emptyList(), emptyList(), emptyList())

        val online = readCpuList(File(root, "online"))?.takeIf { it.isNotEmpty() } ?: discovered
        val allowed = readProcAllowedCpuList(procStatus)
            ?: cgroupFiles.firstNotNullOfOrNull(::readCpuList)
            ?: online.toSet()
        val usableIds = online.toSet().intersect(allowed).intersect(discovered.toSet()).sorted()
        if (usableIds.isEmpty()) return CpuTopology(emptyList(), emptyList(), emptyList(), emptyList())

        val raw = usableIds.map { id ->
            val directory = File(root, "cpu$id")
            CpuCore(
                id = id,
                maxFrequencyKHz = readLong(File(directory, "cpufreq/cpuinfo_max_freq")),
                capacity = readLong(File(directory, "cpu_capacity"))?.toInt(),
                performanceClass = PerformanceClass.UNKNOWN,
            )
        }

        val capacities = raw.mapNotNull { it.capacity }.distinct()
        val frequencies = raw.mapNotNull { it.maxFrequencyKHz }.distinct()
        val useCapacity = capacities.size > 1
        val useFrequency = !useCapacity && frequencies.size > 1
        val maxCapacity = raw.mapNotNull { it.capacity }.maxOrNull()
        val minCapacity = raw.mapNotNull { it.capacity }.minOrNull()
        val maxFrequency = raw.mapNotNull { it.maxFrequencyKHz }.maxOrNull()
        val minFrequency = raw.mapNotNull { it.maxFrequencyKHz }.minOrNull()

        val classified = raw.map { core ->
            val clazz = when {
                useCapacity && core.capacity != null ->
                    if (core.capacity == maxCapacity && maxCapacity != minCapacity) {
                        PerformanceClass.PERFORMANCE
                    } else {
                        PerformanceClass.EFFICIENCY
                    }
                useFrequency && core.maxFrequencyKHz != null ->
                    if (core.maxFrequencyKHz == maxFrequency && maxFrequency != minFrequency) {
                        PerformanceClass.PERFORMANCE
                    } else {
                        PerformanceClass.EFFICIENCY
                    }
                else -> PerformanceClass.UNKNOWN
            }
            core.copy(performanceClass = clazz)
        }

        return CpuTopology(
            cores = classified,
            performanceCores = classified.filter { it.performanceClass == PerformanceClass.PERFORMANCE },
            efficiencyCores = classified.filter { it.performanceClass == PerformanceClass.EFFICIENCY },
            allowedCpuIds = usableIds,
        )
    }

    /** Parses Linux cpulist syntax, for example "0-3,6,8-9". Invalid ranges are ignored. */
    fun parseCpuList(value: String): Set<Int> {
        val result = sortedSetOf<Int>()
        value.trim().split(',').forEach { token ->
            val part = token.trim()
            if (part.isEmpty()) return@forEach
            val range = part.split('-', limit = 2)
            val first = range[0].toIntOrNull() ?: return@forEach
            val last = if (range.size == 1) first else range[1].toIntOrNull() ?: return@forEach
            if (first !in 0..4095 || last !in first..4095) return@forEach
            for (cpu in first..last) result += cpu
        }
        return result
    }

    private fun readProcAllowedCpuList(status: File): Set<Int>? {
        if (!status.isFile) return null
        val value = runCatching {
            status.useLines { lines ->
                lines.firstOrNull { it.startsWith("Cpus_allowed_list:") }
                    ?.substringAfter(':')
                    ?.trim()
            }
        }.getOrNull() ?: return null
        return parseCpuList(value).takeIf { it.isNotEmpty() }
    }

    private fun readCpuList(file: File): Set<Int>? {
        if (!file.isFile) return null
        val text = runCatching { file.readText().trim() }.getOrNull() ?: return null
        return parseCpuList(text).takeIf { it.isNotEmpty() }
    }

    private fun readLong(file: File): Long? =
        runCatching { if (file.isFile) file.readText().trim().toLongOrNull() else null }.getOrNull()
}
