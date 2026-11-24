package com.assavendo.machinevirtuel.runtime

import com.assavendo.machinevirtuel.vm.BackendKind
import com.assavendo.machinevirtuel.vm.GpuBackend
import com.assavendo.machinevirtuel.vm.GuestArch
import com.assavendo.machinevirtuel.vm.NetworkMode
import com.assavendo.machinevirtuel.vm.VmConfig
import java.io.File

object QemuCommandBuilder {
    fun build(
        paths: RuntimePaths,
        vm: VmConfig,
        qmp: File,
        serial: File,
        log: File,
        virtioFsSocket: File? = null,
        tpmHandle: TpmHandle? = null,
    ): List<String> {
        val qemu = when (vm.arch) {
            GuestArch.ARM64 -> paths.qemu
            GuestArch.X86_64 -> if (paths.qemuX86.canExecute()) paths.qemuX86 else paths.qemuX86Headless
            GuestArch.X86_32 -> paths.qemuI386
        }
        if (!qemu.canExecute()) throw IllegalStateException(
            "Aucun QEMU systeme compatible avec l'architecture " + vm.arch.label + " n'est installé dans le runtime PhoenixVM."
        )
        require(vm.diskPath.isNotBlank()) { "Une VM full-system doit avoir un disque virtuel." }
        require(vm.firmwarePath.isNotBlank() && File(vm.firmwarePath).isFile) { "Firmware/NVRAM UEFI introuvable." }
        if (vm.tpmEnabled) require(tpmHandle != null) {
            "TPM activé mais aucun backend swtpm actif."
        }

        val args = mutableListOf<String>()
        args += qemu.absolutePath
        args += "-name"; args += "phoenixvm-" + vm.id.take(8)

        if (vm.arch == GuestArch.ARM64) {
            val machine = if (vm.machineType.isBlank()) "virt" else vm.machineType
            args += "-machine"; args += machine + ",highmem=on,gic-version=3"
            if (vm.backend == BackendKind.KVM) {
                args += "-accel"; args += "kvm"; args += "-cpu"; args += "host"
            } else {
                args += "-accel"; args += "tcg,thread=multi"; args += "-cpu"; args += "max"
            }
        } else {
            val machine = if (vm.machineType.isBlank() || vm.machineType == "virt") "q35" else vm.machineType
            args += "-machine"; args += machine
            args += "-accel"; args += "tcg,thread=multi"; args += "-cpu"; args += "max"
        }

        args += "-smp"; args += vm.vCpu.coerceIn(1, 256).toString()
        args += "-m"; args += vm.ramMb.coerceAtLeast(256).toString() + "M"
        if (vm.memoryPreallocate) args += "-mem-prealloc"

        if (paths.edk2Qemu.isFile) {
            args += "-drive"; args += "if=pflash,format=raw,readonly=on,file=" + paths.edk2Qemu.absolutePath
        }
        args += "-drive"; args += "if=pflash,format=raw,file=" + vm.firmwarePath

        if (vm.ioThreads) args += "-object"; args += "iothread,id=io0"
        args += "-drive"
        args += "if=none,id=vd0,format=" + vm.diskFormat.name.lowercase() + ",file=" + vm.diskPath +
            ",cache=writeback,aio=threads,discard=unmap"
        args += "-device"
        args += "virtio-blk-pci,drive=vd0" + (if (vm.ioThreads) ",iothread=io0" else "") +
            ",disable-legacy=on,disable-modern=off,bootindex=0"

        if (vm.balloonEnabled) {
            args += "-device"; args += "virtio-balloon-pci,disable-legacy=on,disable-modern=off"
        }
        if (vm.isoPath.isNotBlank()) {
            args += "-drive"; args += "if=none,id=cd0,media=cdrom,readonly=on,format=raw,file=" + vm.isoPath
            args += "-device"; args += "virtio-scsi-pci,id=scsi0,disable-legacy=on,disable-modern=off"
            args += "-device"; args += "scsi-cd,bus=scsi0.0,drive=cd0"
        }

        val boot = vm.bootOrder.mapNotNull {
            when (it.lowercase()) {
                "disk" -> "c"; "cdrom", "iso" -> "d"; "network", "net" -> "n"; else -> null
            }
        }.joinToString("")
        if (boot.isNotBlank()) { args += "-boot"; args += "order=" + boot + ",menu=on" }

        when (vm.networkMode) {
            NetworkMode.DISABLED -> Unit
            NetworkMode.NAT -> {
                val forwards = vm.portForwards.joinToString(",") {
                    require(it.hostPort in 1..65535 && it.guestPort in 1..65535) { "Port forwarding must use 1..65535." }
                    val protocol = if (it.protocol.lowercase() == "udp") "udp" else "tcp"
                    "hostfwd=" + protocol + "::" + it.hostPort + "-:" + it.guestPort
                }
                args += "-netdev"
                args += if (forwards.isBlank()) "user,id=net0" else "user,id=net0," + forwards
                args += "-device"; args += "virtio-net-pci,netdev=net0,disable-legacy=on,disable-modern=off"
            }
            NetworkMode.TAP -> {
                if (vm.tapName.isBlank()) throw IllegalStateException("Le mode TAP exige un nom d'interface.")
                args += "-netdev"; args += "tap,id=net0,ifname=" + vm.tapName + ",script=no,downscript=no"
                args += "-device"; args += "virtio-net-pci,netdev=net0,disable-legacy=on,disable-modern=off"
            }
        }

        args += "-device"; args += "virtio-rng-pci,disable-legacy=on,disable-modern=off"
        if (vm.touchEnabled) { args += "-device"; args += "virtio-tablet-pci,disable-legacy=on,disable-modern=off" }
        if (vm.keyboardEnabled) { args += "-device"; args += "virtio-keyboard-pci,disable-legacy=on,disable-modern=off" }
        if (vm.mouseEnabled) { args += "-device"; args += "virtio-mouse-pci,disable-legacy=on,disable-modern=off" }
        args += "-rtc"; args += "base=utc"

        if (virtioFsSocket != null && vm.sharedDirPath.isNotBlank()) {
            args += "-object"; args += "memory-backend-memfd,id=mem,share=on,size=" + vm.ramMb + "M"
            args += "-numa"; args += "node,memdev=mem"
            args += "-chardev"; args += "socket,id=vfs0,path=" + virtioFsSocket.absolutePath
            args += "-device"; args += "vhost-user-fs-pci,queue-size=1024,chardev=vfs0,tag=" + vm.sharedDirTag
        } else if (vm.sharedDirPath.isNotBlank()) {
            require(File(vm.sharedDirPath).isDirectory) { "Dossier partagé introuvable." }
            var fs = "local,path=" + vm.sharedDirPath + ",mount_tag=" + vm.sharedDirTag + ",security_model=mapped-xattr"
            if (vm.sharedDirReadOnly) fs += ",readonly=on"
            args += "-virtfs"; args += fs
        }

        if (tpmHandle != null) {
            TpmManager.appendQemuDevice(args, tpmHandle, vm.arch)
        }

        val gpu = when (vm.gpuBackend) {
            GpuBackend.AUTO ->
                if (File(paths.libDir, "libgfxstream_backend.so").isFile) GpuBackend.GFXSTREAM
                else if (File(paths.libDir, "libvirglrenderer.so").isFile) GpuBackend.VIRGL else GpuBackend.SOFTWARE
            else -> vm.gpuBackend
        }
        when (gpu) {
            GpuBackend.VIRGL -> {
                args += "-device"; args += "virtio-gpu-gl-pci,disable-legacy=on,disable-modern=off,xres=" + vm.displayWidth + ",yres=" + vm.displayHeight + ",edid=on"
                args += "-display"; args += "egl-headless"
            }
            GpuBackend.GFXSTREAM -> {
                args += "-device"; args += "virtio-gpu-rutabaga-pci,disable-legacy=on,disable-modern=off,blob=on,xres=" + vm.displayWidth + ",yres=" + vm.displayHeight + ",edid=on"
                args += "-display"; args += "egl-headless"
            }
            GpuBackend.SOFTWARE, GpuBackend.AUTO -> {
                args += "-device"; args += "virtio-gpu-pci,disable-legacy=on,disable-modern=off,xres=" + vm.displayWidth + ",yres=" + vm.displayHeight + ",edid=on"
                args += "-display"; args += "none"
            }
        }

        if (vm.audioEnabled) {
            args += "-audiodev"; args += "aaudio,id=snd0"
            args += "-device"; args += "virtio-sound-pci,audiodev=snd0,disable-legacy=on,disable-modern=off"
        }

        args += "-device"; args += "qemu-xhci,id=usb"
        args += "-vnc"; args += "127.0.0.1:" + (VncPorts.portFor(vm.id) - 5900)
        args += "-qmp"; args += "unix:" + qmp.absolutePath + ",server=on,wait=off"
        args += "-serial"; args += "file:" + serial.absolutePath
        args += "-D"; args += log.absolutePath
        args += "-L"; args += paths.qemuShare.absolutePath
        args += "-no-reboot"
        return args
    }
}
