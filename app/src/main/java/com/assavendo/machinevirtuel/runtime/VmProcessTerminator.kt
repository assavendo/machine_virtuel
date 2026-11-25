package com.assavendo.machinevirtuel.runtime

import java.util.concurrent.TimeUnit

internal enum class VmProcessTermination {
    ALREADY_EXITED,
    GRACEFUL,
    TERMINATED,
    FORCED,
    STILL_ALIVE,
}

/**
 * Applies a bounded shutdown policy to a backend process:
 * graceful wait -> destroy -> destroy-forcibly.
 */
internal object VmProcessTerminator {
    private const val GRACEFUL_TIMEOUT_MS = 10_000L
    private const val TERMINATE_TIMEOUT_MS = 2_000L
    private const val FORCE_TIMEOUT_MS = 2_000L

    fun terminate(process: Process, graceful: Boolean): VmProcessTermination {
        if (!process.isAlive) return VmProcessTermination.ALREADY_EXITED

        if (graceful && awaitExit(process, GRACEFUL_TIMEOUT_MS)) {
            return VmProcessTermination.GRACEFUL
        }

        runCatching { process.destroy() }
        if (awaitExit(process, TERMINATE_TIMEOUT_MS)) return VmProcessTermination.TERMINATED

        runCatching { process.destroyForcibly() }
        return if (awaitExit(process, FORCE_TIMEOUT_MS)) {
            VmProcessTermination.FORCED
        } else {
            VmProcessTermination.STILL_ALIVE
        }
    }

    private fun awaitExit(process: Process, timeoutMs: Long): Boolean {
        return try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) || !process.isAlive
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            !process.isAlive
        } catch (_: RuntimeException) {
            !process.isAlive
        }
    }
}
