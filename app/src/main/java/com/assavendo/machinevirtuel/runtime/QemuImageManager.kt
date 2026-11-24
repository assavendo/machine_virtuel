package com.assavendo.machinevirtuel.runtime

import com.assavendo.machinevirtuel.vm.DiskFormat
import java.io.File

object QemuImageManager {
    fun create(runtime: RuntimePaths, path: File, format: DiskFormat, gib: Long) {
        require(format != DiskFormat.RAW)
        require(gib in 1L..4096L)
        path.parentFile?.mkdirs()
        val command = listOf(
            runtime.qemuImg.absolutePath,
            "create",
            "-f", format.name.lowercase(),
            path.absolutePath,
            gib.toString() + "G",
        )
        runTool(runtime, command, "qemu-img create")
    }

    fun convert(
        runtime: RuntimePaths,
        source: File,
        sourceFormat: DiskFormat,
        target: File,
        targetFormat: DiskFormat,
    ) {
        require(source.isFile && source.canRead())
        require(source.canonicalFile != target.canonicalFile)
        target.parentFile?.mkdirs()
        val command = listOf(
            runtime.qemuImg.absolutePath,
            "convert", "-p",
            "-f", sourceFormat.name.lowercase(),
            "-O", targetFormat.name.lowercase(),
            source.absolutePath,
            target.absolutePath,
        )
        runTool(runtime, command, "qemu-img convert", 30L * 60L * 1000L)
        check(target.isFile && target.length() > 0L) { "qemu-img convert did not create a valid image." }
    }

    private fun runTool(
        runtime: RuntimePaths,
        command: List<String>,
        description: String,
        timeoutMs: Long = 120_000L,
    ) {
        require(runtime.qemuImg.canExecute()) { "qemu-img is unavailable." }
        val result = ProcessCommandRunner.run(
            command = command,
            workingDir = runtime.root,
            environment = mapOf("LD_LIBRARY_PATH" to runtime.libDir.absolutePath),
            preferRoot = false,
            timeoutMs = timeoutMs,
        )
        check(result.exitCode == 0) {
            description + " failed (exit " + result.exitCode + "): " + result.stderr.ifBlank { result.stdout }
        }
    }
}
