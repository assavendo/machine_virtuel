    @Test fun androidGuestBootArtifactsArePersistedIntoTheFullSystemCommand() {
        val f = runtime()
        try {
            val kernel = File(f.root, "boot/kernel").apply { parentFile?.mkdirs(); writeText("kernel") }
            val initrd = File(f.root, "boot/initrd").apply { writeText("initrd") }
            val dtb = File(f.root, "boot/dtb").apply { writeText("dtb") }
            val system = File(f.root, "android/system.img").apply { parentFile?.mkdirs(); writeText("system") }
            val userdata = File(f.root, "android/userdata.img").apply { writeText("userdata") }
            val vm = VmConfig(
                name = "android", guestOs = "Android guest", arch = GuestArch.ARM64,
                diskPath = f.disk.absolutePath, firmwarePath = f.nvram.absolutePath,
                kernelPath = kernel.absolutePath, initrdPath = initrd.absolutePath,
                dtbPath = dtb.absolutePath, kernelCommandLine = "console=ttyAMA0 androidboot.hardware=generic",
                androidSystemPath = system.absolutePath, androidUserdataPath = userdata.absolutePath,
                audioEnabled = false,
            )
            val command = QemuCommandBuilder.build(
                f.paths, vm, File(f.root, "qmp.sock"), File(f.root, "serial.log"), File(f.root, "qemu.log")
            )
            assertTrue(command.containsAll(listOf("-kernel", kernel.absolutePath, "-initrd", initrd.absolutePath, "-dtb", dtb.absolutePath)))
            assertTrue(command.contains("console=ttyAMA0 androidboot.hardware=generic"))
            assertTrue(command.any { it.contains("id=android-system") })
            assertTrue(command.any { it.contains("id=android-userdata") })
        } finally {
            f.root.deleteRecursively()
        }
    }

