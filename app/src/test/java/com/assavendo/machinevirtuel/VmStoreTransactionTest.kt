package com.assavendo.machinevirtuel

import android.content.SharedPreferences
import com.assavendo.machinevirtuel.vm.VmConfig
import com.assavendo.machinevirtuel.vm.VmState
import com.assavendo.machinevirtuel.vm.VmStore
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VmStoreTransactionTest {
    @Test
    fun stateUpdateCannotBeLostBehindConcurrentVmInsertion() {
        val preferences = inMemoryPreferences()
        val store = VmStore(preferences)
        val runtimeStore = VmStore(preferences)
        val existing = VmConfig(name = "existing")
        val inserted = VmConfig(name = "inserted")
        store.insertUnique(existing)

        val mutationEntered = CountDownLatch(1)
        val releaseMutation = CountDownLatch(1)
        val updateStarted = CountDownLatch(1)
        val updateCompleted = CountDownLatch(1)

        val insertionThread = thread(name = "vm-store-insert") {
            store.mutate { items ->
                mutationEntered.countDown()
                check(releaseMutation.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to complete test mutation."
                }
                items.add(inserted)
            }
        }
        assertTrue(mutationEntered.await(5, TimeUnit.SECONDS))

        val updateThread = thread(name = "vm-store-update") {
            updateStarted.countDown()
            runtimeStore.update(existing.id) { it.state = VmState.RUNNING }
            updateCompleted.countDown()
        }
        assertTrue(updateStarted.await(5, TimeUnit.SECONDS))

        // The updater must wait for the insert transaction to commit.
        assertFalse(updateCompleted.await(100, TimeUnit.MILLISECONDS))
        releaseMutation.countDown()
        insertionThread.join(5_000)
        updateThread.join(5_000)

        assertFalse(insertionThread.isAlive)
        assertFalse(updateThread.isAlive)
        val all = store.list()
        assertEquals(2, all.size)
        assertEquals(VmState.RUNNING, all.single { it.id == existing.id }.state)
        assertTrue(all.any { it.id == inserted.id })
    }

    @Test
    fun perVmLocksSerializeOneVmWithoutBlockingAnother() {
        val preferences = inMemoryPreferences()
        val serviceStore = VmStore(preferences)
        val managerStore = VmStore(preferences)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val otherVmCompleted = CountDownLatch(1)
        val sameVmAttempting = CountDownLatch(1)
        val sameVmCompleted = CountDownLatch(1)

        val first = thread(name = "vm-lock-a1") {
            serviceStore.withVmLock("vm-a") {
                firstEntered.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS))
            }
        }
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))

        val otherVm = thread(name = "vm-lock-b") {
            managerStore.withVmLock("vm-b") { otherVmCompleted.countDown() }
        }
        assertTrue(otherVmCompleted.await(1, TimeUnit.SECONDS))

        val sameVm = thread(name = "vm-lock-a2") {
            sameVmAttempting.countDown()
            managerStore.withVmLock("vm-a") { sameVmCompleted.countDown() }
        }
        assertTrue(sameVmAttempting.await(5, TimeUnit.SECONDS))
        assertFalse(sameVmCompleted.await(100, TimeUnit.MILLISECONDS))

        releaseFirst.countDown()
        first.join(5_000)
        otherVm.join(5_000)
        sameVm.join(5_000)
        assertFalse(first.isAlive)
        assertFalse(otherVm.isAlive)
        assertFalse(sameVm.isAlive)
        assertTrue(sameVmCompleted.count == 0L)
    }

    @Test
    fun nameWhitespaceCannotBypassCaseInsensitiveUniqueness() {
        val store = VmStore(inMemoryPreferences())
        store.insertUnique(VmConfig(name = "Phoenix"))
        try {
            store.insertUnique(VmConfig(name = "  phoenix  "))
            throw AssertionError("Expected duplicate normalized name to be rejected.")
        } catch (_: IllegalArgumentException) {
            // Expected: names are trimmed before case-insensitive uniqueness checks.
        }
        assertEquals("Phoenix", store.list().single().name)
    }

    @Test
    fun concurrentSameNameInsertionsCannotPublishDuplicates() {
        val preferences = inMemoryPreferences()
        val storeA = VmStore(preferences)
        val storeB = VmStore(preferences)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val successful = java.util.concurrent.atomic.AtomicInteger(0)
        val rejected = java.util.concurrent.atomic.AtomicInteger(0)

        repeat(2) { index ->
            thread(name = "vm-store-duplicate-$index") {
                try {
                    check(start.await(5, TimeUnit.SECONDS))
                    (if (index == 0) storeA else storeB).insertUnique(VmConfig(name = "same-name"))
                    successful.incrementAndGet()
                } catch (_: IllegalArgumentException) {
                    rejected.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, successful.get())
        assertEquals(1, rejected.get())
        assertEquals(1, storeA.list().size)
    }

    private fun inMemoryPreferences(): SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        val proxyClassLoader = VmStoreTransactionTest::class.java.classLoader
        var editor: SharedPreferences.Editor? = null

        editor = Proxy.newProxyInstance(
            proxyClassLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            val key = args?.getOrNull(0) as? String
            when (method.name) {
                "putString", "putInt", "putLong", "putFloat", "putBoolean", "putStringSet" -> {
                    synchronized(values) { values[key!!] = args?.getOrNull(1) }
                    editor
                }
                "remove" -> {
                    synchronized(values) { values.remove(key) }
                    editor
                }
                "clear" -> {
                    synchronized(values) { values.clear() }
                    editor
                }
                "apply" -> null
                "commit" -> true
                "toString" -> "InMemorySharedPreferences.Editor"
                else -> null
            }
        } as SharedPreferences.Editor

        return Proxy.newProxyInstance(
            proxyClassLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            val key = args?.getOrNull(0) as? String
            synchronized(values) {
                when (method.name) {
                    "getAll" -> values.toMutableMap()
                    "getString" -> values[key] as? String ?: args?.getOrNull(1) as? String
                    "getInt" -> values[key] as? Int ?: args?.getOrNull(1) as? Int ?: 0
                    "getLong" -> values[key] as? Long ?: args?.getOrNull(1) as? Long ?: 0L
                    "getFloat" -> values[key] as? Float ?: args?.getOrNull(1) as? Float ?: 0f
                    "getBoolean" -> values[key] as? Boolean ?: args?.getOrNull(1) as? Boolean ?: false
                    "getStringSet" -> (values[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet()
                        ?: (args?.getOrNull(1) as? Set<*>)?.filterIsInstance<String>()?.toMutableSet()
                    "contains" -> values.containsKey(key)
                    "edit" -> editor
                    "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null
                    "toString" -> "InMemorySharedPreferences"
                    else -> null
                }
            }
        } as SharedPreferences
    }
}
