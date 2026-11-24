package com.assavendo.machinevirtuel.runtime

import com.assavendo.machinevirtuel.vm.DiskFormat
import com.assavendo.machinevirtuel.vm.VmConfig
import java.io.File
import java.io.RandomAccessFile

object DiskManager {
    private const val DEFAULT_DISK_GIB = 16L

    fun ensureVmStorage(base: File, vm: VmConfig, runtime: RuntimePaths? = null) {
        val vmDir = File(base, "vms/" + vm.id).apply { mkdirs() }
        val disk = if (vm.diskPath.isBlank()) {
            File(vmDir, "disk." + extension(vm.diskFormat))
        } else {
            File(vm.diskPath)
        }
        if (!disk.exists()) {
            if (vm.diskFormat == DiskFormat.RAW) {
                RandomAccessFile(disk, "rw").use {
                    it.setLength(DEFAULT_DISK_GIB * 1024L * 1024L * 1024L)
                }
            } else {
                requireNotNull(runtime) { "A verified QEMU runtime is required for non-RAW disk creation." }
                QemuImageManager.create(runtime, disk, vm.diskFormat, DEFAULT_DISK_GIB)
            }
        }
        vm.diskPath = disk.absolutePath
        vm.firmwarePath = File(vmDir, "nvram.fd").absolutePath
    }

    fun ensureNvram(vm: VmConfig, template: File) {
        val vars = File(vm.firmwarePath)
        if (!vars.exists()) template.copyTo(vars)
    }

    fun extension(format: DiskFormat): String = when (format) {
        DiskFormat.RAW -> "raw"
        DiskFormat.QCOW2 -> "qcow2"
        DiskFormat.VHDX -> "vhdx"
        DiskFormat.VDI -> "vdi"
        DiskFormat.VMDK -> "vmdk"
    }
}
