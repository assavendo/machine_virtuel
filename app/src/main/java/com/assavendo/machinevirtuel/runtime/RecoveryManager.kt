package com.assavendo.machinevirtuel.runtime

import com.assavendo.machinevirtuel.vm.VmConfig
import com.assavendo.machinevirtuel.vm.VmState
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

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


object RecoveryPlanStore {
    fun write(base: File, plan: RecoveryPlan): File {
        val dir = File(base, "recovery").apply { mkdirs() }
        val target = File(dir, plan.vmId + ".json")
        val json = JSONObject()
            .put("vmId", plan.vmId)
            .put("previousState", plan.previousState.name)
            .put("safeSnapshot", plan.safeSnapshot?.absolutePath ?: JSONObject.NULL)
            .put("createdAt", System.currentTimeMillis())
            .put("actions", JSONArray(plan.actions))
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(json.toString(2))
        check(tmp.renameTo(target)) { "Impossible de publier le plan de récupération." }
        return target
    }

    fun read(base: File, vmId: String): RecoveryPlan? {
        val target = File(base, "recovery/$vmId.json")
        if (!target.isFile) return null
        return runCatching {
            val json = JSONObject(target.readText())
            val actions = buildList {
                val array = json.optJSONArray("actions") ?: JSONArray()
                for (i in 0 until array.length()) add(array.optString(i))
            }
            RecoveryPlan(
                vmId = json.getString("vmId"),
                previousState = VmState.valueOf(json.optString("previousState", VmState.ERROR.name)),
                safeSnapshot = json.optString("safeSnapshot").takeIf { it.isNotBlank() && it != "null" }?.let(::File),
                actions = actions,
            )
        }.getOrNull()
    }

    fun clear(base: File, vmId: String) {
        File(base, "recovery/$vmId.json").delete()
    }
}
