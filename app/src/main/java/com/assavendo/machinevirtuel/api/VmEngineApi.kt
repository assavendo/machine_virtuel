package com.assavendo.machinevirtuel.api

import com.assavendo.machinevirtuel.host.HostCapabilities
import com.assavendo.machinevirtuel.performance.PerformanceAdvice
import com.assavendo.machinevirtuel.vm.OrchestrationDecision
import com.assavendo.machinevirtuel.vm.VmConfig
import java.io.File

interface VmEngineApi {
    fun apiVersion(): Int = API_VERSION
    fun create(config: VmConfig): VmConfig
    fun start(id: String)
    fun stop(id: String)
    fun pause(id: String)
    fun resume(id: String)
    fun snapshot(id: String, label: String): String
    fun restore(id: String, snapshot: String)
    fun delete(id: String)
    fun clone(id: String, newName: String): VmConfig
    fun export(id: String, output: File): File
    fun importPackage(packageFile: File): VmConfig
    fun metrics(id: String): Map<String, Number?>
    fun capabilities(): HostCapabilities
    fun selectBackend(id: String): OrchestrationDecision
    fun optimize(id: String): PerformanceAdvice
}

const val API_VERSION = 1

enum class EngineEventType {
    VM_STARTED, VM_STOPPED, VM_PAUSED, VM_ERROR, VM_BACKEND_CHANGED,
    GPU_ERROR, STORAGE_ERROR, THERMAL_WARNING, MEMORY_WARNING, FALLBACK_ACTIVATED,
    RECOVERY_AVAILABLE,
}
