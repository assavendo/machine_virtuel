package com.assavendo.machinevirtuel.runtime

import com.assavendo.machinevirtuel.vm.VmConfig
import java.io.File
import java.io.RandomAccessFile

object DiskManager {
    private const val DEFAULT_DISK_GIB = 16L
    fun ensureVmStorage(base: File, vm: VmConfig) {
        val vmDir = File(base, "vms/" + vm.id).apply { mkdirs() }
        val disk = if (vm.diskPath.isBlank()) File(vmDir, "disk.raw") else File(vm.diskPath)
        if (!disk.exists()) {
            RandomAccessFile(disk, "rw").use {
                it.setLength(DEFAULT_DISK_GIB * 1024L * 1024L * 1024L)
            }
        }
        vm.diskPath = disk.absolutePath
        vm.firmwarePath = File(vmDir, "nvram.fd").absolutePath
    }
    fun ensureNvram(vm: VmConfig, template: File) {
        val vars = File(vm.firmwarePath)
        if (!vars.exists()) template.copyTo(vars)
    }
}
