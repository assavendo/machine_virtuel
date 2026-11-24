package com.assavendo.machinevirtuel.runtime

import com.assavendo.machinevirtuel.vm.VmConfig
import com.assavendo.machinevirtuel.vm.VmState
import java.io.File

object SnapshotManager {
    fun createColdSnapshot(base: File, vm: VmConfig, label: String): File {
        require(vm.state != VmState.RUNNING) { "Cold snapshot requires a stopped VM." }
        val safe = label.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val source = File(vm.diskPath)
        val nvram = File(vm.firmwarePath)
        val dir = File(base, "snapshots/" + vm.id + "/" + safe).apply { mkdirs() }
        source.copyTo(File(dir, source.name), overwrite = true)
        if (nvram.isFile) nvram.copyTo(File(dir, "nvram.fd"), overwrite = true)
        File(dir, "metadata.txt").writeText(
            "vm=" + vm.id + "\nname=" + vm.name + "\nformat=" + vm.diskFormat +
                "\ncreated=" + System.currentTimeMillis() + "\n"
        )
        return dir
    }

    fun restoreColdSnapshot(vm: VmConfig, snapshotDir: File) {
        require(vm.state != VmState.RUNNING) { "Restore requires a stopped VM." }
        val snapshotDisk = File(snapshotDir, File(vm.diskPath).name)
        check(snapshotDisk.isFile)
        snapshotDisk.copyTo(File(vm.diskPath), overwrite = true)
        val snapshotNvram = File(snapshotDir, "nvram.fd")
        if (snapshotNvram.isFile) snapshotNvram.copyTo(File(vm.firmwarePath), overwrite = true)
    }
}
