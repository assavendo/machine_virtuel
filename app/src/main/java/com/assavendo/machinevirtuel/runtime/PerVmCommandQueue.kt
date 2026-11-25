package com.assavendo.machinevirtuel.runtime

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Dispatches commands in FIFO order per VM while allowing different VMs to run concurrently.
 *
 * The queue owns no thread; its caller supplies the executor and its lifecycle.
 */
internal class PerVmCommandQueue(
    private val executor: Executor,
    private val onFailure: (vmId: String, failure: Throwable) -> Unit = { _, _ -> },
) {
    private val queues = ConcurrentHashMap<String, SerialQueue>()

    fun execute(vmId: String, command: () -> Unit) {
        require(vmId.isNotBlank()) { "VM id must not be blank." }
        queues.computeIfAbsent(vmId) { id ->
            SerialQueue(executor) { failure ->
                runCatching { onFailure(id, failure) }
            }
        }.execute(command)
    }

    private class SerialQueue(
        private val executor: Executor,
        private val onFailure: (Throwable) -> Unit,
    ) {
        private val lock = Any()
        private val pending = ArrayDeque<() -> Unit>()
        private var draining = false

        fun execute(command: () -> Unit) {
            val shouldDispatch = synchronized(lock) {
                pending.addLast(command)
                if (draining) {
                    false
                } else {
                    draining = true
                    true
                }
            }
            if (shouldDispatch) dispatchNext()
        }

        private fun dispatchNext() {
            val next = synchronized(lock) {
                val command = pending.pollFirst()
                if (command == null) draining = false
                command
            } ?: return

            try {
                executor.execute {
                    try {
                        next()
                    } catch (failure: Throwable) {
                        onFailure(failure)
                    } finally {
                        dispatchNext()
                    }
                }
            } catch (rejected: RejectedExecutionException) {
                synchronized(lock) {
                    pending.clear()
                    draining = false
                }
                onFailure(rejected)
            }
        }
    }
}
