package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.runtime.RuntimeLifecycleGate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeLifecycleGateTest {
    @Test
    fun closedGateRejectsNewRuntimeActions() {
        val gate = RuntimeLifecycleGate()
        val closeCalls = AtomicInteger()
        val actionCalls = AtomicInteger()

        gate.close { closeCalls.incrementAndGet() }

        assertTrue(gate.isClosed())
        assertFalse(gate.runIfOpen { actionCalls.incrementAndGet() })
        assertEquals(0, actionCalls.get())
        assertEquals(1, closeCalls.get())
    }

    @Test
    fun closeWaitsForAnAlreadyAdmittedCriticalSection() {
        val gate = RuntimeLifecycleGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)

        val admittedResult = java.util.concurrent.atomic.AtomicBoolean(false)
        val admitted = thread(name = "runtime-gate-admitted") {
            admittedResult.set(
                gate.runIfOpen {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            )
        }

        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val closer = thread(name = "runtime-gate-close") {
            gate.close { closeEntered.countDown() }
        }

        assertFalse(closeEntered.await(100, TimeUnit.MILLISECONDS))
        release.countDown()

        admitted.join(5_000)
        closer.join(5_000)
        assertFalse(admitted.isAlive)
        assertFalse(closer.isAlive)
        assertTrue(admittedResult.get())
        assertTrue(closeEntered.await(5, TimeUnit.SECONDS))
        assertFalse(gate.runIfOpen { error("Action must not run after close.") })
    }

    @Test
    fun closeActionRunsOnlyOnce() {
        val gate = RuntimeLifecycleGate()
        val calls = AtomicInteger()

        gate.close { calls.incrementAndGet() }
        gate.close { calls.incrementAndGet() }

        assertEquals(1, calls.get())
    }
}
