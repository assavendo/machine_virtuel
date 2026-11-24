package com.assavendo.machinevirtuel.runtime

import com.assavendo.machinevirtuel.vm.BackendKind
import com.assavendo.machinevirtuel.vm.VmConfig

object QemuCommandBuilder {
    fun build(
        paths: RuntimePaths,
        vm: VmConfig,
        qmp: java.io.File,
        serial: java.io.File,
        log: java.io.File,
    ): List<String> {
        val args = mutableListOf<String>()
        args += paths.qemu.absolutePath
        args += "-name"; args += "phoenixvm-" + vm.id.take(8)
        args += "-machine"; args += "virt,highmem=on,gic-version=3"

        if (vm.backend == BackendKind.KVM) {
            args += "-accel"; args += "kvm"
            args += "-cpu"; args += "host"
        } else {
            args += "-accel"; args += "tcg,thread=multi"
            args += "-cpu"; args += "max"
        }

        args += "-smp"; args += vm.vCpu.toString()
        args += "-m"; args += vm.ramMb.toString() + "M"
        args += "-drive"; args += "if=pflash,format=raw,readonly=on,file=" + paths.edk2Qemu.absolutePath
        args += "-drive"; args += "if=pflash,format=raw,file=" + vm.firmwarePath
        args += "-drive"; args += "if=none,id=vd0,format=" +
            vm.diskFormat.name.lowercase() + ",file=" + vm.diskPath
        args += "-device"; args += "virtio-blk-pci,drive=vd0,bootindex=0"

        if (vm.isoPath.isNotBlank()) {
            args += "-drive"; args += "if=none,id=cd0,media=cdrom,readonly=on,format=raw,file=" + vm.isoPath
            args += "-device"; args += "virtio-scsi-pci,id=scsi0"
            args += "-device"; args += "scsi-cd,bus=scsi0.0,drive=cd0"
        }

        args += "-netdev"; args += "user,id=net0"
        args += "-device"; args += "virtio-net-pci,netdev=net0"
        args += "-device"; args += "virtio-gpu-pci"
        args += "-device"; args += "virtio-tablet-pci"
        args += "-device"; args += "virtio-keyboard-pci"
        args += "-device"; args += "virtio-rng-pci"
        args += "-device"; args += "qemu-xhci,id=usb"
        args += "-rtc"; args += "base=utc"

        if (vm.sharedDirPath.isNotBlank()) {
            args += "-virtfs"
            args += "local,path=" + vm.sharedDirPath +
                ",security_model=none,mount_tag=" + vm.sharedDirTag
        }

        args += "-display"; args += "none"
        args += "-vnc"; args += "127.0.0.1:" + (VncPorts.portFor(vm.id) - 5900)
        args += "-qmp"; args += "unix:" + qmp.absolutePath + ",server=on,wait=off"
        args += "-serial"; args += "file:" + serial.absolutePath
        args += "-D"; args += log.absolutePath
        args += "-L"; args += paths.qemuShare.absolutePath
        args += "-no-reboot"
        return args
    }
}
