package com.assavendo.machinevirtuel.runtime

/**
 * Linearizes command admission, backend registration and service shutdown.
 *
 * An action admitted before close finishes its critical section before close can snapshot
 * active processes. Once close starts, no later action can be admitted.
 */
internal class RuntimeLifecycleGate {
    private val lock = Any()
    private var closed = false

    fun runIfOpen(action: () -> Unit): Boolean = synchronized(lock) {
        if (closed) {
            false
        } else {
            action()
            true
        }
    }

    fun close(action: () -> Unit) {
        synchronized(lock) {
            if (closed) return
            closed = true
            action()
        }
    }

    fun isClosed(): Boolean = synchronized(lock) { closed }
}
