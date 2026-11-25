package com.assavendo.machinevirtuel.vm

import java.io.File

data class ValidationIssue(val field: String, val message: String, val fatal: Boolean)

object VmSpecValidator {
    fun validate(vm: VmConfig): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        if (vm.name.isBlank()) issues += ValidationIssue("name", "Le nom de la VM est vide.", true)
        if (vm.ramMb < 256) issues += ValidationIssue("ramMb", "RAM minimale de 256 MiB.", true)
        if (vm.vCpu < 1) issues += ValidationIssue("vCpu", "Au moins 1 vCPU est requis.", true)
        if (vm.vCpu > 256) issues += ValidationIssue("vCpu", "Le nombre de vCPU dépasse 256.", true)
        if (vm.diskSizeGiB !in 1L..4096L) {
            issues += ValidationIssue("diskSizeGiB", "La taille du disque doit être comprise entre 1 et 4096 Gio.", true)
        }
        if (vm.guestOs.isBlank()) issues += ValidationIssue("guestOs", "Le système invité doit être sélectionné.", true)
        if (vm.displayWidth !in 320..16384) issues += ValidationIssue("displayWidth", "Résolution horizontale invalide.", true)
        if (vm.displayHeight !in 240..16384) issues += ValidationIssue("displayHeight", "Résolution verticale invalide.", true)

        val os = vm.guestOs.lowercase()
        if (os.contains("android") && vm.arch != GuestArch.ARM64) {
            issues += ValidationIssue("arch", "Le profil Android invité est actuellement réservé à ARM64.", true)
        }
        if (os.contains("windows") && vm.arch == GuestArch.X86_32) {
            issues += ValidationIssue("arch", "Windows x86 32-bit n'est pas proposé par ce profil de création.", true)
        }
        if ((os.contains("dos") || os.contains("legacy")) && vm.arch != GuestArch.X86_32) {
            issues += ValidationIssue("arch", "DOS / legacy exige l'architecture x86 32-bit.", true)
        }

        if (vm.sharedDirPath.isNotBlank()) {
            val directory = File(vm.sharedDirPath)
            if (!directory.isDirectory || !directory.canRead() || !directory.canWrite()) {
                issues += ValidationIssue("sharedDirPath", "Le dossier partagé doit exister et être accessible en lecture/écriture.", true)
            }
        }
        if (vm.sharedDirs.size > 16) {
            issues += ValidationIssue("sharedDirs", "Au maximum 16 dossiers partagés sont autorisés.", true)
        }
        val sharePaths = mutableSetOf<String>()
        val shareTags = mutableSetOf<String>()
        vm.sharedDirs.forEachIndexed { index, share ->
            val directory = runCatching { File(share.hostPath).canonicalFile }.getOrNull()
            if (directory == null || !directory.isDirectory || !directory.canRead() ||
                (!share.readOnly && !directory.canWrite())
            ) {
                issues += ValidationIssue("sharedDirs", "Le dossier partagé #${index + 1} est introuvable ou inaccessible.", true)
            } else if (!sharePaths.add(directory.path)) {
                issues += ValidationIssue("sharedDirs", "Un même dossier partagé est déclaré plusieurs fois.", true)
            }
            if (!share.tag.matches(Regex("[A-Za-z0-9_.-]{1,36}"))) {
                issues += ValidationIssue("sharedDirs", "Le tag du dossier partagé #${index + 1} est invalide.", true)
            } else if (!shareTags.add(share.tag)) {
                issues += ValidationIssue("sharedDirs", "Les tags des dossiers partagés doivent être uniques.", true)
            }
        }

        NetworkPolicy(
            mode = vm.networkMode,
            macAddress = NetworkPolicy.deterministicMac(vm.id),
            portForwards = vm.portForwards,
        ).validate().forEach { message ->
            issues += ValidationIssue("network", message, true)
        }

