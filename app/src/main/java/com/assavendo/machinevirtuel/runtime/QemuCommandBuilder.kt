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
        require(qemu.canExecute()) {
            "Aucun QEMU système compatible avec l'architecture " + vm.arch.label + " n'est installé."
        }
        require(vm.diskPath.isNotBlank()) { "Une VM full-system doit avoir un disque virtuel." }
        require(File(vm.diskPath).isFile) { "Le disque virtuel est introuvable." }
        require(vm.firmwarePath.isNotBlank() && File(vm.firmwarePath).isFile) {
            "Firmware/NVRAM UEFI introuvable."
        }
        if (vm.tpmEnabled) require(tpmHandle != null) {
            "TPM activé mais aucun backend swtpm actif."
        }

        val firmwareCode = when (vm.arch) {
            GuestArch.ARM64 -> paths.edk2Qemu
            GuestArch.X86_64, GuestArch.X86_32 -> paths.edk2X86Code
        }
        require(firmwareCode.isFile) {
            "Le firmware UEFI " + vm.arch.label +
                " n'est pas provisionné. Aucun firmware d'une autre architecture ne sera substitué."
        }

        val diskPath = qemuKeyValue(vm.diskPath, "diskPath")
        val nvramPath = qemuKeyValue(vm.firmwarePath, "firmwarePath")
        val firmwarePath = qemuKeyValue(firmwareCode.absolutePath, "firmwareCode")
        val args = mutableListOf<String>()
        args += qemu.absolutePath
        args += "-name"; args += "phoenixvm-" + vm.id.take(8)

        if (vm.arch == GuestArch.ARM64) {
            val machine = if (vm.machineType.isBlank()) "virt" else vm.machineType
            args += "-machine"; args += machine + ",highmem=on,gic-version=3"
            if (vm.backend == BackendKind.KVM) {
                args += "-accel"; args += "kvm"
                args += "-cpu"; args += "host"
            } else {
                args += "-accel"; args += "tcg,thread=multi,tb-size=" + tcgTbCacheSizeMb(vm)
                args += "-cpu"; args += "max"
            }
        } else {
            val machine = if (vm.machineType.isBlank() || vm.machineType == "virt") "q35" else vm.machineType
            args += "-machine"; args += machine
            args += "-accel"; args += "tcg,thread=multi,tb-size=" + tcgTbCacheSizeMb(vm)
            args += "-cpu"; args += "max"
        }

        args += "-smp"; args += vm.vCpu.coerceIn(1, 256).toString()
        args += "-m"; args += vm.ramMb.coerceAtLeast(256).toString() + "M"
        if (vm.memoryPreallocate) args += "-mem-prealloc"

        args += "-drive"
        args += "if=pflash,format=raw,readonly=on,file=" + firmwarePath
        args += "-drive"
        args += "if=pflash,format=raw,file=" + nvramPath

        if (vm.ioThreads) {
            args += "-object"
            args += "iothread,id=io0"
        }
        args += "-drive"
        args += "if=none,id=vd0,format=" + vm.diskFormat.name.lowercase() +
            ",file=" + diskPath + ",cache=writeback,aio=threads,discard=unmap"
        args += "-device"
        args += "virtio-blk-pci,drive=vd0" +
            (if (vm.ioThreads) ",iothread=io0" else "") +
            ",disable-legacy=on,disable-modern=off,bootindex=0"

        if (vm.balloonEnabled) {
            args += "-device"; args += "virtio-balloon-pci,disable-legacy=on,disable-modern=off"
        }
        if (vm.isoPath.isNotBlank()) {
            val iso = qemuKeyValue(vm.isoPath, "isoPath")
            require(File(vm.isoPath).isFile) { "L'image ISO sélectionnée est introuvable." }
            args += "-drive"; args += "if=none,id=cd0,media=cdrom,readonly=on,format=raw,file=" + iso
            args += "-device"; args += "virtio-scsi-pci,id=scsi0,disable-legacy=on,disable-modern=off"
            args += "-device"; args += "scsi-cd,bus=scsi0.0,drive=cd0"
        }

        fun requireImage(path: String, label: String): String {
            require(path.isNotBlank()) { label + " est requis." }
            val file = File(path)
            require(file.isFile && file.length() > 0L) { label + " est introuvable ou vide." }
            return qemuKeyValue(file.absolutePath, label)
        }

        if (vm.kernelPath.isNotBlank()) {
            args += "-kernel"; args += requireImage(vm.kernelPath, "kernelPath")
            if (vm.initrdPath.isNotBlank()) {
                args += "-initrd"; args += requireImage(vm.initrdPath, "initrdPath")
            }
            if (vm.dtbPath.isNotBlank()) {
                args += "-dtb"; args += requireImage(vm.dtbPath, "dtbPath")
            }
            if (vm.kernelCommandLine.isNotBlank()) {
                require(vm.kernelCommandLine.length <= 16_384 &&
                    !vm.kernelCommandLine.contains('\u0000')) { "kernelCommandLine invalide." }
                args += "-append"; args += vm.kernelCommandLine
            }
        }

        if (vm.androidSystemPath.isNotBlank() ||
            vm.androidVendorPath.isNotBlank() ||
            vm.androidUserdataPath.isNotBlank()
        ) {
            require(vm.guestOs.contains("android", ignoreCase = true)) {
                "Les partitions Android ne peuvent être attachées qu'à un guest Android."
            }
            fun addAndroidDisk(id: String, path: String) {
                if (path.isBlank()) return
                val image = requireImage(path, id)
                args += "-drive"
                args += "if=none,id=$id,format=raw,file=$image,cache=none,aio=threads"
                args += "-device"
                args += "virtio-blk-pci,drive=$id,disable-legacy=on,disable-modern=off"
            }
            addAndroidDisk("android-system", vm.androidSystemPath)
            addAndroidDisk("android-vendor", vm.androidVendorPath)
            addAndroidDisk("android-userdata", vm.androidUserdataPath)
        }

        val boot = vm.bootOrder.mapNotNull {
            when (it.lowercase()) {
                "disk" -> "c"
                "cdrom", "iso" -> "d"
                "network", "net" -> "n"
                else -> null
            }
        }.joinToString("")
        if (boot.isNotBlank()) {
            args += "-boot"; args += "order=" + boot + ",menu=on"
        }

        when (vm.networkMode) {
            NetworkMode.DISABLED -> Unit
            NetworkMode.NAT -> {
                val forwards = vm.portForwards.joinToString(",") {
                    require(it.hostPort in 1..65535 && it.guestPort in 1..65535) {
                        "Port forwarding must use 1..65535."
                    }
                    val protocol = when (it.protocol.lowercase()) {
                        "tcp" -> "tcp"
                        "udp" -> "udp"
                        else -> error("Protocole de redirection réseau invalide.")
                    }
                    "hostfwd=" + protocol + "::" + it.hostPort + "-:" + it.guestPort
                }
                args += "-netdev"
                args += if (forwards.isBlank()) "user,id=net0" else "user,id=net0," + forwards
                args += "-device"; args += "virtio-net-pci,netdev=net0,disable-legacy=on,disable-modern=off"
            }
            NetworkMode.TAP -> {
                require(vm.tapName.matches(Regex("[A-Za-z0-9_.-]{1,15}"))) {
                    "Le nom de l'interface TAP est invalide."
                }
                args += "-netdev"; args += "tap,id=net0,ifname=" + vm.tapName + ",script=no,downscript=no"
                args += "-device"; args += "virtio-net-pci,netdev=net0,disable-legacy=on,disable-modern=off"
            }
        }

        args += "-device"; args += "virtio-rng-pci,disable-legacy=on,disable-modern=off"
        if (vm.touchEnabled) {
            args += "-device"; args += "virtio-tablet-pci,disable-legacy=on,disable-modern=off"
        }
        if (vm.keyboardEnabled) {
            args += "-device"; args += "virtio-keyboard-pci,disable-legacy=on,disable-modern=off"
        }
        if (vm.mouseEnabled) {
            args += "-device"; args += "virtio-mouse-pci,disable-legacy=on,disable-modern=off"
        }
        args += "-rtc"; args += "base=utc"

        if (virtioFsSocket != null && vm.sharedDirPath.isNotBlank()) {
            require(!vm.sharedDirReadOnly) {
                "Le partage virtio-fs en lecture seule n'est pas activé dans ce runtime."
            }
            val socket = qemuKeyValue(virtioFsSocket.absolutePath, "virtioFsSocket")
            val tag = safeTag(vm.sharedDirTag)
            args += "-object"; args += "memory-backend-memfd,id=mem,share=on,size=" + vm.ramMb + "M"
            args += "-numa"; args += "node,memdev=mem"
            args += "-chardev"; args += "socket,id=vfs0,path=" + socket
            args += "-device"; args += "vhost-user-fs-pci,queue-size=1024,chardev=vfs0,tag=" + tag
        } else if (vm.sharedDirPath.isNotBlank()) {
            require(File(vm.sharedDirPath).isDirectory) { "Dossier partagé introuvable." }
            val shared = qemuKeyValue(vm.sharedDirPath, "sharedDirPath")
            val tag = safeTag(vm.sharedDirTag)
            var fs = "local,path=" + shared + ",mount_tag=" + tag + ",security_model=mapped-xattr"
            if (vm.sharedDirReadOnly) fs += ",readonly=on"
            args += "-virtfs"; args += fs
        }

        if (tpmHandle != null) {
            TpmManager.appendQemuDevice(args, tpmHandle, vm.arch)
        }

        val gpu = when (vm.gpuBackend) {
            // A shared library's presence is not proof that the Android host exposes a compatible
            // EGL/Vulkan path. Hardware rendering remains an explicit opt-in until device acceptance.
            GpuBackend.AUTO -> GpuBackend.SOFTWARE
            else -> vm.gpuBackend
        }
        when (gpu) {
            GpuBackend.VIRGL -> {
                require(File(paths.libDir, "libvirglrenderer.so").isFile) {
                    "VirGL was selected but its verified renderer library is missing."
                }
                args += "-device"
                args += "virtio-gpu-gl-pci,disable-legacy=on,disable-modern=off,xres=" +
                    vm.displayWidth + ",yres=" + vm.displayHeight + ",edid=on"
                args += "-display"; args += "egl-headless"
            }
            GpuBackend.GFXSTREAM -> {
                require(File(paths.libDir, "libgfxstream_backend.so").isFile) {
                    "Gfxstream was selected but its verified backend library is missing."
                }
                args += "-device"
                args += "virtio-gpu-rutabaga-pci,disable-legacy=on,disable-modern=off,blob=on,xres=" +
                    vm.displayWidth + ",yres=" + vm.displayHeight + ",edid=on"
                args += "-display"; args += "egl-headless"
            }
            GpuBackend.SOFTWARE, GpuBackend.AUTO -> {
                args += "-device"
                args += "virtio-gpu-pci,disable-legacy=on,disable-modern=off,xres=" +
                    vm.displayWidth + ",yres=" + vm.displayHeight + ",edid=on"
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
        args += "-L"; args += paths.dataDir(vm.arch).absolutePath
        args += "-no-reboot"
        return args
    }

    /** QEMU owns executable TB code; this only chooses the size of its in-memory TCG cache. */
    private fun tcgTbCacheSizeMb(vm: VmConfig): Int {
        val configured = when (vm.translationCacheLevel.coerceIn(0, 3)) {
            0 -> 16
            1 -> 32
            2 -> 64
            else -> 128
        }
        val presetCap = when (vm.performancePreset) {
            com.assavendo.machinevirtuel.vm.PerformancePreset.BATTERY_SAVER,
            com.assavendo.machinevirtuel.vm.PerformancePreset.COMPATIBILITY -> 32
            com.assavendo.machinevirtuel.vm.PerformancePreset.ULTRA_PERFORMANCE,
            com.assavendo.machinevirtuel.vm.PerformancePreset.GAMING,
            com.assavendo.machinevirtuel.vm.PerformancePreset.DEVELOPMENT -> 128
            else -> 64
        }
        // Reserve at least 7/8 of guest RAM for guest memory and Android/runtime overhead.
        val ramCap = (vm.ramMb / 8).coerceAtLeast(16)
        return minOf(configured, presetCap, ramCap).coerceIn(16, 128)
    }

    private fun qemuKeyValue(value: String, field: String): String {
        require(value.isNotBlank() && !value.contains(',') && !value.contains('\n') && !value.contains('\r')) {
            field + " contains characters that QEMU's key-value parser cannot safely accept."
        }
        return value
    }

    private fun safeTag(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_.-]{1,36}"))) { "Tag de partage invalide." }
        return value
    }
}
