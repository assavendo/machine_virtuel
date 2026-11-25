package com.assavendo.machinevirtuel.vm

enum class VmEventType {
    VM_STARTED, VM_STOPPED, VM_PAUSED, VM_RESUMED, VM_ERROR,
    VM_BACKEND_CHANGED, GPU_ERROR, STORAGE_ERROR, THERMAL_WARNING,
    MEMORY_WARNING, RESOURCE_POLICY_UPDATED, FALLBACK_ACTIVATED, SNAPSHOT_CREATED, SNAPSHOT_RESTORED,
    PACKAGE_EXPORTED, PACKAGE_IMPORTED, RECOVERY_AVAILABLE,
}

data class VmEvent(
    val vmId: String,
    val type: VmEventType,
    val timestampMs: Long = System.currentTimeMillis(),
    val message: String = "",
)

fun interface VmEventListener {
    fun onEvent(event: VmEvent)
}
