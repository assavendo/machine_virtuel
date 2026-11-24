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

}
