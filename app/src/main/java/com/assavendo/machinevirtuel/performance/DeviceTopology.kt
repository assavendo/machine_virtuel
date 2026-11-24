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
) {
    val logicalCpuCount: Int get() = cores.size
}

object DeviceTopology {
    fun read(root: File = File("/sys/devices/system/cpu")): CpuTopology {
        val cpus = root.listFiles()
            ?.mapNotNull { file ->
                val id = Regex("cpu(\\d+)").matchEntire(file.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                CpuCore(
                    id,
                    readLong(File(file, "cpufreq/cpuinfo_max_freq")),
                    readLong(File(file, "cpu_capacity"))?.toInt(),
                    PerformanceClass.UNKNOWN,
                )
            }
            ?.sortedBy { it.id }
            ?: emptyList()
        if (cpus.isEmpty()) return CpuTopology(emptyList(), emptyList(), emptyList())
        val maxCapacity = cpus.mapNotNull { it.capacity }.maxOrNull()
        val maxFrequency = cpus.mapNotNull { it.maxFrequencyKHz }.maxOrNull()
        val classified = cpus.map {
            val clazz = when {
                maxCapacity != null && it.capacity != null ->
                    if (it.capacity == maxCapacity) PerformanceClass.PERFORMANCE else PerformanceClass.EFFICIENCY
                maxFrequency != null && it.maxFrequencyKHz != null ->
                    if (it.maxFrequencyKHz == maxFrequency) PerformanceClass.PERFORMANCE else PerformanceClass.EFFICIENCY
                else -> PerformanceClass.UNKNOWN
            }
            it.copy(performanceClass = clazz)
        }
        return CpuTopology(
            classified,
            classified.filter { it.performanceClass == PerformanceClass.PERFORMANCE },
            classified.filter { it.performanceClass == PerformanceClass.EFFICIENCY },
        )
    }

    private fun readLong(file: File): Long? =
        runCatching { if (file.isFile) file.readText().trim().toLongOrNull() else null }.getOrNull()
}
