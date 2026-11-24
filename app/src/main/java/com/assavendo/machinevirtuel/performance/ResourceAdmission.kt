package com.assavendo.machinevirtuel.performance

import com.assavendo.machinevirtuel.host.HostCapabilities
import com.assavendo.machinevirtuel.vm.VmConfig
import com.assavendo.machinevirtuel.vm.VmState

data class ResourceAdmission(
    val allowed: Boolean,
    val reason: String,
    val recommendedRamMb: Int,
    val recommendedVcpus: Int,
)

object ResourceAdmissionController {
    fun check(vm: VmConfig, current: List<VmConfig>, host: HostCapabilities): ResourceAdmission {
        val active = current.filter {
            it.id != vm.id && (it.state == VmState.RUNNING || it.state == VmState.STARTING ||
                it.state == VmState.PAUSED || it.state == VmState.SUSPENDED)
        }
        val committedVmRam = active.sumOf { it.ramMb.coerceAtLeast(0).toLong() }
        val reserve = maxOf(768L, host.totalMemoryMb.coerceAtLeast(0L) / 10L)
        val physicalBudget = (host.availableMemoryMb - reserve).coerceAtLeast(0L)
        val configuredBudget = (host.totalMemoryMb * 70L / 100L - committedVmRam).coerceAtLeast(0L)
        val budgetForNewVm = minOf(physicalBudget, configuredBudget)
        val requestedRam = vm.ramMb.toLong()
        val validRam = requestedRam >= 256L
        val allowed = validRam && budgetForNewVm >= requestedRam
        val recommendedRam = minOf(requestedRam.coerceAtLeast(256L), budgetForNewVm)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val recommendedVcpus = vm.vCpu.coerceIn(1, host.cpuCount.coerceAtLeast(1))
        val reason = when {
            !validRam -> "La RAM demandée doit être d'au moins 256 MiB."
            allowed -> "Ressources suffisantes avec réserve Android et plafond agrégé des VM."
            else -> "RAM insuffisante: la VM demande " + requestedRam +
                " MiB, budget prudent disponible " + budgetForNewVm +
                " MiB après réserve Android et VM actives."
        }
        return ResourceAdmission(allowed, reason, recommendedRam, recommendedVcpus)
    }
}
