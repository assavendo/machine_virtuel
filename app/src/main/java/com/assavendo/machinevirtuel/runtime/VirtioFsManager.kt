package com.assavendo.machinevirtuel.runtime

import com.assavendo.machinevirtuel.vm.VmConfig
import java.io.File
import java.util.concurrent.TimeUnit

data class VirtioFsHandle(val process: Process, val socket: File)

object VirtioFsManager {
    fun start(paths: RuntimePaths, vm: VmConfig, runtimeDir: File, log: File): VirtioFsHandle? {
        if (!paths.hasVirtiofsd() || vm.sharedDirPath.isBlank()) return null
        val shared = File(vm.sharedDirPath)
        if (!shared.isDirectory) return null
        val socket = File(runtimeDir, "virtiofs-" + vm.id + ".sock")
        socket.delete()
        val command = buildList {
            add(paths.virtiofsd.absolutePath)
            add("--socket-path=" + socket.absolutePath)
            add("--shared-dir")
            add(shared.absolutePath)
            add("--log-level")
            add("warn")
        }
        val process = ProcessBuilder(command)
            .directory(runtimeDir)
            .redirectErrorStream(true)
            .start()
        Thread {
            process.inputStream.use { input ->
                log.outputStream(append = true).buffered().use { output -> input.copyTo(output) }
            }
        }.start()
        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline && !socket.exists() && process.isAlive) {
            Thread.sleep(25L)
        }
        if (!socket.exists() || !process.isAlive) {
            process.destroy()
            runCatching { process.waitFor(1, TimeUnit.SECONDS) }
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