        if (vm.arch == GuestArch.X86_64 && vm.backend != BackendKind.QEMU_TCG && vm.backend != BackendKind.KVM)
            issues += ValidationIssue("backend", "Le guest x86_64 exige actuellement QEMU TCG.", false)
        if (vm.arch == GuestArch.X86_32 && vm.backend != BackendKind.QEMU_TCG)
            issues += ValidationIssue("backend", "Le guest x86 32-bit exige actuellement QEMU TCG.", false)
        if (vm.secureBoot && vm.arch != GuestArch.X86_64) {
            issues += ValidationIssue(
                "secureBoot",
                "Secure Boot est actuellement supporté uniquement pour les guests x86_64 avec le runtime OVMF Secure Boot vérifié.",
                true,
            )
        }
        if (vm.backend in setOf(BackendKind.GUNYAH, BackendKind.GENIEZONE, BackendKind.HALLA) && !vm.headless) {
            issues += ValidationIssue(
                "headless",
                "Le backend crosvm/hyperviseur matériel configuré ici est réservé au mode headless tant que le bridge d'affichage Android n'est pas installé.",
                true
            )
        }
        if (vm.backend in setOf(BackendKind.GUNYAH, BackendKind.GENIEZONE, BackendKind.HALLA) &&
            vm.networkMode == NetworkMode.NAT
        ) {
            issues += ValidationIssue(
                "networkMode",
                "Le chemin crosvm Android ne fournit pas de NAT utilisateur portable; utilisez TAP ou QEMU.",
                true
            )
        }
        if (vm.networkMode == NetworkMode.TAP &&
            !vm.tapName.matches(Regex("[A-Za-z0-9_.-]{1,15}"))
        ) {
            issues += ValidationIssue("tapName", "Le nom TAP doit contenir 1 à 15 caractères alphanumériques, point, tiret ou underscore.", true)
        }
        VmBootOrder.validate(vm.bootOrder)?.let { message ->
            issues += ValidationIssue("bootOrder", message, true)
        }

        if (vm.diskPath.isNotBlank()) {
            val disk = File(vm.diskPath)
            if (!disk.isFile || !disk.canRead() || !disk.canWrite() || disk.length() <= 0L) {
                issues += ValidationIssue("diskPath", "L'image disque doit exister, être non vide et accessible en lecture/écriture.", true)
            }
        }
        if (os.contains("existing disk") && vm.diskPath.isBlank()) {
            issues += ValidationIssue("diskPath", "Un disque existant doit être sélectionné avant la création.", true)
        }

        if (vm.isoPath.isNotBlank()) {
            val iso = File(vm.isoPath)
            if (!iso.isFile || !iso.canRead() || iso.length() <= 0L) {
                issues += ValidationIssue("isoPath", "L'image ISO sélectionnée est introuvable ou vide.", true)
            }
        }
        if ((os.contains("custom iso") || os.contains("iso personnalis")) && vm.isoPath.isBlank()) {
            issues += ValidationIssue("isoPath", "Une ISO d'installation doit être sélectionnée pour ce profil.", true)
        }

        if (os.contains("android")) {
            if (vm.androidSystemPath.isBlank() || !File(vm.androidSystemPath).isFile) {
                issues += ValidationIssue("androidSystemPath", "Un guest Android complet exige une image system.", true)
            }
            if (vm.androidUserdataPath.isBlank() || !File(vm.androidUserdataPath).isFile) {
                issues += ValidationIssue("androidUserdataPath", "Un guest Android complet exige une image userdata.", true)
            }
            if (vm.kernelPath.isBlank() || !File(vm.kernelPath).isFile) {
                issues += ValidationIssue("kernelPath", "Un guest Android complet exige un kernel explicite.", true)
            }
            if (vm.androidAdbEnabled && vm.networkMode != NetworkMode.NAT) {
                issues += ValidationIssue("androidAdbEnabled", "ADB réseau exige actuellement le mode NAT QEMU.", true)
            }
            if (vm.androidAdbEnabled && vm.androidAdbHostPort !in 1..65535) {
                issues += ValidationIssue("androidAdbHostPort", "Port ADB hôte hors plage 1..65535.", true)
            }
            if (vm.androidAdbEnabled && vm.portForwards.any { it.hostPort == vm.androidAdbHostPort }) {
                issues += ValidationIssue("androidAdbHostPort", "Le port ADB hôte est déjà utilisé par une redirection.", true)
            }
        }
        return issues
    }
}
