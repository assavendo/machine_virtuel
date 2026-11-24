package com.assavendo.machinevirtuel.runtime

import com.assavendo.machinevirtuel.vm.VmConfig
import com.assavendo.machinevirtuel.vm.VmState
import java.io.File

data class RecoveryPlan(
    val vmId: String,
    val previousState: VmState,
    val safeSnapshot: File?,
    val actions: List<String>,
)

object RecoveryManager {
    fun plan(base: File, vm: VmConfig, lastError: String): RecoveryPlan {
        val snapshotRoot = File(base, "snapshots/${vm.id}")
        val snapshots = snapshotRoot.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }.orEmpty()
        val safe = snapshots.firstOrNull()
        val actions = buildList {
            add("Conserver les logs et le dernier état d'erreur.")
            if (safe != null) add("Proposer une restauration depuis ${safe.name}.")
            add("Retenter avec le backend le plus stable.")
            add("Désactiver les optimisations expérimentales avant retry.")
            if (lastError.isNotBlank()) add("Analyser l'erreur: $lastError")
            add("Ne jamais supprimer automatiquement le disque utilisateur.")
        }
        return RecoveryPlan(vm.id, vm.state, safe, actions)
    }
}
