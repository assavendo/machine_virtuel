package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.runtime.QemuCommandBuilder
import com.assavendo.machinevirtuel.runtime.RuntimePaths
import com.assavendo.machinevirtuel.vm.BackendKind
import com.assavendo.machinevirtuel.vm.VmConfig
import kotlin.test.Test
import kotlin.test.assertEquals
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
        )
        val command = QemuCommandBuilder.build(
            paths,
            vm,
            File("/vm/qmp.sock"),
            File("/vm/serial.log"),
            File("/vm/qemu.log"),
        )

        assertEquals("/runtime/usr/bin/qemu-system-aarch64", command.first())
        assertTrue(command.containsAll(listOf(
            "-machine", "virt,highmem=on,gic-version=3",
            "-accel", "tcg,thread=multi",
            "-cpu", "max",
            "-smp", "4",
            "-m", "2048M",
            "-device", "virtio-blk-pci,drive=vd0,bootindex=0",
            "-device", "virtio-net-pci,netdev=net0",
            "-device", "virtio-gpu-pci",
            "-device", "virtio-tablet-pci",
            "-device", "virtio-keyboard-pci",
        )))
        assertTrue(command.any { it == "127.0.0.1:1" })
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
        )
        val command = QemuCommandBuilder.build(
            paths, vm, File("/vm/qmp.sock"), File("/vm/serial.log"), File("/vm/qemu.log")
        )
        assertTrue(command.containsAll(listOf("-accel", "kvm", "-cpu", "host")))
    }
}
