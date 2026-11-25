package com.assavendo.machinevirtuel.runtime

import com.assavendo.machinevirtuel.vm.DiskFormat
import com.assavendo.machinevirtuel.vm.GuestArch
import com.assavendo.machinevirtuel.vm.VmConfig
import java.io.File
import java.io.RandomAccessFile

object DiskManager {
    fun ensureVmStorage(base: File, vm: VmConfig, runtime: RuntimePaths? = null) {
        require(vm.diskSizeGiB in 1L..4096L) {
            "La taille du disque virtuel doit être comprise entre 1 et 4096 Gio."
        }
        val vmDir = File(base, "vms/" + vm.id).apply {
            check(mkdirs() || isDirectory) { "Impossible de créer le répertoire de la VM." }
        }
        val disk = if (vm.diskPath.isBlank()) {
            File(vmDir, "disk." + extension(vm.diskFormat))
        } else {
            File(vm.diskPath)
        }
        if (!disk.exists()) {
            disk.parentFile?.mkdirs()
            if (vm.diskFormat == DiskFormat.RAW) {
                RandomAccessFile(disk, "rw").use {
                    it.setLength(vm.diskSizeGiB * 1024L * 1024L * 1024L)
                }
            } else {
                requireNotNull(runtime) {
                    "Un runtime QEMU vérifié est requis pour créer ce format de disque."
                }
                QemuImageManager.create(runtime, disk, vm.diskFormat, vm.diskSizeGiB)
            }
        } else {
            require(disk.isFile && disk.canRead() && disk.canWrite()) {
                "L'image disque doit être un fichier lisible et modifiable."
            }
        }
        if (vm.secureBoot) {
            require(vm.arch == GuestArch.X86_64) {
                "Secure Boot est actuellement supporté uniquement pour les guests x86_64 avec OVMF Secure Boot vérifié."
            }
        }
        vm.diskPath = disk.absolutePath
        vm.firmwarePath = File(vmDir, if (vm.secureBoot) "nvram-secure.fd" else "nvram.fd").absolutePath
    }

    fun ensureNvram(vm: VmConfig, template: File) {
        require(template.isFile && template.canRead()) {
            "Le modèle NVRAM UEFI pour l'architecture " + vm.arch.label + " est indisponible."
        }
        val vars = File(vm.firmwarePath)
        if (!vars.exists()) {
            vars.parentFile?.mkdirs()
            template.copyTo(vars)
        }
        require(vars.isFile && vars.length() == template.length()) {
            "L'image NVRAM UEFI est invalide."
        }
    }

    fun firmwareTemplate(runtime: RuntimePaths, vm: VmConfig): File = when (vm.arch) {
        GuestArch.ARM64 -> runtime.edk2QemuVars
        GuestArch.X86_64 -> if (vm.secureBoot) runtime.edk2X86SecureVars else runtime.edk2X86Vars
        GuestArch.X86_32 -> runtime.edk2X86Vars
    }

    fun extension(format: DiskFormat): String = when (format) {
        DiskFormat.RAW -> "raw"
        DiskFormat.QCOW2 -> "qcow2"
        DiskFormat.VHDX -> "vhdx"
        DiskFormat.VDI -> "vdi"
        DiskFormat.VMDK -> "vmdk"
    }
}
