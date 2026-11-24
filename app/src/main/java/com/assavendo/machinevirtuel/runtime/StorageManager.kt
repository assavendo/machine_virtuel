package com.assavendo.machinevirtuel.runtime

import com.assavendo.machinevirtuel.vm.DiskFormat
import java.io.File
import java.util.concurrent.TimeUnit

data class StorageCommandResult(val exitCode: Int, val stdout: String, val stderr: String)

object StorageManager {
    private fun run(
        paths: RuntimePaths,
        args: List<String>,
        timeoutMs: Long = 120_000L,
    ): StorageCommandResult {
        require(paths.qemuImg.canExecute()) { "qemu-img n'est pas installé ou exécutable." }
        val process = ProcessBuilder(listOf(paths.qemuImg.absolutePath) + args)
            .redirectErrorStream(false)
            .start()
        val out = StringBuilder()
        val err = StringBuilder()
        val outThread = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> if (out.length < 1_000_000) out.append(line).append('\n') }
            }
        }
        val errThread = Thread {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> if (err.length < 1_000_000) err.append(line).append('\n') }
            }
        }
        outThread.start()
        errThread.start()
        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroy()
                if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
                error("qemu-img a dépassé le délai autorisé (" + timeoutMs + " ms) et a été arrêté.")
            }
            outThread.join(2_000)
            errThread.join(2_000)
            return StorageCommandResult(process.exitValue(), out.toString(), err.toString())
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            if (outThread.isAlive) outThread.interrupt()
            if (errThread.isAlive) errThread.interrupt()
        }
    }

    fun resize(paths: RuntimePaths, image: File, newSizeBytes: Long) {
        require(image.isFile && image.canWrite()) { "Image disque inaccessible." }
        require(newSizeBytes > 0) { "Taille invalide." }
        val result = run(paths, listOf("resize", image.absolutePath, newSizeBytes.toString()))
        check(result.exitCode == 0) { result.stderr.ifBlank { result.stdout } }
    }

    fun convert(
        paths: RuntimePaths,
        source: File,
        sourceFormat: DiskFormat,
        target: File,
        targetFormat: DiskFormat,
    ) {
        require(source.isFile && source.canRead()) { "Image source inaccessible." }
        require(source.canonicalFile != target.canonicalFile) { "La conversion doit écrire dans un autre fichier." }
        target.parentFile?.mkdirs()
        val result = run(
            paths,
            listOf(
                "convert", "-p", "-f", sourceFormat.name.lowercase(),
                "-O", targetFormat.name.lowercase(), source.absolutePath, target.absolutePath
            ),
            30 * 60_000L,
        )
        check(result.exitCode == 0) { result.stderr.ifBlank { result.stdout } }
        check(target.isFile && target.length() > 0L) { "La conversion n'a pas produit d'image valide." }
    }

    fun clone(
        paths: RuntimePaths,
        source: File,
        sourceFormat: DiskFormat,
        target: File,
        targetFormat: DiskFormat = sourceFormat,
    ) = convert(paths, source, sourceFormat, target, targetFormat)

    fun check(paths: RuntimePaths, image: File, format: DiskFormat): StorageCommandResult {
        require(image.isFile && image.canRead()) { "Image disque inaccessible." }
        return run(paths, listOf("check", "-f", format.name.lowercase(), image.absolutePath), 10 * 60_000L)
    }

    fun compact(paths: RuntimePaths, image: File, format: DiskFormat) {
        if (format == DiskFormat.RAW) return
        require(image.isFile && image.canWrite()) { "Image disque inaccessible." }
        val tmp = File(image.parentFile, image.name + ".compact.tmp")
        val backup = File(image.parentFile, image.name + ".compact.bak")
        require(!backup.exists()) { "Un backup de compactage existe déjà; intervention requise." }
        try {
            convert(paths, image, format, tmp, format)
            val verification = check(paths, tmp, format)
            check(verification.exitCode == 0) {
                verification.stderr.ifBlank { verification.stdout }.ifBlank {
                    "Vérification du disque compacté échouée."
                }
            }
            check(image.renameTo(backup)) { "Impossible de sauvegarder l'image avant remplacement." }
            if (!tmp.renameTo(image)) {
                val restored = backup.renameTo(image)
                check(restored) {
                    "Échec du remplacement et restauration automatique impossible; le disque original reste dans " +
                        backup.absolutePath + "."
                }
                error("Remplacement du disque compacté impossible; l'original a été restauré.")
            }
            backup.delete()
        } finally {
            tmp.delete()
        }
    }
}
