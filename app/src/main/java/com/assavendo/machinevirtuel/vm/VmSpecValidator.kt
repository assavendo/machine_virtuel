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
        if (vm.displayWidth !in 320..16384) issues += ValidationIssue("displayWidth", "Résolution horizontale invalide.", true)
        if (vm.displayHeight !in 240..16384) issues += ValidationIssue("displayHeight", "Résolution verticale invalide.", true)
        if (vm.sharedDirPath.isNotBlank() && !File(vm.sharedDirPath).exists())
            issues += ValidationIssue("sharedDirPath", "Le dossier partagé n'existe pas encore.", false)
        vm.portForwards.forEachIndexed { index, forward ->
            if (forward.hostPort !in 1..65535 || forward.guestPort !in 1..65535)
                issues += ValidationIssue("portForwards[$index]", "Port hors plage 1..65535.", true)
        }
        if (vm.arch == GuestArch.X86_64 && vm.backend != BackendKind.QEMU_TCG)
            issues += ValidationIssue("backend", "Le guest x86_64 exige actuellement QEMU TCG.", false)
        if (vm.arch == GuestArch.X86_32 && vm.backend != BackendKind.QEMU_TCG)
            issues += ValidationIssue("backend", "Le guest x86 32-bit exige actuellement QEMU TCG.", false)
        if (vm.backend in setOf(BackendKind.GUNYAH, BackendKind.GENIEZONE, BackendKind.HALLA, BackendKind.KVM) &&
            !vm.headless
        ) {
            issues += ValidationIssue(
                "headless",
                "Le backend crosvm/hyperviseur matériel configuré ici est réservé au mode headless tant que le bridge d'affichage Android n'est pas installé.",
                true
            )
        }
        if (vm.backend in setOf(BackendKind.GUNYAH, BackendKind.GENIEZONE, BackendKind.HALLA, BackendKind.KVM) &&
            vm.networkMode == NetworkMode.NAT
        ) {
            issues += ValidationIssue(
                "networkMode",
                "Le chemin crosvm Android ne fournit pas de NAT utilisateur portable; utilisez TAP ou QEMU.",
                true
            )
        }
        if (vm.diskPath.isNotBlank() && !File(vm.diskPath).exists())
            issues += ValidationIssue("diskPath", "L'image disque est introuvable.", true)
        return issues
    }
}
