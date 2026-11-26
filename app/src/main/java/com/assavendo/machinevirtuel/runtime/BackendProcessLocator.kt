package com.assavendo.machinevirtuel.runtime

import java.io.File

/**
 * Resolves the actual backend PID from /proc without trusting the launcher Process PID.
 * Matching argv[0] and the backend-specific control socket avoids selecting a su wrapper
 * or an unrelated QEMU/crosvm instance when multiple VMs run simultaneously.
 */
internal object BackendProcessLocator {
    fun find(
        procRoot: File = File("/proc"),
        executableNeedle: String,
        controlPath: String,
    ): Long? {
        if (executableNeedle.isBlank() || controlPath.isBlank()) return null
        val entries = procRoot.listFiles() ?: return null
        return entries.asSequence()
            .mapNotNull { entry ->
                val pid = entry.name.toLongOrNull()?.takeIf { it > 0L } ?: return@mapNotNull null
                val commandLine = runCatching {
                    File(entry, "cmdline").readBytes().toString(Charsets.UTF_8)
                }.getOrNull() ?: return@mapNotNull null
                val arguments = commandLine.split('\u0000').filter { it.isNotBlank() }
                val executable = arguments.firstOrNull() ?: return@mapNotNull null
                if (executable.contains(executableNeedle) && arguments.any { it.contains(controlPath) }) {
                    pid
                } else {
                    null
                }
            }
            .minOrNull()
    }
}
