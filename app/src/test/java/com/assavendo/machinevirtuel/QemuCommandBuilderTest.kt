package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.runtime.QemuCommandBuilder
import com.assavendo.machinevirtuel.runtime.RuntimePaths
import com.assavendo.machinevirtuel.vm.*
import kotlin.test.Test
import kotlin.test.assertTrue
import java.io.File

class QemuCommandBuilderTest {
    private fun runtime(): Triple<RuntimePaths, File, File> {
        val root = createTempDir(prefix = "runtime-")
        val paths = RuntimePaths(root)
        paths.qemu.parentFile.mkdirs()
        paths.qemu.writeText("")
        paths.qemu.setExecutable(true)
        paths.qemuX86.parentFile.mkdirs()
        paths.qemuX86.writeText("")
        paths.qemuX86.setExecutable(true)
        paths.edk2Qemu.parentFile.mkdirs()
        paths.edk2Qemu.writeText("")
        val nvram = createTempDir(prefix = "vm-").resolve("nvram.fd")
        nvram.writeText("")
        return Triple(paths, root, nvram)
    }

    @Test fun arm64TcgBuildsFullSystemCommand() {
        val (paths, root, nvram) = runtime()
        try {
            val vm = VmConfig(
                name = "test", ramMb = 2048, vCpu = 4,
                diskPath = "/vm/disk.raw", firmwarePath = nvram.absolutePath,
                backend = BackendKind.QEMU_TCG, gpuBackend = GpuBackend.SOFTWARE,
                audioEnabled = false, networkMode = NetworkMode.NAT,
            )
            val command = QemuCommandBuilder.build(paths, vm, File("/vm/qmp.sock"), File("/vm/serial.log"), File("/vm/qemu.log"))
            assertTrue(command.containsAll(listOf("-machine", "virt,highmem=on,gic-version=3", "-accel", "tcg,thread=multi", "-cpu", "max", "-smp", "4", "-m", "2048M")))
            assertTrue(command.any { it.contains("virtio-tablet-pci") })
            assertTrue(command.any { it.contains("virtio-keyboard-pci") })
            assertTrue(command.any { it.contains("virtio-gpu-pci") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun x86UsesQ35AndX86Qemu() {
        val (paths, root, nvram) = runtime()
        try {
            val vm = VmConfig(
                name = "x86", arch = GuestArch.X86_64, machineType = "q35",
                diskPath = "/vm/disk.raw", firmwarePath = nvram.absolutePath,
                backend = BackendKind.QEMU_TCG, audioEnabled = false,
            )
            val command = QemuCommandBuilder.build(paths, vm, File("/vm/qmp.sock"), File("/vm/serial.log"), File("/vm/qemu.log"))
            assertTrue(command.containsAll(listOf("-machine", "q35", "-accel", "tcg,thread=multi", "-cpu", "max")))
            assertTrue(command.first().endsWith("qemu-system-x86_64"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun natForwardAndGfxstreamAreEmitted() {
        val (paths, root, nvram) = runtime()
        try {
            paths.libDir.mkdirs()
            File(paths.libDir, "libgfxstream_backend.so").writeText("")
            val vm = VmConfig(
                name = "perf", diskPath = "/vm/disk.raw", firmwarePath = nvram.absolutePath,
                gpuBackend = GpuBackend.GFXSTREAM, audioEnabled = true,
                networkMode = NetworkMode.NAT,
                portForwards = listOf(PortForward("tcp", 2222, 22)),
            )
            val command = QemuCommandBuilder.build(paths, vm, File("/vm/qmp.sock"), File("/vm/serial.log"), File("/vm/qemu.log"))
            assertTrue(command.contains("user,id=net0,hostfwd=tcp::2222-:22"))
            assertTrue(command.contains("egl-headless"))
            assertTrue(command.any { it.contains("virtio-gpu-rutabaga-pci") })
            assertTrue(command.any { it.contains("virtio-sound-pci,audiodev=snd0") })
        } finally {
            root.deleteRecursively()
        }
    }
}
