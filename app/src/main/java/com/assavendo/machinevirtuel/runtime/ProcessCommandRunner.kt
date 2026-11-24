package com.assavendo.machinevirtuel.runtime

import java.io.File
import java.util.concurrent.TimeUnit

data class ProcessCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

object ProcessCommandRunner {
    fun run(
        command: List<String>,
        workingDir: File,
        environment: Map<String, String> = emptyMap(),
        preferRoot: Boolean = false,
        timeoutMs: Long = 120_000L,
        maxOutputChars: Int = 512_000,
    ): ProcessCommandResult {
        require(timeoutMs > 0L)
        require(maxOutputChars > 0)
        val process = RootProcessLauncher.start(command, workingDir, environment, preferRoot)
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutThread = Thread({
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> synchronized(stdout) {
                    if (stdout.length < maxOutputChars) stdout.append(line).append('\n')
                } }
            }
        }, "phoenixvm-stdout")
        val stderrThread = Thread({
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> synchronized(stderr) {
                    if (stderr.length < maxOutputChars) stderr.append(line).append('\n')
                } }
            }
        }, "phoenixvm-stderr")
        stdoutThread.start()
        stderrThread.start()
        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroy()
                if (!process.waitFor(1L, TimeUnit.SECONDS)) process.destroyForcibly()
                error("Processus expiré après " + timeoutMs + " ms: " + command.firstOrNull())
            }
            stdoutThread.join(2_000L)
            stderrThread.join(2_000L)
            return ProcessCommandResult(process.exitValue(), stdout.toString(), stderr.toString())
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            if (stdoutThread.isAlive) stdoutThread.interrupt()
            if (stderrThread.isAlive) stderrThread.interrupt()
        }
    }
}
