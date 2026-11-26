package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.performance.DeviceTopology
import com.assavendo.machinevirtuel.performance.PerformanceClass
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceTopologyTest {
    @Test
    fun topologyUsesOnlyOnlineAndProcessAllowedCpus() {
        val root = createTempDir(prefix = "phoenix-cpu-topology-")
        try {
            File(root, "online").writeText("0-3\n")
            listOf(0 to 100, 1 to 100, 2 to 200, 3 to 200).forEach { (id, capacity) ->
                val cpu = File(root, "cpu$id").apply { mkdirs() }
                File(cpu, "cpu_capacity").writeText("$capacity\n")
                val frequency = File(cpu, "cpufreq").apply { mkdirs() }
                File(frequency, "cpuinfo_max_freq").writeText(
                    if (id < 2) "1800000\n" else "2800000\n"
                )
            }
            val status = File(root, "proc-status").apply {
                writeText("Name:\tphoenixvm\nCpus_allowed_list:\t1-2,3\n")
            }

            val result = DeviceTopology.read(
                root = root,
                procStatus = status,
                cgroupFiles = emptyList(),
            )

            assertEquals(listOf(1, 2, 3), result.allowedCpuIds)
            assertEquals(listOf(1, 2, 3), result.cores.map { it.id })
            assertEquals(listOf(2, 3), result.performanceCores.map { it.id })
            assertEquals(listOf(1), result.efficiencyCores.map { it.id })
            assertTrue(result.cores.none { it.id == 0 })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun parserSupportsCpuRangesAndRejectsMalformedOrUnboundedEntries() {
        assertEquals(setOf(0, 1, 2, 5, 8, 9), DeviceTopology.parseCpuList("0-2,5,8-9"))
        assertEquals(setOf(1, 2), DeviceTopology.parseCpuList("x,1-2,9-8,4096"))
        assertTrue(DeviceTopology.parseCpuList(",,bad,-1,1024-").isEmpty())
    }

    @Test
    fun homogeneousTopologyIsNotInventedAsPerformanceOrEfficiencyCores() {
        val root = createTempDir(prefix = "phoenix-cpu-homogeneous-")
        try {
            File(root, "online").writeText("0-1")
            (0..1).forEach { id ->
                val cpu = File(root, "cpu$id").apply { mkdirs() }
                File(cpu, "cpu_capacity").writeText("200")
            }
            val status = File(root, "status").apply {
                writeText("Cpus_allowed_list:\t0-1")
            }
            val result = DeviceTopology.read(root, status, emptyList())
            assertEquals(2, result.logicalCpuCount)
            assertTrue(result.performanceCores.isEmpty())
            assertTrue(result.efficiencyCores.isEmpty())
            assertTrue(result.cores.all { it.performanceClass == PerformanceClass.UNKNOWN })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedOnlineOrAllowedMasksFailClosedInsteadOfWideningCpuAccess() {
        val root = createTempDir(prefix = "phoenix-cpu-invalid-mask-")
        try {
            (0..1).forEach { id -> File(root, "cpu$id").mkdirs() }
            File(root, "online").writeText("bad-mask")
            val status = File(root, "status").apply {
                writeText("Cpus_allowed_list:\\t0-1")
            }
            val malformedOnline = DeviceTopology.read(root, status, emptyList())
            assertTrue(malformedOnline.allowedCpuIds.isEmpty())
            assertTrue(malformedOnline.cores.isEmpty())

            File(root, "online").writeText("0-1")
            status.writeText("Cpus_allowed_list:\\tinvalid")
            val malformedAllowed = DeviceTopology.read(root, status, emptyList())
            assertTrue(malformedAllowed.allowedCpuIds.isEmpty())
            assertTrue(malformedAllowed.cores.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

}
