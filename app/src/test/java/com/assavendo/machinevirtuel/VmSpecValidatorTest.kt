package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.vm.*
import kotlin.test.Test
import kotlin.test.assertTrue

class VmSpecValidatorTest {
    @Test fun invalidRamIsFatal() {
        val vm = VmConfig(name = "bad", ramMb = 128)
        assertTrue(VmSpecValidator.validate(vm).any { it.field == "ramMb" && it.fatal })
    }

    @Test fun x86HardwareBackendIsRejected() {
        val vm = VmConfig(name = "bad", arch = GuestArch.X86_64, backend = BackendKind.KVM)
        assertTrue(VmSpecValidator.validate(vm).any { it.field == "backend" })
    }
    @Test fun secureBootIsLimitedToX86_64() {
        val arm = VmConfig(name = "secure-arm", arch = GuestArch.ARM64, secureBoot = true)
        assertTrue(VmSpecValidator.validate(arm).any { it.field == "secureBoot" && it.fatal })

        val x86 = VmConfig(name = "secure-x86", arch = GuestArch.X86_64, secureBoot = true)
        assertTrue(VmSpecValidator.validate(x86).none { it.field == "secureBoot" && it.fatal })
    }

    @Test
    fun duplicateBootAliasesAreRejected() {
        val vm = VmConfig(name = "duplicate-boot", bootOrder = listOf("cdrom", "iso"))
        assertTrue(VmSpecValidator.validate(vm).any { it.field == "bootOrder" && it.fatal })
    }

    @Test
    fun unknownBootDevicesAreRejected() {
        val vm = VmConfig(name = "unknown-boot", bootOrder = listOf("disk", "floppy"))
        assertTrue(VmSpecValidator.validate(vm).any { it.field == "bootOrder" && it.fatal })
    }

    @Test fun androidGuestRequiresBootArtifacts() {
        val vm = VmConfig(
            name = "android",
            guestOs = "Android guest",
            arch = GuestArch.ARM64,
            networkMode = NetworkMode.NAT,
            diskPath = "",
        )
        val issues = VmSpecValidator.validate(vm)
        assertTrue(issues.any { it.field == "androidSystemPath" && it.fatal })
        assertTrue(issues.any { it.field == "androidUserdataPath" && it.fatal })
        assertTrue(issues.any { it.field == "kernelPath" && it.fatal })
    }

    @Test fun completeAndroidGuestPassesAndroidSpecificValidation() {
        val root = createTempDir(prefix = "android-vm-")
        try {
            val system = java.io.File(root, "system.img").apply { writeText("system") }
            val userdata = java.io.File(root, "userdata.img").apply { writeText("userdata") }
            val kernel = java.io.File(root, "Image").apply { writeText("kernel") }
            val vm = VmConfig(
                name = "android",
                guestOs = "Android guest",
                arch = GuestArch.ARM64,
                diskPath = "",
                androidSystemPath = system.absolutePath,
                androidUserdataPath = userdata.absolutePath,
                kernelPath = kernel.absolutePath,
                networkMode = NetworkMode.NAT,
                androidAdbEnabled = true,
                androidAdbHostPort = 15555,
            )
            val issues = VmSpecValidator.validate(vm)
            assertTrue(
                issues.none {
                    it.field in setOf(
                        "androidSystemPath", "androidUserdataPath", "kernelPath",
                        "androidAdbEnabled", "androidAdbHostPort"
                    ) && it.fatal
                }
            )
        } finally {
            root.deleteRecursively()
        }
    }


    @Test
    fun diskSizeMustStayWithinSupportedBounds() {
        val tooSmall = VmConfig(name = "small", diskSizeGiB = 0)
        val tooLarge = VmConfig(name = "large", diskSizeGiB = 4097)
        assertTrue(VmSpecValidator.validate(tooSmall).any { it.field == "diskSizeGiB" && it.fatal })
        assertTrue(VmSpecValidator.validate(tooLarge).any { it.field == "diskSizeGiB" && it.fatal })
    }

    @Test
    fun customIsoAndExistingDiskRequireTheirMedia() {
        val custom = VmConfig(name = "custom", guestOs = "Custom ISO ARM64")
        assertTrue(VmSpecValidator.validate(custom).any { it.field == "isoPath" && it.fatal })

        val existing = VmConfig(name = "existing", guestOs = "Existing disk ARM64")
        assertTrue(VmSpecValidator.validate(existing).any { it.field == "diskPath" && it.fatal })
    }

    @Test
    fun guestArchitectureCombinationsAreValidated() {
        val windows32 = VmConfig(name = "windows32", guestOs = "Windows x86 32-bit", arch = GuestArch.X86_32)
        val dosArm = VmConfig(name = "dosarm", guestOs = "DOS / legacy ARM64", arch = GuestArch.ARM64)
        assertTrue(VmSpecValidator.validate(windows32).any { it.field == "arch" && it.fatal })
        assertTrue(VmSpecValidator.validate(dosArm).any { it.field == "arch" && it.fatal })
    }

    @Test
    fun tapNameAndSharedDirectoriesMustBeUsable() {
        val invalidTap = VmConfig(name = "tap", networkMode = NetworkMode.TAP, tapName = "tap name with spaces")
        assertTrue(VmSpecValidator.validate(invalidTap).any { it.field == "tapName" && it.fatal })

        val invalidShare = VmConfig(
            name = "share",
            sharedDirs = listOf(SharedDirectory("/path/that/does/not/exist", "invalid")),
        )
        assertTrue(VmSpecValidator.validate(invalidShare).any { it.field == "sharedDirs" && it.fatal })
    }

    @Test
    fun tapModeRejectsPortForwardsInsteadOfSilentlyDroppingThem() {
        val vm = VmConfig(
            name = "tap-forward",
            networkMode = NetworkMode.TAP,
            tapName = "tap0",
            portForwards = listOf(PortForward("tcp", 2222, 22)),
        )
        assertTrue(VmSpecValidator.validate(vm).any {
            it.field == "network" && it.fatal && it.message.contains("TAP")
        })
    }

    @Test
    fun importedDiskMustBeReadableWritableAndNonEmpty() {
        val root = createTempDir(prefix = "vm-disk-validation-")
        try {
            val empty = java.io.File(root, "empty.raw").apply { createNewFile() }
            val vm = VmConfig(name = "empty-disk", diskPath = empty.absolutePath)
            assertTrue(VmSpecValidator.validate(vm).any { it.field == "diskPath" && it.fatal })
        } finally {
            root.deleteRecursively()
        }
    }

}
