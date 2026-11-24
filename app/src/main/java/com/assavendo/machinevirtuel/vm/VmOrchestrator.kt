package com.assavendo.machinevirtuel.vm

import com.assavendo.machinevirtuel.host.HostCapabilities

data class BackendCandidate(val backend: BackendKind, val score: Int, val reasons: List<String>)
data class OrchestrationDecision(
    val selected: BackendKind,
    val candidates: List<BackendCandidate>,
    val warnings: List<String>,
)

class VmOrchestrator {
    fun choose(vm: VmConfig, host: HostCapabilities): OrchestrationDecision {
        val warnings = mutableListOf<String>()
        val candidates = mutableListOf<BackendCandidate>()
        val requested = if (vm.mode == VmMode.EXPERT) vm.backend else null

        fun add(kind: BackendKind, score: Int, vararg reasons: String) {
            candidates += BackendCandidate(kind, score, reasons.toList())
        }

        if (vm.arch == GuestArch.ARM64) {
            if (host.kvm) add(BackendKind.KVM, 100, "KVM /dev/kvm accessible")
            if (host.gunyah) add(BackendKind.GUNYAH, 98, "Gunyah device detected")
            if (host.genieZone) add(BackendKind.GENIEZONE, 97, "GenieZone device detected")
            if (host.halla) add(BackendKind.HALLA, 96, "Halla device detected")
            if (host.avfAvailable) add(BackendKind.AVF, 95, "AVF / VirtualizationService appears available")
            add(BackendKind.QEMU_TCG, 70, "QEMU TCG universal software fallback")
        } else {
            add(BackendKind.QEMU_TCG, 100, "full-system x86 emulation")
        }

        if (vm.performancePreset == PerformancePreset.ULTRA_PERFORMANCE)
            candidates.replaceAll { it.copy(score = it.score + if (it.backend == BackendKind.QEMU_TCG) -10 else 5) }
        if (vm.mode == VmMode.COMPATIBILITY)
            candidates.replaceAll { it.copy(score = it.score + if (it.backend == BackendKind.QEMU_TCG) 20 else 0) }
        if (host.thermalStatus >= 4) {
            candidates.replaceAll { it.copy(score = it.score - if (it.backend != BackendKind.QEMU_TCG) 5 else 0) }
            warnings += "État thermique élevé : la performance soutenue peut être réduite."
        }

        if (requested != null) {
            if (candidates.any { it.backend == requested })
                return OrchestrationDecision(requested, candidates.sortedByDescending { it.score }, warnings)
            warnings += "Le backend demandé n'est pas disponible ; sélection automatique appliquée."
        }

        val sorted = candidates.sortedByDescending { it.score }
        return OrchestrationDecision(sorted.firstOrNull()?.backend ?: BackendKind.QEMU_TCG, sorted, warnings)
    }
}
