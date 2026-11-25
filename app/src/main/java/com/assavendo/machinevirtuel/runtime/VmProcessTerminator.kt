package com.assavendo.machinevirtuel.runtime

import java.util.concurrent.TimeUnit

/**
 * Applies a bounded shutdown policy to a backend process:
 * graceful wait -> SIGTERM/destroy -> SIGKILL/destroyForcibly.
 */
internal object VmProcessTerminator {
    private const val GRACEFUL_TIMEOUT_MS = 10_000L
    private const val TERMINATE_TIMEOUT_MS = 2_000L
    private const val FORCE_TIMEOUT_MS = 2_000L

    fun terminate(process: Process, graceful: Boolean): Boolean {
        if (!process.isAlive) return true

        if (graceful && awaitExit(process, GRACEFUL_TIMEOUT_MS)) return true

        runCatching { process.destroy() }
        if (awaitExit(process, TERMINATE_TIMEOUT_MS)) return true

        runCatching { process.destroyForcibly() }
        return awaitExit(process, FORCE_TIMEOUT_MS)
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
