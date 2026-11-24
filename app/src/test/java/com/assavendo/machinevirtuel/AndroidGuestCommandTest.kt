package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.runtime.QemuCommandBuilder
import com.assavendo.machinevirtuel.runtime.RuntimePaths
import com.assavendo.machinevirtuel.vm.GuestArch
import com.assavendo.machinevirtuel.vm.VmConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class AndroidGuestCommandTest {
    @Test fun androidGuestBootArtifactsAreEmitted() {
        val root = createTempDir(prefix = "android-guest-")
        try {
            val paths = RuntimePaths(root)
            paths.qemu.parentFile.mkdirs()
            paths.qemu.writeText("")
            paths.qemu.setExecutable(true)
            paths.edk2Qemu.parentFile.mkdirs()
            paths.edk2Qemu.writeText("")
            val disk = File(root, "disk.raw").apply { writeText("disk") }
            val nvram = File(root, "nvram.fd").apply { writeText("nvram") }
            val kernel = File(root, "boot/kernel").apply { parentFile?.mkdirs(); writeText("kernel") }
            val initrd = File(root, "boot/initrd").apply { writeText("initrd") }
            val dtb = File(root, "boot/dtb").apply { writeText("dtb") }
            val system = File(root, "android/system.img").apply { parentFile?.mkdirs(); writeText("system") }
            val userdata = File(root, "android/userdata.img").apply { writeText("userdata") }
            val vm = VmConfig(
                name = "android", guestOs = "Android guest", arch = GuestArch.ARM64,
                diskPath = disk.absolutePath, firmwarePath = nvram.absolutePath,
                kernelPath = kernel.absolutePath, initrdPath = initrd.absolutePath,
                dtbPath = dtb.absolutePath,
                kernelCommandLine = "console=ttyAMA0 androidboot.hardware=generic",
                androidSystemPath = system.absolutePath,
                androidUserdataPath = userdata.absolutePath,
                audioEnabled = false,
            )
            val command = QemuCommandBuilder.build(
                paths, vm, File(root, "qmp.sock"), File(root, "serial.log"), File(root, "qemu.log")
            )
            assertTrue(command.containsAll(listOf("-kernel", kernel.absolutePath, "-initrd", initrd.absolutePath, "-dtb", dtb.absolutePath)))
            assertTrue(command.contains("console=ttyAMA0 androidboot.hardware=generic"))
            assertTrue(command.any { it.contains("id=android-system") })
            assertTrue(command.any { it.contains("id=android-userdata") })
        } finally {
            root.deleteRecursively()
        }
    }
}
