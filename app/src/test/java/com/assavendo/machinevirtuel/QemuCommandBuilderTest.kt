package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.runtime.QemuCommandBuilder
import com.assavendo.machinevirtuel.runtime.RuntimePaths
import com.assavendo.machinevirtuel.vm.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QemuCommandBuilderTest {
    private data class Fixture(val paths: RuntimePaths, val root: File, val nvram: File, val disk: File)

    private fun runtime(): Fixture {
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
        val nvram = File(root, "vm/nvram.fd").apply { parentFile?.mkdirs(); writeText("") }
        val disk = File(root, "vm/disk.raw").apply { writeText("fixture") }
        return Fixture(paths, root, nvram, disk)
    }

    @Test fun arm64TcgBuildsFullSystemCommandWithoutBrokenIothreadArgument() {
        val f = runtime()
        try {
            val vm = VmConfig(
                name = "test", ramMb = 2048, vCpu = 4,
                diskPath = f.disk.absolutePath, firmwarePath = f.nvram.absolutePath,
                backend = BackendKind.QEMU_TCG, gpuBackend = GpuBackend.SOFTWARE,
                audioEnabled = false, networkMode = NetworkMode.NAT, ioThreads = false,
            )
            val command = QemuCommandBuilder.build(
                f.paths, vm, File(f.root, "qmp.sock"), File(f.root, "serial.log"), File(f.root, "qemu.log")
            )
            assertTrue(command.containsAll(listOf(
                "-machine", "virt,highmem=on,gic-version=3", "-accel", "tcg,thread=multi",
                "-cpu", "max", "-smp", "4", "-m", "2048M"
            )))
            assertTrue(command.any { it.contains("virtio-tablet-pci") })
            assertTrue(command.any { it.contains("virtio-keyboard-pci") })
            assertTrue(command.any { it.contains("virtio-gpu-pci") })
            assertFalse(command.contains("iothread,id=io0"))
        } finally {
            f.root.deleteRecursively()
        }
    }

    @Test fun iothreadsAreOnlyEmittedWhenEnabled() {
        val f = runtime()
        try {
            val vm = VmConfig(
                name = "io", diskPath = f.disk.absolutePath, firmwarePath = f.nvram.absolutePath,
                ioThreads = true, audioEnabled = false,
            )
            val command = QemuCommandBuilder.build(
                f.paths, vm, File(f.root, "qmp.sock"), File(f.root, "serial.log"), File(f.root, "qemu.log")
            )
            assertTrue(command.windowed().any { it == listOf("-object", "iothread,id=io0") })
            assertTrue(command.any { it.contains("iothread=io0") })
        } finally {
            f.root.deleteRecursively()
        }
    }

    @Test fun x86UsesQ35AndRequiresX86Firmware() {
        val f = runtime()
        try {
            f.paths.edk2X86Code.parentFile.mkdirs()
            f.paths.edk2X86Code.writeText("")
            val vm = VmConfig(
                name = "x86", arch = GuestArch.X86_64, machineType = "q35",
                diskPath = f.disk.absolutePath, firmwarePath = f.nvram.absolutePath,
                backend = BackendKind.QEMU_TCG, audioEnabled = false,
            )
            val command = QemuCommandBuilder.build(
                f.paths, vm, File(f.root, "qmp.sock"), File(f.root, "serial.log"), File(f.root, "qemu.log")
            )
            assertTrue(command.containsAll(listOf("-machine", "q35", "-accel", "tcg,thread=multi", "-cpu", "max")))
            assertTrue(command.first().endsWith("qemu-system-x86_64"))
            assertTrue(command.any { it.contains(f.paths.edk2X86Code.absolutePath) })
        } finally {
            f.root.deleteRecursively()
        }
    }

    @Test fun x86NeverReceivesArm64Firmware() {
        val f = runtime()
        try {
            val vm = VmConfig(
                name = "x86", arch = GuestArch.X86_64,
                diskPath = f.disk.absolutePath, firmwarePath = f.nvram.absolutePath,
                backend = BackendKind.QEMU_TCG, audioEnabled = false,
            )
            assertFailsWith<IllegalArgumentException> {
                QemuCommandBuilder.build(
                    f.paths, vm, File(f.root, "qmp.sock"), File(f.root, "serial.log"), File(f.root, "qemu.log")
                )
            }
        } finally {
            f.root.deleteRecursively()
        }
    }

    @Test fun natForwardAndGfxstreamAreEmitted() {
        val f = runtime()
        try {
            f.paths.libDir.mkdirs()
            File(f.paths.libDir, "libgfxstream_backend.so").writeText("")
            val vm = VmConfig(
                name = "perf", diskPath = f.disk.absolutePath, firmwarePath = f.nvram.absolutePath,
                gpuBackend = GpuBackend.GFXSTREAM, audioEnabled = true,
                networkMode = NetworkMode.NAT,
                portForwards = listOf(PortForward("tcp", 2222, 22)),
            )
            val command = QemuCommandBuilder.build(
                f.paths, vm, File(f.root, "qmp.sock"), File(f.root, "serial.log"), File(f.root, "qemu.log")
            )
            assertTrue(command.contains("user,id=net0,hostfwd=tcp::2222-:22"))
            assertTrue(command.contains("egl-headless"))
            assertTrue(command.any { it.contains("virtio-gpu-rutabaga-pci") })
            assertTrue(command.any { it.contains("virtio-sound-pci,audiodev=snd0") })
        } finally {
            f.root.deleteRecursively()
        }
    }

    @Test fun keyValuePathsWithCommasAreRejected() {
        val f = runtime()
        try {
            val vm = VmConfig(
                name = "unsafe", diskPath = f.disk.absolutePath + ",bad",
                firmwarePath = f.nvram.absolutePath, audioEnabled = false,
            )
            assertFailsWith<IllegalArgumentException> {
                QemuCommandBuilder.build(
                    f.paths, vm, File(f.root, "qmp.sock"), File(f.root, "serial.log"), File(f.root, "qemu.log")
                )
            }
        } finally {
            f.root.deleteRecursively()
        }
    }

    private fun <T> List<T>.windowed(): List<List<T>> = windowed(2)
}
