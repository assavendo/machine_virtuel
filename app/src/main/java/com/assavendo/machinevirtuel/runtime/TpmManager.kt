package com.assavendo.machinevirtuel.runtime

import com.assavendo.machinevirtuel.vm.GuestArch
import com.assavendo.machinevirtuel.vm.VmConfig
import java.io.File
import java.util.concurrent.TimeUnit

data class TpmHandle(val process: Process, val socket: File)

object TpmManager {
    fun start(paths: RuntimePaths, vm: VmConfig, runtimeDir: File, log: File): TpmHandle? {
        if (!vm.tpmEnabled) return null
        check(paths.hasSwtpm()) {
            "TPM demandé mais swtpm n'est pas présent dans le runtime vérifié."
        }
        val stateDir = File(
            if (vm.tpmStatePath.isBlank()) runtimeDir else vm.tpmStatePath
        ).apply { mkdirs() }
        val socket = File(stateDir, "swtpm-sock")
        socket.delete()
        val command = listOf(
            paths.swtpm.absolutePath,
            "socket",
            "--tpmstate", "dir=" + stateDir.absolutePath,
            "--ctrl", "type=unixio,path=" + socket.absolutePath,
            "--tpm2",
            "--log", "level=20",
        )
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        Thread {
            process.inputStream.use { input ->
                log.outputStream(append = true).buffered().use { output -> input.copyTo(output) }
            }
        }.start()

        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline && !socket.exists() && process.isAlive) Thread.sleep(25L)
        if (!socket.exists() || !process.isAlive) {
            process.destroy()
            runCatching { process.waitFor(1, TimeUnit.SECONDS) }
            error("swtpm n'a pas créé son socket TPM.")
        }
        return TpmHandle(process, socket)
    }

    fun appendQemuDevice(args: MutableList<String>, handle: TpmHandle, arch: GuestArch) {
        args += "-chardev"
        args += "socket,id=chrtpm,path=" + handle.socket.absolutePath
        args += "-tpmdev"
        args += "emulator,id=tpm0,chardev=chrtpm"
        args += "-device"
        args += if (arch == GuestArch.ARM64) "tpm-tis-device,tpmdev=tpm0" else "tpm-tis,tpmdev=tpm0"
    }

    fun stop(handle: TpmHandle?) {
        if (handle == null) return
        handle.process.destroy()
        runCatching { if (!handle.process.waitFor(2, TimeUnit.SECONDS)) handle.process.destroyForcibly() }
    }
}
