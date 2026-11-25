package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.runtime.VmProcessTerminator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VmProcessTerminatorTest {
    @Test
    fun gracefulExitDoesNotEscalate() {
        val process = FakeProcess(listOf(true))

        assertTrue(VmProcessTerminator.terminate(process, graceful = true))
        assertTrue(process.waitCalls == 1)
        assertTrue(process.destroyCalls == 0)
        assertTrue(process.forceCalls == 0)
    }

    @Test
    fun nonResponsiveProcessIsEscalatedAndConfirmedDead() {
        val process = FakeProcess(listOf(false, false, true))

        assertTrue(VmProcessTerminator.terminate(process, graceful = true))
        assertTrue(process.destroyCalls == 1)
        assertTrue(process.forceCalls == 1)
        assertFalse(process.isAlive)
    }

    @Test
    fun failedForceTerminationIsNotReportedAsSuccessful() {
        val process = FakeProcess(
            waitResults = listOf(false, false, false),
            exitsOnForce = false,
        )

        assertFalse(VmProcessTerminator.terminate(process, graceful = true))
        assertTrue(process.forceCalls == 1)
        assertTrue(process.isAlive)
    }

    private class FakeProcess(
        waitResults: List<Boolean>,
        private val exitsOnDestroy: Boolean = false,
        private val exitsOnForce: Boolean = true,
    ) : Process() {
        private val results = ArrayDeque(waitResults)
        private var alive = true
        var waitCalls = 0
            private set
        var destroyCalls = 0
            private set
        var forceCalls = 0
            private set

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitCalls++
            val result = if (results.isEmpty()) !alive else results.removeFirst()
            if (result) alive = false
            return result
        }

        override fun waitFor(): Int {
            alive = false
            return 0
        }

        override fun exitValue(): Int {
            check(!alive) { "Process is still running." }
            return 0
        }

        override fun destroy() {
            destroyCalls++
            if (exitsOnDestroy) alive = false
        }

        override fun destroyForcibly(): Process {
            forceCalls++
            if (exitsOnForce) alive = false
            return this
        }

        override fun isAlive(): Boolean = alive
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }
}
