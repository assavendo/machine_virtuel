package com.assavendo.machinevirtuel.runtime

import com.assavendo.machinevirtuel.vm.BackendKind
import com.assavendo.machinevirtuel.vm.GpuBackend
import com.assavendo.machinevirtuel.vm.NetworkMode
import com.assavendo.machinevirtuel.vm.VmConfig

object QemuCommandBuilder {
    fun build(
        paths: RuntimePaths,
        vm: VmConfig,
        qmp: java.io.File,
        serial: java.io.File,
        log: java.io.File,
    ): List<String> {
        val qemu = when (vm.arch) {
            com.assavendo.machinevirtuel.vm.GuestArch.ARM64 -> paths.qemu
            com.assavendo.machinevirtuel.vm.GuestArch.X86_64 -> paths.qemuX86
        }
        if (!qemu.canExecute()) {
            throw IllegalStateException(
                "Aucun QEMU systeme compatible avec l'architecture " + vm.arch.label +
                    " n'est installé dans le runtime PhoenixVM."
            )
        }

        val args = mutableListOf<String>()
        args += qemu.absolutePath
        args += "-name"; args += "phoenixvm-" + vm.id.take(8)

        if (vm.arch == com.assavendo.machinevirtuel.vm.GuestArch.ARM64) {
            args += "-machine"; args += "virt,highmem=on,gic-version=3"
            if (vm.backend == BackendKind.KVM) {
                args += "-accel"; args += "kvm"
                args += "-cpu"; args += "host"
            } else {
                args += "-accel"; args += "tcg,thread=multi"
                args += "-cpu"; args += "max"
            }
        } else {
            args += "-machine"; args += "q35"
            args += "-accel"; args += "tcg,thread=multi"
            args += "-cpu"; args += "max"
        }

        args += "-smp"; args += vm.vCpu.toString()
        args += "-m"; args += (vm.ramMb.toString() + "M")
        if (vm.memoryPreallocate) args += "-mem-prealloc"

        args += "-drive"; args += "if=pflash,format=raw,readonly=on,file=" + paths.edk2Qemu.absolutePath
        args += "-drive"; args += "if=pflash,format=raw,file=" + vm.firmwarePath
        if (vm.ioThreads) args += "-object"; args += "iothread,id=io0"
        args += "-drive"
        args += "if=none,id=vd0,format=" + vm.diskFormat.name.lowercase() +
            ",file=" + vm.diskPath + ",cache=writeback,aio=threads,discard=unmap"
        args += "-device"
        args += "virtio-blk-pci,drive=vd0" +
            (if (vm.ioThreads) ",iothread=io0" else "") +
            ",disable-legacy=on,disable-modern=off,bootindex=0"

        if (vm.balloonEnabled) {
            args += "-device"; args += "virtio-balloon-pci,disable-legacy=on,disable-modern=off"
        }

        if (vm.isoPath.isNotBlank()) {
            args += "-drive"; args += "if=none,id=cd0,media=cdrom,readonly=on,format=raw,file=" + vm.isoPath
            args += "-device"; args += "virtio-scsi-pci,id=scsi0,disable-legacy=on,disable-modern=off"
            args += "-device"; args += "scsi-cd,bus=scsi0.0,drive=cd0"
        }

        when (vm.networkMode) {
            NetworkMode.DISABLED -> Unit
            NetworkMode.NAT -> {
                val forwards = vm.portForwards.joinToString(",") {
                    require(it.hostPort in 1..65535 && it.guestPort in 1..65535) {
                        "Port forwarding must use 1..65535."
                    }
                    val protocol = if (it.protocol.lowercase() == "udp") "udp" else "tcp"
                    "hostfwd=" + protocol + "::" + it.hostPort + "-:" + it.guestPort
                }
                val netdev = if (forwards.isBlank()) "user,id=net0" else "user,id=net0," + forwards
                args += "-netdev"; args += netdev
                args += "-device"; args += "virtio-net-pci,netdev=net0,disable-legacy=on,disable-modern=off"
            }
            NetworkMode.TAP -> {
                if (vm.tapName.isBlank()) throw IllegalStateException("Le mode TAP exige un nom d'interface.")
                args += "-netdev"
                args += "tap,id=net0,ifname=" + vm.tapName + ",script=no,downscript=no"
                args += "-device"; args += "virtio-net-pci,netdev=net0,disable-legacy=on,disable-modern=off"
            }
        }

        args += "-device"; args += "virtio-rng-pci,disable-legacy=on,disable-modern=off"
        args += "-device"; args += "virtio-multitouch-pci,disable-legacy=on,disable-modern=off"
        args += "-device"; args += "virtio-keyboard-pci,disable-legacy=on,disable-modern=off"
        args += "-rtc"; args += "base=utc"

        if (vm.sharedDirPath.isNotBlank()) {
            var fs = "local,path=" + vm.sharedDirPath +
                ",mount_tag=" + vm.sharedDirTag + ",security_model=mapped-xattr"
            if (vm.sharedDirReadOnly) fs += ",readonly=on"
            args += "-virtfs"; args += fs
        }

        val gpu = when (vm.gpuBackend) {
            GpuBackend.AUTO ->
                if (paths.libDir.resolve("libgfxstream_backend.so").isFile) GpuBackend.GFXSTREAM else GpuBackend.SOFTWARE
            else -> vm.gpuBackend
        }
        var displayMode: String = "none"
        when (gpu) {
            GpuBackend.VIRGL -> {
                args += "-device"
                args += "virtio-gpu-gl-pci,disable-legacy=on,disable-modern=off,xres=" +
                    vm.displayWidth + ",yres=" + vm.displayHeight + ",edid=on"
                displayMode = "egl-headless"
            }
            GpuBackend.GFXSTREAM -> {
                args += "-device"
                args += "virtio-gpu-rutabaga-pci,disable-legacy=on,disable-modern=off,blob=on,xres=" +
                    vm.displayWidth + ",yres=" + vm.displayHeight + ",edid=on"
                displayMode = "egl-headless"
            }
            GpuBackend.SOFTWARE, GpuBackend.AUTO -> {
                args += "-device"
                args += "virtio-gpu-pci,disable-legacy=on,disable-modern=off,xres=" +
                    vm.displayWidth + ",yres=" + vm.displayHeight + ",edid=on"
            }
        }

        if (vm.audioEnabled) {
            args += "-audiodev"; args += "aaudio,id=snd0"
            args += "-device"; args += "virtio-sound-pci,audiodev=snd0,disable-legacy=on,disable-modern=off"
        }

        args += "-device"; args += "qemu-xhci,id=usb"
        args += "-display"; args += displayMode
        args += "-vnc"; args += "127.0.0.1:" + (VncPorts.portFor(vm.id) - 5900)
        args += "-qmp"; args += "unix:" + qmp.absolutePath + ",server=on,wait=off"
        args += "-serial"; args += "file:" + serial.absolutePath
        args += "-D"; args += log.absolutePath
        args += "-L"; args += paths.qemuShare.absolutePath
        args += "-no-reboot"
        return args
    }
}
