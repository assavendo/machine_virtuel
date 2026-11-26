package com.assavendo.machinevirtuel.performance

import android.os.Process
import com.assavendo.machinevirtuel.engine.NativeEngine
import java.io.File

data class CpuThreadSchedulingReport(
    val processThreadCount: Int,
    val vcpuThreadCount: Int,
    val ioThreadCount: Int,
    val affinityApplied: Int,
    val affinityFailed: Int,
    val priorityApplied: Int,
    val priorityFailed: Int,
) {
    fun summary(): String =
        "threads=$processThreadCount, vCPU=$vcpuThreadCount, I/O=$ioThreadCount, " +
            "affinity=$affinityApplied applied/$affinityFailed failed, " +
            "priority=$priorityApplied applied/$priorityFailed failed"
}

internal data class CpuThreadPlacement(
    val threadId: Long,
    val cpuIds: List<Int>,
    val priority: Int?,
)

/**
 * Applies scheduler decisions only to TIDs belonging to PhoenixVM's own backend process.
 * QMP-identified vCPU workers receive a single-core affinity; I/O workers receive the
 * VM's allowed CPU set and a deliberately lower priority. Other backend threads remain
 * within the VM CPU set but are not guessed to be GPU/audio threads.
 */
object VmCpuThreadScheduler {
    internal fun plan(
        processThreadIds: List<Long>,
        vcpuThreadIds: List<Long>,
        ioThreadIds: List<Long>,
        decision: VmScheduleDecision,
    ): List<CpuThreadPlacement> {
        if (decision.assignedCpuIds.isEmpty()) return emptyList()
        val processThreads = processThreadIds
            .filter { it in 1..Int.MAX_VALUE.toLong() }
            .distinct()
        val processThreadSet = processThreads.toSet()
        val vcpus = vcpuThreadIds.distinct().filter { it in processThreadSet }
        val vcpuSet = vcpus.toSet()
        val ioThreads = ioThreadIds.distinct().filter { it in processThreadSet && it !in vcpuSet }
        val known = vcpuSet + ioThreads.toSet()
        val placements = mutableListOf<CpuThreadPlacement>()

        processThreads.filter { it !in known }.forEach { tid ->
            placements += CpuThreadPlacement(tid, decision.assignedCpuIds, null)
        }

        vcpus.forEachIndexed { index, tid ->
            val cpu = decision.assignedCpuIds.getOrNull(index)
            if (cpu != null) {
                placements += CpuThreadPlacement(tid, listOf(cpu), decision.threadPriority)
            }
        }

        val ioPriority = (decision.threadPriority + 3).coerceAtLeast(0).coerceAtMost(19)
        ioThreads.forEach { tid ->
            placements += CpuThreadPlacement(tid, decision.assignedCpuIds, ioPriority)
        }
        return placements
    }

    fun apply(
        processId: Long,
        vcpuThreadIds: List<Long>,
        ioThreadIds: List<Long>,
        decision: VmScheduleDecision,
        procRoot: File = File("/proc"),
    ): CpuThreadSchedulingReport {
        if (processId !in 1..Int.MAX_VALUE.toLong() || decision.assignedCpuIds.isEmpty()) {
            return CpuThreadSchedulingReport(0, 0, 0, 0, 0, 0, 0)
        }

        val processThreads = File(procRoot, "$processId/task")
            .listFiles()
            ?.mapNotNull { it.name.toLongOrNull() }
            .orEmpty()
        val processThreadSet = processThreads.filter { it in 1..Int.MAX_VALUE.toLong() }.toSet()
        val vcpuSet = vcpuThreadIds.distinct().filter { it in processThreadSet }.toSet()
        val ioSet = ioThreadIds.distinct().filter { it in processThreadSet && it !in vcpuSet }.toSet()
        val placements = plan(processThreads, vcpuThreadIds, ioThreadIds, decision)

        var affinityApplied = 0
        var affinityFailed = 0
        var priorityApplied = 0
        var priorityFailed = 0

        placements.forEach { placement ->
            val affinity = NativeEngine.setThreadAffinity(placement.threadId, placement.cpuIds)
            if (affinity.applied) affinityApplied++ else affinityFailed++

            placement.priority?.let { priority ->
                val success = runCatching {
                    Process.setThreadPriority(placement.threadId.toInt(), priority.coerceIn(-4, 19))
                    true
                }.getOrDefault(false)
                if (success) priorityApplied++ else priorityFailed++
            }
        }

        return CpuThreadSchedulingReport(
            processThreadCount = processThreadSet.size,
            vcpuThreadCount = vcpuSet.size,
            ioThreadCount = ioSet.size,
            affinityApplied = affinityApplied,
            affinityFailed = affinityFailed,
            priorityApplied = priorityApplied,
            priorityFailed = priorityFailed,
        )
    }
}
