package com.assavendo.machinevirtuel.runtime

import com.assavendo.machinevirtuel.vm.DiskFormat
import java.io.File
import java.util.concurrent.TimeUnit

data class StorageCommandResult(val exitCode: Int, val stdout: String, val stderr: String)

object StorageManager {
    private fun run(paths: RuntimePaths, args: List<String>, timeoutMs: Long = 120_000L): StorageCommandResult {
        val process = ProcessBuilder(listOf(paths.qemuImg.absolutePath) + args)
            .redirectErrorStream(false).start()
        val out = StringBuilder()
        val err = StringBuilder()
        val outThread = Thread {
            process.inputStream.bufferedReader().useLines { it.forEach { line -> out.append(line).append('\n') } }
        }
        val errThread = Thread {
            process.errorStream.bufferedReader().useLines { it.forEach { line -> err.append(line).append('\n') } }
        }
        outThread.start(); errThread.start()
        check(process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) { "qemu-img timeout" }
        outThread.join(1000); errThread.join(1000)
        return StorageCommandResult(process.exitValue(), out.toString(), err.toString())
    }

    fun resize(paths: RuntimePaths, image: File, newSizeBytes: Long) {
        require(newSizeBytes > 0) { "Taille invalide." }
        val result = run(paths, listOf("resize", image.absolutePath, newSizeBytes.toString()))
        check(result.exitCode == 0) { result.stderr.ifBlank { result.stdout } }
    }

    fun convert(paths: RuntimePaths, source: File, sourceFormat: DiskFormat, target: File, targetFormat: DiskFormat) {
        val result = run(paths, listOf("convert", "-p", "-f", sourceFormat.name.lowercase(), "-O", targetFormat.name.lowercase(), source.absolutePath, target.absolutePath), 30 * 60_000L)
        check(result.exitCode == 0) { result.stderr.ifBlank { result.stdout } }
    }

    fun clone(paths: RuntimePaths, source: File, sourceFormat: DiskFormat, target: File, targetFormat: DiskFormat = sourceFormat) =
        convert(paths, source, sourceFormat, target, targetFormat)

    fun check(paths: RuntimePaths, image: File, format: DiskFormat): StorageCommandResult =
        run(paths, listOf("check", "-f", format.name.lowercase(), image.absolutePath), 10 * 60_000L)

    fun compact(paths: RuntimePaths, image: File, format: DiskFormat) {
        if (format == DiskFormat.RAW) return
        val tmp = File(image.parentFile, image.name + ".compact.tmp")
        try {
            convert(paths, image, format, tmp, format)
            check(tmp.renameTo(image) || runCatching {
                image.delete()
                tmp.renameTo(image)
            }.getOrDefault(false)) { "Impossible de remplacer l'image compactée." }
        } finally {
            tmp.delete()
        }
    }
}
