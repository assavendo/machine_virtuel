package com.assavendo.machinevirtuel.runtime

import java.io.File

object RootProcessLauncher {
    private val suCandidates = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/debug_ramdisk/su"
    )

    fun hasRoot(): Boolean = suCandidates.any { File(it).canExecute() }

    fun start(
        command: List<String>,
        workingDir: File,
        environment: Map<String, String>,
        preferRoot: Boolean,
    ): Process {
        if (preferRoot && hasRoot()) {
            return startViaSu(command, workingDir, environment)
        }
        return direct(command, workingDir, environment)
    }

    private fun direct(
        command: List<String>,
        workingDir: File,
        environment: Map<String, String>,
    ): Process =
        ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(false)
            .apply { environment().putAll(environment) }
            .start()

    private fun startViaSu(
        command: List<String>,
        workingDir: File,
        environment: Map<String, String>,
    ): Process {
        val shell = buildString {
            append("cd ")
            append(shellQuote(workingDir.absolutePath))
            append(" && ")
            environment.forEach { (key, value) ->
                append("export ")
                append(key)
                append("=")
                append(shellQuote(value))
                append(" && ")
            }
            append("exec ")
            append(command.joinToString(" ") { shellQuote(it) })
        }

        val suPath = suCandidates.first { File(it).canExecute() }
        return ProcessBuilder(suPath, "-c", shell)
            .directory(workingDir)
            .redirectErrorStream(false)
            .start()
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'"'"'") + "'"
}
