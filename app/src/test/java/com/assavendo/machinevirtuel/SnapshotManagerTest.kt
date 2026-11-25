package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.runtime.SnapshotManager
import com.assavendo.machinevirtuel.vm.VmConfig
import com.assavendo.machinevirtuel.vm.VmState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class SnapshotManagerTest {
    private data class Fixture(val root: File, val vm: VmConfig)

    private fun fixture(): Fixture {
        val root = createTempDir(prefix = "phoenix-snapshot-test-")
        val vmId = "test-vm-0001"
        val vmDir = File(root, "vms/" + vmId).apply { mkdirs() }
        val disk = File(vmDir, "disk.raw").apply { writeText("guest-disk-state-one") }
        val nvram = File(vmDir, "nvram.fd").apply { writeText("uefi-state-one") }
        val vm = VmConfig(
            id = vmId,
            name = "snapshot-test",
            diskPath = disk.absolutePath,
            firmwarePath = nvram.absolutePath,
            state = VmState.OFF,
        )
        return Fixture(root, vm)
    }

    @Test
    fun coldSnapshotAndRestoreRejectErrorStateUntilOffIsConfirmed() {
        val f = fixture()
        try {
            val errorVm = f.vm.copy(state = VmState.ERROR)
            assertFails {
                SnapshotManager.createColdSnapshot(f.root, errorVm, "must-not-copy")
            }
            assertFails {
                SnapshotManager.restoreColdSnapshot(errorVm, File(f.root, "missing-snapshot"))
            }
        } finally {
            f.root.deleteRecursively()
        }
    }

    @Test fun createsListsAndRestoresDiskAndNvramWithIntegrityChecks() {
        val f = fixture()
        try {
            val snapshot = SnapshotManager.createColdSnapshot(f.root, f.vm, "before update")
            assertEquals(1, SnapshotManager.list(f.root, f.vm.id).size)
            File(f.vm.diskPath).writeText("guest-disk-state-two")
            File(f.vm.firmwarePath).writeText("uefi-state-two")
            SnapshotManager.restoreColdSnapshot(f.vm, snapshot)
            assertEquals("guest-disk-state-one", File(f.vm.diskPath).readText())
            assertEquals("uefi-state-one", File(f.vm.firmwarePath).readText())
        } finally {
            f.root.deleteRecursively()
        }
    }

    @Test fun refusesCorruptedSnapshotWithoutChangingCurrentDisk() {
        val f = fixture()
        try {
            val snapshot = SnapshotManager.createColdSnapshot(f.root, f.vm, "known-good")
            val snapshotDisk = snapshot.listFiles()!!.first { it.name.endsWith(".snapshot") }
            snapshotDisk.appendText("tamper")
            File(f.vm.diskPath).writeText("current-disk")
            assertFails { SnapshotManager.restoreColdSnapshot(f.vm, snapshot) }
            assertEquals("current-disk", File(f.vm.diskPath).readText())
        } finally {
            f.root.deleteRecursively()
        }
    }

    @Test fun protectedSnapshotsCannotBeDeleted() {
        val f = fixture()
        try {
            val snapshot = SnapshotManager.createColdSnapshot(f.root, f.vm, "protected")
            SnapshotManager.protect(snapshot, true)
            assertFails { SnapshotManager.delete(snapshot) }
            assertTrue(snapshot.isDirectory)
        } finally {
            f.root.deleteRecursively()
        }
    }

    @Test fun snapshotsCannotBeRestoredIntoAnotherVm() {
        val f = fixture()
        try {
            val snapshot = SnapshotManager.createColdSnapshot(f.root, f.vm, "vm-a")
            val other = f.vm.copy(id = "test-vm-0002")
            assertFails { SnapshotManager.restoreColdSnapshot(other, snapshot) }
        } finally {
            f.root.deleteRecursively()
        }
    }
}
