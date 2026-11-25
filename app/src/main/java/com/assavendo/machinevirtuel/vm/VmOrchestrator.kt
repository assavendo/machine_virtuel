package com.assavendo.machinevirtuel.vm

import com.assavendo.machinevirtuel.engine.NativeEngine
import com.assavendo.machinevirtuel.host.HostCapabilities

data class BackendCandidate(val backend: BackendKind, val score: Int, val reasons: List<String>)
data class OrchestrationDecision(
    val selected: BackendKind,
    val candidates: List<BackendCandidate>,
    val warnings: List<String>,
    val nativePolicyUsed: Boolean = false,
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
            // These crosvm paths are intentionally headless and do not expose Android
            // display or user-mode NAT bridges yet. Never select them for an interactive VM.
            if (vm.headless && vm.networkMode != NetworkMode.NAT) {
                if (host.gunyah) add(BackendKind.GUNYAH, 98, "Gunyah device detected; headless crosvm path")
                if (host.genieZone) add(BackendKind.GENIEZONE, 97, "GenieZone device detected; headless crosvm path")
                if (host.halla) add(BackendKind.HALLA, 96, "Halla device detected; headless crosvm path")
            }
            if (host.avfHint || host.avfAvailable) {
                warnings += "AVF détecté comme indice, mais son bridge de lancement PhoenixVM n'est pas disponible; il n'est pas sélectionné."
            }
            add(BackendKind.QEMU_TCG, 70, "QEMU TCG universal software fallback")
        } else {
            add(BackendKind.QEMU_TCG, 100, "full-system x86 emulation")
        }

        if (vm.performancePreset == PerformancePreset.ULTRA_PERFORMANCE) {
            candidates.replaceAll {
                it.copy(score = it.score + if (it.backend == BackendKind.QEMU_TCG) -10 else 5)
            }
        }

        if (vm.mode == VmMode.COMPATIBILITY) {
            candidates.replaceAll {
                it.copy(score = it.score + if (it.backend == BackendKind.QEMU_TCG) 20 else 0)
            }
        }

        if (host.thermalStatus >= 4) {
            candidates.replaceAll {
                it.copy(score = it.score - if (it.backend != BackendKind.QEMU_TCG) 5 else 0)
            }
            warnings += "État thermique élevé : la performance soutenue peut être réduite."
        }

        if (requested != null) {
            if (candidates.any { it.backend == requested }) {
                return OrchestrationDecision(
                    requested,
                    candidates.sortedByDescending { it.score },
                    warnings,
                    nativePolicyUsed = false,
                )
            }
            warnings += "Backend indisponible : sélection automatique du meilleur fallback appliquée."
        }

        val native = NativeEngine.selectBackend(
            arm64Guest = vm.arch == GuestArch.ARM64,
            kvm = host.kvm,
            gunyah = host.gunyah,
            genieZone = host.genieZone,
            halla = host.halla,
            avf = host.avfAvailable,
            thermalStatus = host.thermalStatus,
        )

        val nativeKind = when (native.backend) {
            "KVM" -> BackendKind.KVM
            "GUNYAH" -> BackendKind.GUNYAH
            "GENIEZONE" -> BackendKind.GENIEZONE
            "HALLA" -> BackendKind.HALLA
            "AVF" -> BackendKind.AVF
            else -> BackendKind.QEMU_TCG
        }

        val sorted = candidates.sortedByDescending { it.score }
        val selected = if (native.nativeAvailable) {
            sorted.firstOrNull { it.backend == nativeKind }?.backend
                ?: sorted.firstOrNull()?.backend
                ?: BackendKind.QEMU_TCG
        } else {
            sorted.firstOrNull()?.backend ?: BackendKind.QEMU_TCG
        }

        if (native.nativeAvailable) {
            warnings += "Politique native Rust utilisée pour la sélection initiale."
        }

        return OrchestrationDecision(
            selected = selected,
            candidates = sorted,
            warnings = warnings,
            nativePolicyUsed = native.nativeAvailable,
        )
    }
}
