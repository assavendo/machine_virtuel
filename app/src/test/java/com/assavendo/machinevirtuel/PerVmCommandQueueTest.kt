package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.runtime.PerVmCommandQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PerVmCommandQueueTest {
    @Test
    fun commandsForOneVmRunInFifoOrder() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val queue = PerVmCommandQueue(executor)
            val started = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val completed = CountDownLatch(2)
            val events = CopyOnWriteArrayList<String>()

            queue.execute("vm-a") {
                events += "first-start"
                started.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS))
                events += "first-end"
                completed.countDown()
            }
            queue.execute("vm-a") {
                events += "second"
                completed.countDown()
            }

            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("first-start"), events.toList())
            releaseFirst.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("first-start", "first-end", "second"), events.toList())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun differentVmsCanExecuteConcurrently() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val queue = PerVmCommandQueue(executor)
            val entered = CountDownLatch(2)
            val release = CountDownLatch(1)
            val completed = CountDownLatch(2)

            repeat(2) { index ->
                queue.execute("vm-$index") {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    completed.countDown()
                }
            }

            assertTrue(entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun aFailedCommandDoesNotBlockLaterCommandsForTheSameVm() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val failures = CopyOnWriteArrayList<Throwable>()
            val queue = PerVmCommandQueue(executor) { _, failure -> failures += failure }
            val completed = CountDownLatch(1)

            queue.execute("vm-a") { error("simulated lifecycle failure") }
            queue.execute("vm-a") { completed.countDown() }

            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(1, failures.size)
            assertEquals("simulated lifecycle failure", failures.single().message)
        } finally {
            executor.shutdownNow()
        }
    }
}
