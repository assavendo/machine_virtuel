package com.assavendo.machinevirtuel.runtime

import com.assavendo.machinevirtuel.vm.BackendKind
import com.assavendo.machinevirtuel.vm.GuestArch
import com.assavendo.machinevirtuel.vm.VmConfig
import java.io.File

object CrosvmCommandBuilder {
    fun build(paths: RuntimePaths, vm: VmConfig): List<String> {
        require(vm.arch == GuestArch.ARM64) {
            "crosvm Android est provisionné ici pour les guests ARM64 uniquement."
        }
        require(paths.crosvm.canExecute()) {
            "Le runtime crosvm n'est pas installé ou exécutable."
        }
        require(vm.diskPath.isNotBlank()) { "Une VM full-system doit avoir un disque virtuel." }
        val disk = File(vm.diskPath).canonicalFile
        require(disk.isFile && disk.length() > 0L) {
            "Le disque virtuel est introuvable ou vide."
        }

        val firmware = vm.firmwarePath.takeIf { it.isNotBlank() }?.let { File(it).canonicalFile }
        if (vm.kernelPath.isBlank()) {
            require(firmware != null && firmware.isFile) {
                "Un firmware est requis lorsque crosvm démarre sans noyau direct."
            }
        }
        vm.kernelPath.takeIf { it.isNotBlank() }?.let {
            require(File(it).isFile && File(it).length() > 0L) { "Noyau guest introuvable ou vide." }
        }
        vm.initrdPath.takeIf { it.isNotBlank() }?.let {
            require(File(it).isFile && File(it).length() > 0L) { "Initrd guest introuvable ou vide." }
        }

        val hypervisor = when (vm.backend) {
            BackendKind.GUNYAH -> "gunyah"
            BackendKind.GENIEZONE -> "geniezone"
            BackendKind.HALLA -> "halla"
            BackendKind.KVM -> "kvm"
            else -> error("Backend crosvm non pris en charge: " + vm.backend)
        }

        return buildList {
            add(paths.crosvm.absolutePath)
            add("--no-syslog")
            add("run")
            add("--hypervisor"); add(hypervisor)
            add("--mem"); add("size=" + vm.ramMb.coerceAtLeast(256))
            add("--cpus"); add("num-cores=" + vm.vCpu.coerceIn(1, 256))
            add("--block"); add("path=" + disk.absolutePath)

            if (firmware != null) {
                require(firmware.isFile) { "Firmware crosvm introuvable." }
                add("--bios"); add(firmware.absolutePath)
            }

            if (vm.kernelPath.isNotBlank()) {
                add(vm.kernelPath)
                if (vm.initrdPath.isNotBlank()) {
                    add("--initrd"); add(vm.initrdPath)
                }
                if (vm.kernelCommandLine.isNotBlank()) {
                    require(
                        vm.kernelCommandLine.length <= 16_384 &&
                            !vm.kernelCommandLine.contains('\u0000')
                    ) { "kernelCommandLine invalide." }
                    add("--params"); add(vm.kernelCommandLine)
                }
                // crosvm generates and exposes its own guest FDT on ARM64. The explicit
                // DTB path remains a QEMU full-system feature in this runtime.
            }

            add("--socket")
            add(FilePaths.controlSocket(vm.id).absolutePath)
        }
    }
}
