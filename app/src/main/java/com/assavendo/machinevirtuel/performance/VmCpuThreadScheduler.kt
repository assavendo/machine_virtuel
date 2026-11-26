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
            "affinity=$affinityApplied/$affinityFailed failed, " +
            "priority=$priorityApplied/$priorityFailed failed"
}

/**
 * Applies scheduler decisions only to TIDs belonging to PhoenixVM's own backend process.
 * vCPU workers get a single-core affinity where QMP identifies them; other backend threads
 * stay within the VM's assigned CPU set. Unsupported/denied affinity never blocks the VM.
 */
object VmCpuThreadScheduler {
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
            ?.filter { it > 0L && it <= Int.MAX_VALUE.toLong() }
            ?.distinct()
            .orEmpty()
        val processThreadSet = processThreads.toSet()
        val vcpus = vcpuThreadIds.distinct().filter { it in processThreadSet }
        val ioThreads = ioThreadIds.distinct().filter { it in processThreadSet && it !in vcpus }

        var affinityApplied = 0
        var affinityFailed = 0
        var priorityApplied = 0
        var priorityFailed = 0

        fun setAffinity(tid: Long, cpus: List<Int>) {
            val result = NativeEngine.setThreadAffinity(tid, cpus)
            if (result.applied) affinityApplied++ else affinityFailed++
        }

        fun setPriority(tid: Long, priority: Int) {
            val success = runCatching {
                Process.setThreadPriority(tid.toInt(), priority.coerceIn(-4, 19))
                true
            }.getOrDefault(false)
            if (success) priorityApplied++ else priorityFailed++
        }

        // Unclassified main/worker/renderer threads are kept inside the VM CPU budget,
        // without guessing whether they are GPU or audio threads.
        val known = (vcpus + ioThreads).toSet()
        processThreads.filter { it !in known }.forEach { setAffinity(it, decision.assignedCpuIds) }

        vcpus.forEachIndexed { index, tid ->
            val cpu = decision.assignedCpuIds.getOrNull(index)
            if (cpu != null) setAffinity(tid, listOf(cpu))
            setPriority(tid, decision.threadPriority)
        }

        val ioPriority = (decision.threadPriority + 3).coerceAtLeast(0).coerceAtMost(19)
        ioThreads.forEach { tid ->
            setAffinity(tid, decision.assignedCpuIds)
            setPriority(tid, ioPriority)
        }

        return CpuThreadSchedulingReport(
            processThreadCount = processThreads.size,
            vcpuThreadCount = vcpus.size,
            ioThreadCount = ioThreads.size,
            affinityApplied = affinityApplied,
            affinityFailed = affinityFailed,
            priorityApplied = priorityApplied,
            priorityFailed = priorityFailed,
        )
    }
}
