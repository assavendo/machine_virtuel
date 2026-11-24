package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.runtime.DiskManager
import com.assavendo.machinevirtuel.vm.VmConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class DiskManagerTest {
    @Test fun rawDiskUsesConfiguredCapacityAndCreatesFirmwarePath() {
        val root = createTempDir(prefix = "phoenix-disk-test-")
        try {
            val vm = VmConfig(name = "capacity-test", diskSizeGiB = 1)
            DiskManager.ensureVmStorage(root, vm)
            assertEquals(1024L * 1024L * 1024L, File(vm.diskPath).length())
            assertEquals("nvram.fd", File(vm.firmwarePath).name)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun rejectsOutOfRangeDiskCapacityBeforeAllocating() {
        val root = createTempDir(prefix = "phoenix-disk-test-")
        try {
            val vm = VmConfig(name = "bad-capacity", diskSizeGiB = 0)
            assertFails { DiskManager.ensureVmStorage(root, vm) }
            assertEquals(false, File(root, "vms/" + vm.id).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun nvramTemplateIsCopiedIntoVmState() {
        val root = createTempDir(prefix = "phoenix-uefi-test-")
        try {
            val vm = VmConfig(name = "uefi-test")
            val template = File(root, "template.fd").apply { writeText("firmware-data") }
            vm.firmwarePath = File(root, "vm/nvram.fd").absolutePath
            DiskManager.ensureNvram(vm, template)
            assertEquals("firmware-data", File(vm.firmwarePath).readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
