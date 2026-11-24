package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.runtime.CrosvmCommandBuilder
import com.assavendo.machinevirtuel.runtime.RuntimePaths
import com.assavendo.machinevirtuel.vm.BackendKind
import com.assavendo.machinevirtuel.vm.GuestArch
import com.assavendo.machinevirtuel.vm.VmConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrosvmCommandBuilderTest {
    private data class Fixture(
        val paths: RuntimePaths,
        val root: File,
        val disk: File,
        val firmware: File,
    )

    private fun fixture(): Fixture {
        val root = createTempDir(prefix = "crosvm-")
        val paths = RuntimePaths(root)
        paths.crosvm.parentFile.mkdirs()
        paths.crosvm.writeText("")
        paths.crosvm.setExecutable(true, false)
        paths.edk2Qemu.parentFile.mkdirs()
        paths.edk2Qemu.writeText("uefi")
        val disk = File(root, "disk.raw").apply { writeText("guest") }
        val firmware = File(root, "nvram.fd").apply { writeText("vars") }
        return Fixture(paths, root, disk, firmware)
    }

    @Test
    fun gunyahCommandIsFullSystemAndUsesControlSocket() {
        val f = fixture()
        try {
            val vm = VmConfig(
                name = "gunyah",
                arch = GuestArch.ARM64,
                backend = BackendKind.GUNYAH,
                diskPath = f.disk.absolutePath,
                firmwarePath = f.firmware.absolutePath,
                audioEnabled = true,
                networkMode = com.assavendo.machinevirtuel.vm.NetworkMode.DISABLED,
            )
            val socket = File(f.root, "control.sock")
            val command = CrosvmCommandBuilder.build(f.paths, vm, socket)

            assertEquals(f.paths.crosvm.absolutePath, command.first())
            assertTrue(command.contains("run"))
            assertTrue(command.contains("--hypervisor"))
            assertTrue(command.contains("gunyah"))
            assertTrue(command.contains("--mem"))
            assertTrue(command.contains("size=2048"))
            assertTrue(command.contains("--cpus"))
            assertTrue(command.contains("num-cores=2"))
            assertTrue(command.contains("--block"))
            assertTrue(command.contains("path=" + f.disk.canonicalPath))
            assertTrue(command.contains("--bios"))
            assertTrue(command.contains(f.paths.edk2Qemu.canonicalPath))
            assertTrue(command.contains("--socket"))
            assertTrue(command.contains(socket.absolutePath))
            assertTrue(command.contains("--virtio-snd"))
            assertTrue(command.contains("backend=aaudio,num_output_devices=1,num_input_devices=1"))
        } finally {
            f.root.deleteRecursively()
        }
    }

    @Test
    fun sharedDirectoryUsesCrosvmNativeVirtioFs() {
        val f = fixture()
        try {
            val shared = File(f.root, "shared").apply { mkdirs() }
            val vm = VmConfig(
                name = "shared",
                arch = GuestArch.ARM64,
                backend = BackendKind.GUNYAH,
                diskPath = f.disk.absolutePath,
                firmwarePath = f.firmware.absolutePath,
                sharedDirPath = shared.absolutePath,
                sharedDirTag = "shared",
                audioEnabled = false,
            )
            val command = CrosvmCommandBuilder.build(f.paths, vm, File(f.root, "control.sock"))
            val i = command.indexOf("--shared-dir")
            assertTrue(i >= 0)
            assertEquals(shared.canonicalPath + ":shared:type=fs", command[i + 1])
        } finally {
            f.root.deleteRecursively()
        }
    }

    @Test
    fun directKernelAddsInitrdAndParams() {
        val f = fixture()
        try {
            val kernel = File(f.root, "Image").apply { writeText("kernel") }
            val initrd = File(f.root, "initrd").apply { writeText("initrd") }
            val vm = VmConfig(
                name = "kernel",
                arch = GuestArch.ARM64,
                backend = BackendKind.GENIEZONE,
                diskPath = f.disk.absolutePath,
                firmwarePath = "",
                kernelPath = kernel.absolutePath,
                initrdPath = initrd.absolutePath,
                kernelCommandLine = "console=ttyAMA0 root=/dev/vda",
                audioEnabled = false,
                networkMode = com.assavendo.machinevirtuel.vm.NetworkMode.DISABLED,
            )
            val command = CrosvmCommandBuilder.build(f.paths, vm, File(f.root, "control.sock"))
            assertTrue(command.contains(kernel.absolutePath))
            assertTrue(command.contains("--initrd"))
            assertTrue(command.contains(initrd.absolutePath))
            assertTrue(command.contains("--params"))
            assertTrue(command.contains("console=ttyAMA0 root=/dev/vda"))
            assertTrue(command.contains("geniezone"))
        } finally {
            f.root.deleteRecursively()
        }
    }

    @Test
    fun firmwareCodeIsRequiredWithoutDirectKernel() {
        val f = fixture()
        try {
            f.paths.edk2Qemu.delete()
            val vm = VmConfig(
                name = "no-firmware",
                arch = GuestArch.ARM64,
                backend = BackendKind.KVM,
                diskPath = f.disk.absolutePath,
                firmwarePath = "",
                audioEnabled = false,
            )
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                CrosvmCommandBuilder.build(f.paths, vm, File(f.root, "control.sock"))
            }
        } finally {
            f.root.deleteRecursively()
        }
    }
}
