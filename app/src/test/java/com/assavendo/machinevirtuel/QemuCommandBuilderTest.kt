package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.runtime.QemuCommandBuilder
import com.assavendo.machinevirtuel.runtime.RuntimePaths
import com.assavendo.machinevirtuel.vm.BackendKind
import com.assavendo.machinevirtuel.vm.GpuBackend
import com.assavendo.machinevirtuel.vm.NetworkMode
import com.assavendo.machinevirtuel.vm.VmConfig
import kotlin.test.Test
import kotlin.test.assertTrue
import java.io.File

class QemuCommandBuilderTest {
    @Test
    fun tcgBuildsFullSystemCommand() {
        val paths = RuntimePaths(File("/runtime"))
        val vm = VmConfig(
            name = "test",
            ramMb = 2048,
            vCpu = 4,
            diskPath = "/vm/disk.raw",
            firmwarePath = "/vm/nvram.fd",
            backend = BackendKind.QEMU_TCG,
            gpuBackend = GpuBackend.SOFTWARE,
            audioEnabled = false,
            networkMode = NetworkMode.NAT,
        )
        val command = QemuCommandBuilder.build(
            paths, vm, File("/vm/qmp.sock"), File("/vm/serial.log"), File("/vm/qemu.log")
        )
        assertTrue(command.containsAll(listOf(
            "-machine", "virt,highmem=on,gic-version=3",
            "-accel", "tcg,thread=multi",
            "-cpu", "max",
            "-smp", "4",
            "-m", "2048M",
            "-device", "virtio-blk-pci,drive=vd0,iothread=io0,disable-legacy=on,disable-modern=off,bootindex=0",
            "-device", "virtio-rng-pci,disable-legacy=on,disable-modern=off",
            "-device", "virtio-multitouch-pci,disable-legacy=on,disable-modern=off",
            "-device", "virtio-keyboard-pci,disable-legacy=on,disable-modern=off",
            "-device", "virtio-gpu-pci,disable-legacy=on,disable-modern=off,xres=1280,yres=720,edid=on",
        )))
        assertTrue(command.any { it.startsWith("127.0.0.1:") })
        assertTrue(command.contains("-qmp"))
    }

    @Test
    fun kvmUsesHardwareAcceleration() {
        val paths = RuntimePaths(File("/runtime"))
        val vm = VmConfig(
            name = "kvm",
            diskPath = "/vm/disk.raw",
            firmwarePath = "/vm/nvram.fd",
            backend = BackendKind.KVM,
            audioEnabled = false,
        )
        val command = QemuCommandBuilder.build(
            paths, vm, File("/vm/qmp.sock"), File("/vm/serial.log"), File("/vm/qemu.log")
        )
        assertTrue(command.containsAll(listOf("-accel", "kvm", "-cpu", "host")))
    }

    @Test
    fun natForwardsPortsAndGfxstreamSelectsHeadlessRenderer() {
        val root = File("/runtime")
        root.resolve("usr/lib").mkdirs()
        root.resolve("usr/lib/libgfxstream_backend.so").createNewFile()
        val paths = RuntimePaths(root)
        val vm = VmConfig(
            name = "perf",
            diskPath = "/vm/disk.raw",
            firmwarePath = "/vm/nvram.fd",
            backend = BackendKind.QEMU_TCG,
            gpuBackend = GpuBackend.GFXSTREAM,
            audioEnabled = true,
            networkMode = NetworkMode.NAT,
            portForwards = listOf(
                com.assavendo.machinevirtuel.vm.PortForward("tcp", 2222, 22)
            ),
        )
        val command = QemuCommandBuilder.build(
            paths, vm, File("/vm/qmp.sock"), File("/vm/serial.log"), File("/vm/qemu.log")
        )
        assertTrue(command.contains("user,id=net0,hostfwd=tcp::2222-:22"))
        assertTrue(command.contains("egl-headless"))
        assertTrue(command.any { it.contains("virtio-gpu-rutabaga-pci") })
        assertTrue(command.any { it.contains("virtio-sound-pci,audiodev=snd0") })
    }
}
