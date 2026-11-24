package com.assavendo.machinevirtuel.runtime

import com.assavendo.machinevirtuel.vm.VmConfig
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class VirtioFsHandle(val process: Process, val socket: File)

object VirtioFsManager {
    fun start(paths: RuntimePaths, vm: VmConfig, runtimeDir: File, log: File): VirtioFsHandle? {
        if (!paths.hasVirtiofsd() || vm.sharedDirPath.isBlank()) return null
        val shared = File(vm.sharedDirPath)
        if (!shared.isDirectory) return null
        if (vm.sharedDirReadOnly) {
            // Current verified virtiofsd invocation has no read-only export policy wired.
            // Force the caller onto its 9p read-only-capable fallback instead.
            return null
        }
        val socket = File(runtimeDir, "virtiofs-" + vm.id + ".sock")
        socket.delete()
        val command = listOf(
            paths.virtiofsd.absolutePath,
            "--socket-path=" + socket.absolutePath,
            "--shared-dir",
            shared.absolutePath,
            "--log-level",
            "warn",
        )
        val process = ProcessBuilder(command)
            .directory(runtimeDir)
            .redirectErrorStream(true)
            .also {
                val current = it.environment()["LD_LIBRARY_PATH"].orEmpty()
                it.environment()["LD_LIBRARY_PATH"] =
                    paths.virtiofsdLibDir.absolutePath +
                        if (current.isBlank()) "" else File.pathSeparator + current
            }
            .start()
        Thread {
            process.inputStream.use { input ->
                FileOutputStream(log, true).buffered().use { output -> input.copyTo(output) }
            }
        }.start()
        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline && !socket.exists() && process.isAlive) {
            Thread.sleep(25L)
        }
        if (!socket.exists() || !process.isAlive) {
            process.destroy()
            runCatching { process.waitFor(1, TimeUnit.SECONDS) }
            if (process.isAlive) process.destroyForcibly()
            socket.delete()
            return null
        }
        return VirtioFsHandle(process, socket)
    }

    fun stop(handle: VirtioFsHandle?) {
        if (handle == null) return
        handle.process.destroy()
        runCatching { if (!handle.process.waitFor(2, TimeUnit.SECONDS)) handle.process.destroyForcibly() }
        handle.socket.delete()
    }
}
