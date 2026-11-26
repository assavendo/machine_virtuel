package com.assavendo.machinevirtuel.runtime

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackendProcessLocatorTest {
    @Test
    fun resolvesTheBackendMatchingItsControlSocketAmongMultipleVMs() {
        val proc = createTempDir(prefix = "phoenix-proc-fixture-")
        try {
            val qemuOld = File(proc, "120").apply { mkdirs() }
            File(qemuOld, "cmdline").writeBytes(
                "/opt/qemu-system-aarch64\\u0000-qmp\\u0000unix:/run/vm-a.qmp\\u0000".toByteArray()
            )
            val qemuTarget = File(proc, "240").apply { mkdirs() }
            File(qemuTarget, "cmdline").writeBytes(
                "/opt/qemu-system-aarch64\\u0000-qmp\\u0000unix:/run/vm-b.qmp\\u0000".toByteArray()
            )
            val crosvm = File(proc, "360").apply { mkdirs() }
            File(crosvm, "cmdline").writeBytes(
                "/opt/crosvm\\u0000run\\u0000--socket\\u0000/run/vm-c.sock\\u0000".toByteArray()
            )
            val unrelated = File(proc, "480").apply { mkdirs() }
            File(unrelated, "cmdline").writeBytes(
                "/opt/qemu-system-aarch64\\u0000-qmp\\u0000unix:/run/other.qmp\\u0000".toByteArray()
            )

            assertEquals(
                240L,
                BackendProcessLocator.find(proc, "qemu-system", "/run/vm-b.qmp"),
            )
            assertEquals(
                360L,
                BackendProcessLocator.find(proc, "crosvm", "/run/vm-c.sock"),
            )
        } finally {
            proc.deleteRecursively()
        }
    }

    @Test
    fun missingExecutableOrControlSocketMatchReturnsNull() {
        val proc = createTempDir(prefix = "phoenix-proc-missing-")
        try {
            val process = File(proc, "120").apply { mkdirs() }
            File(process, "cmdline").writeBytes(
                "/opt/qemu-system-aarch64\\u0000-qmp\\u0000unix:/run/vm.qmp\\u0000".toByteArray()
            )
            assertNull(BackendProcessLocator.find(proc, "qemu-system", "/run/missing.qmp"))
            assertNull(BackendProcessLocator.find(proc, "crosvm", "/run/vm.qmp"))
            assertNull(BackendProcessLocator.find(proc, "", "/run/vm.qmp"))
        } finally {
            proc.deleteRecursively()
        }
    }
}
