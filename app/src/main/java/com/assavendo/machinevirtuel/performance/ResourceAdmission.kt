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
        val active = current.filter { it.state == VmState.RUNNING || it.state == VmState.STARTING }
        val usedRam = active.sumOf { it.ramMb.toLong() }
        val reserve = 768L
        val available = (host.availableMemoryMb - usedRam - reserve).coerceAtLeast(0L)
        val maxRam = minOf(vm.ramMb.toLong(), (host.totalMemoryMb * 70L / 100L))
        val allowed = maxRam >= 256L && available >= 256L
        val recommendedRam = minOf(maxRam, available).toInt().coerceAtLeast(256)
        val cores = host.cpuCount.coerceAtLeast(1)
        val recommendedVcpus = vm.vCpu.coerceIn(1, cores)
        val reason = if (allowed) {
            "Ressources compatibles avec la marge Android."
        } else {
            "Marge mémoire insuffisante pour démarrer cette VM sans risque d'OOM."
        }
        return ResourceAdmission(allowed, reason, recommendedRam, recommendedVcpus)
    }
}
