package com.assavendo.machinevirtuel.runtime

import com.assavendo.machinevirtuel.vm.BackendKind
import com.assavendo.machinevirtuel.vm.VmConfig

object CrosvmCommandBuilder {
    fun build(paths: RuntimePaths, vm: VmConfig): List<String> {
        val hypervisor = when (vm.backend) {
            BackendKind.GUNYAH -> "gunyah"
            BackendKind.GENIEZONE -> "geniezone"
            BackendKind.HALLA -> "halla"
            BackendKind.KVM -> "kvm"
            else -> "kvm"
        }
        val firmware = if (vm.backend == BackendKind.GUNYAH) {
            paths.edk2Gunyah
        } else {
            paths.edk2Qemu
        }
        return buildList {
            add(paths.crosvm.absolutePath)
            add("run")
            add("--name"); add("phoenix-" + vm.id.take(8))
            add("--hypervisor"); add(hypervisor)
            add("--mem"); add("size=" + vm.ramMb)
            add("--cpus"); add("num-cores=" + vm.vCpu)
            add("--bios"); add(firmware.absolutePath)
            add("--block"); add(vm.diskPath)
            add("--socket"); add(FilePaths.controlSocket(vm.id).absolutePath)
        }
    }
}
