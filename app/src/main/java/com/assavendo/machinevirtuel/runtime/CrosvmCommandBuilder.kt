package com.assavendo.machinevirtuel.runtime

import com.assavendo.machinevirtuel.vm.BackendKind
import com.assavendo.machinevirtuel.vm.GuestArch
import com.assavendo.machinevirtuel.vm.VmConfig
import java.io.File

object CrosvmCommandBuilder {
    fun build(paths: RuntimePaths, vm: VmConfig, controlSocket: File = FilePaths.controlSocket(vm.id)): List<String> {
        require(vm.arch == GuestArch.ARM64) {
            "crosvm Android est provisionné ici pour les guests ARM64 uniquement."
        }
        require(paths.crosvm.canExecute()) {
            "Le runtime crosvm n'est pas installé ou exécutable."
        }
        require(!vm.tpmEnabled) {
            "Le backend crosvm Android ne prend pas encore en charge le TPM 2.0 via swtpm; utilisez QEMU."
        }
        require(vm.diskPath.isNotBlank()) { "Une VM full-system doit avoir un disque virtuel." }
        val disk = File(vm.diskPath).canonicalFile
        require(disk.isFile && disk.length() > 0L) {
            "Le disque virtuel est introuvable ou vide."
        }

        val firmware = if (vm.kernelPath.isBlank()) {
            val code = paths.edk2Qemu.canonicalFile
            require(code.isFile && code.length() > 0L) {
                "Le firmware UEFI crosvm ARM64 est absent."
            }
            code
        } else {
            null
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

            when (vm.networkMode) {
                com.assavendo.machinevirtuel.vm.NetworkMode.DISABLED -> Unit
                com.assavendo.machinevirtuel.vm.NetworkMode.NAT -> {
                    // Upstream crosvm's Android/Linux network path uses a preconfigured
                    // persistent TAP or host-ip/netmask/mac; there is no Android slirp NAT path
                    // here. Do not silently turn NAT into an unusable or privileged request.
                }
                com.assavendo.machinevirtuel.vm.NetworkMode.TAP -> {
                    require(vm.tapName.matches(Regex("[A-Za-z0-9_.-]{1,15}"))) {
                        "Le nom de l'interface TAP crosvm est invalide."
                    }
                    add("--net")
                    add("tap-name=" + vm.tapName)
                }
            }

            val shares = buildList {
                if (vm.sharedDirPath.isNotBlank()) {
                    add(com.assavendo.machinevirtuel.vm.SharedDirectory(
                        hostPath = vm.sharedDirPath,
                        tag = vm.sharedDirTag,
                        readOnly = vm.sharedDirReadOnly,
                    ))
                }
                addAll(vm.sharedDirs)
            }.distinctBy { it.tag }

            shares.forEach { share ->
                val shared = File(share.hostPath).canonicalFile
                require(shared.isDirectory) { "Dossier partagé crosvm introuvable: " + shared }
                require(!share.readOnly) {
                    "Le partage crosvm natif n'expose pas encore une politique read-only garantie."
                }
                require(share.tag.matches(Regex("[A-Za-z0-9_.-]{1,36}"))) {
                    "Tag de partage invalide."
                }
                add("--shared-dir")
                add(shared.absolutePath + ":" + share.tag + ":type=fs")
            }

            if (firmware != null) {
                require(firmware.isFile) { "Firmware crosvm introuvable." }
                add("--bios"); add(firmware.absolutePath)
            }

            if (vm.audioEnabled) {
                require(vm.audioFrequencyHz == 48_000 && vm.audioChannels == 2 && vm.audioBufferMs == 20 && !vm.audioMuted) {
                    "Le chemin crosvm AAudio Android actuel ne garantit pas encore les réglages audio personnalisés/mute; utilisez QEMU pour ces paramètres."
                }
                add("--virtio-snd")
                add("backend=aaudio,num_output_devices=1,num_input_devices=1")
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
            add(controlSocket.absolutePath)
        }
    }
}
