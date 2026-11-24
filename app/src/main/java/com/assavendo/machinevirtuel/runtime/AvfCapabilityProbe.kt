
package com.assavendo.machinevirtuel.runtime

import android.content.Context
import android.os.Build

data class AvfProbeResult(
    val apiLevel: Int,
    val virtualizationServiceClass: Boolean,
    val virtualMachineClass: Boolean,
    val microdroidHint: Boolean,
    val explanation: String,
)

object AvfCapabilityProbe {
    fun probe(context: Context): AvfProbeResult {
        val virtualizationService = runCatching { Class.forName("android.system.virtualmachine.VirtualizationService") }.isSuccess
        val virtualMachine = runCatching { Class.forName("android.system.virtualmachine.VirtualMachine") }.isSuccess
        val microdroid = context.packageManager.hasSystemFeature("android.software.virtualization")
        val explanation = when {
            virtualizationService && virtualMachine ->
                "API Java AVF détectables ; disponibilité et permissions restent dépendantes de l'image système."
            Build.VERSION.SDK_INT >= 31 ->
                "Android récent : AVF peut exister mais les API Java sont optionnelles et non garanties pour une application tierce."
            else ->
                "Version Android trop ancienne pour le chemin AVF attendu."
        }
        return AvfProbeResult(Build.VERSION.SDK_INT, virtualizationService, virtualMachine, microdroid, explanation)
    }
}
