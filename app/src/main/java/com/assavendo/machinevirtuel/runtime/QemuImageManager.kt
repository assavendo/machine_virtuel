package com.assavendo.machinevirtuel.runtime

import com.assavendo.machinevirtuel.vm.DiskFormat
import java.io.File

object QemuImageManager {
    fun create(runtime: RuntimePaths, path: File, format: DiskFormat, gib: Long) {
        require(format != DiskFormat.RAW)
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
        val command = listOf(
            runtime.qemuImg.absolutePath,
            "convert", "-p",
            "-f", sourceFormat.name.lowercase(),
            "-O", targetFormat.name.lowercase(),
            source.absolutePath,
            target.absolutePath,
        )
        runTool(runtime, command, "qemu-img convert")
    }

    private fun runTool(runtime: RuntimePaths, command: List<String>, description: String) {
        val process = RootProcessLauncher.start(
            command = command,
            workingDir = runtime.root,
            environment = mapOf("LD_LIBRARY_PATH" to runtime.libDir.absolutePath),
            preferRoot = true,
        )
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        val exit = process.waitFor()
        check(exit == 0) { description + " failed: " + (output + error) }
    }
}
