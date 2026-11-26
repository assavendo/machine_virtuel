package com.assavendo.machinevirtuel.runtime

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackendProcessLocatorTest {
    private fun writeCmdline(proc: File, pid: Int, vararg args: String) {
        val directory = File(proc, pid.toString()).apply { mkdirs() }
        val bytes = args.joinToString(separator = "\u0000", postfix = "\u0000")
        File(directory, "cmdline").writeBytes(bytes.toByteArray())
    }

    @Test
    fun resolvesTheActualBackendRatherThanItsSuWrapperAmongMultipleVms() {
        val proc = createTempDir(prefix = "phoenix-proc-fixture-")
        try {
            // A su command line may contain the full child command, but argv[0] is still su.
            writeCmdline(
                proc,
                60,
                "/system/bin/su",
                "-c",
                "/opt/qemu-system-aarch64 -qmp unix:/run/vm-b.qmp",
            )
            writeCmdline(
                proc,
                120,
                "/opt/qemu-system-aarch64",
                "-qmp",
                "unix:/run/vm-a.qmp",
            )
            writeCmdline(
                proc,
                240,
                "/opt/qemu-system-aarch64",
                "-qmp",
                "unix:/run/vm-b.qmp",
            )
            writeCmdline(
                proc,
                360,
                "/opt/crosvm",
                "run",
                "--socket",
                "/run/vm-c.sock",
            )
            writeCmdline(
                proc,
                480,
                "/opt/qemu-system-aarch64",
                "-qmp",
                "unix:/run/other.qmp",
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
            writeCmdline(
                proc,
                120,
                "/opt/qemu-system-aarch64",
                "-qmp",
                "unix:/run/vm.qmp",
            )
            assertNull(BackendProcessLocator.find(proc, "qemu-system", "/run/missing.qmp"))
            assertNull(BackendProcessLocator.find(proc, "crosvm", "/run/vm.qmp"))
            assertNull(BackendProcessLocator.find(proc, "", "/run/vm.qmp"))
        } finally {
            proc.deleteRecursively()
        }
    }
}
