package com.assavendo.machinevirtuel.performance

import kotlin.test.Test
import kotlin.test.assertEquals

class VmCpuThreadSchedulerTest {
    private fun decision() = VmScheduleDecision(
        vmId = "vm-cpu",
        recommendedVcpus = 2,
        cpuClass = PerformanceClass.PERFORMANCE,
        priority = 90,
        reason = "test",
        assignedCpuIds = listOf(4, 6),
        threadPriority = -2,
    )

    @Test
    fun planPinsOnlyQmpIdentifiedVcpusAndLowersIoThreadPriority() {
        val plan = VmCpuThreadScheduler.plan(
            processThreadIds = listOf(100, 101, 102, 103),
            vcpuThreadIds = listOf(101, 999),
            ioThreadIds = listOf(102, 103, 101),
            decision = decision(),
        )

        assertEquals(
            listOf(
                CpuThreadPlacement(100, listOf(4, 6), null),
                CpuThreadPlacement(101, listOf(4), -2),
                CpuThreadPlacement(102, listOf(4, 6), 1),
                CpuThreadPlacement(103, listOf(4, 6), 1),
            ),
            plan,
        )
    }

    @Test
    fun planDeduplicatesThreadsAndRejectsThreadsOutsideBackendProcess() {
        val plan = VmCpuThreadScheduler.plan(
            processThreadIds = listOf(100, 100, 101),
            vcpuThreadIds = listOf(101, 101, 500),
            ioThreadIds = listOf(101, 500, 500),
            decision = decision(),
        )

        assertEquals(2, plan.size)
        assertEquals(1, plan.count { it.threadId == 100L })
        assertEquals(1, plan.count { it.threadId == 101L })
        assertEquals(listOf(4), plan.single { it.threadId == 101L }.cpuIds)
    }

    @Test
    fun emptyCpuAssignmentDoesNotCreateAffinityOperations() {
        val empty = decision().copy(assignedCpuIds = emptyList())
        assertEquals(
            emptyList(),
            VmCpuThreadScheduler.plan(
                processThreadIds = listOf(100, 101),
                vcpuThreadIds = listOf(101),
                ioThreadIds = emptyList(),
                decision = empty,
            ),
        )
    }
}
