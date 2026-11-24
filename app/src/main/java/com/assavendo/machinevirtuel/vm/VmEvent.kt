package com.assavendo.machinevirtuel.vm

enum class VmEventType {
    VM_STARTED, VM_STOPPED, VM_PAUSED, VM_RESUMED, VM_ERROR,
    VM_BACKEND_CHANGED, GPU_ERROR, STORAGE_ERROR, THERMAL_WARNING,
    MEMORY_WARNING, FALLBACK_ACTIVATED, SNAPSHOT_CREATED, SNAPSHOT_RESTORED,
    PACKAGE_EXPORTED, PACKAGE_IMPORTED,
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
