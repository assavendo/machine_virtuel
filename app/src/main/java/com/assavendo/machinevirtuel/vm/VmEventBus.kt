package com.assavendo.machinevirtuel.vm

import java.util.concurrent.CopyOnWriteArrayList

object VmEventBus {
    private val listeners = CopyOnWriteArrayList<VmEventListener>()

    fun register(listener: VmEventListener) {
        listeners += listener
    }

    fun unregister(listener: VmEventListener) {
        listeners -= listener
    }

    fun emit(event: VmEvent) {
        listeners.forEach { listener -> runCatching { listener.onEvent(event) } }
    }
}
