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
            processThreadIds = listOf(100L, 101L, 102L, 103L),
            vcpuThreadIds = listOf(101L, 999L),
            ioThreadIds = listOf(102L, 103L, 101L),
            decision = decision(),
        )

        assertEquals(
            listOf(
                CpuThreadPlacement(100L, listOf(4, 6), null),
                CpuThreadPlacement(101L, listOf(4), -2),
                CpuThreadPlacement(102L, listOf(4, 6), 1),
                CpuThreadPlacement(103L, listOf(4, 6), 1),
            ),
            plan,
        )
    }

    @Test
    fun planDeduplicatesThreadsAndRejectsThreadsOutsideBackendProcess() {
        val plan = VmCpuThreadScheduler.plan(
            processThreadIds = listOf(100L, 100L, 101L),
            vcpuThreadIds = listOf(101L, 101L, 500L),
            ioThreadIds = listOf(101L, 500L, 500L),
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
                processThreadIds = listOf(100L, 101L),
                vcpuThreadIds = listOf(101L),
                ioThreadIds = emptyList(),
                decision = empty,
            ),
        )
    }

    @Test
    fun allBackendThreadsStayInsideAssignedCpuSetWhenQmpExposesNoThreadIds() {
        val plan = VmCpuThreadScheduler.plan(
            processThreadIds = listOf(700L, 701L, 702L),
            vcpuThreadIds = emptyList(),
            ioThreadIds = emptyList(),
            decision = decision(),
        )

        assertEquals(3, plan.size)
        assertEquals(setOf(4, 6), plan.flatMap { it.cpuIds }.toSet())
        assertTrue(plan.all { it.priority == null })
    }

}
