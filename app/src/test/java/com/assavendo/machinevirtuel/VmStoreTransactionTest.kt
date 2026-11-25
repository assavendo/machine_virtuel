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
        val store = VmStore(inMemoryPreferences())
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
            store.update(existing.id) { it.state = VmState.RUNNING }
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
    fun concurrentSameNameInsertionsCannotPublishDuplicates() {
        val store = VmStore(inMemoryPreferences())
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val successful = java.util.concurrent.atomic.AtomicInteger(0)
        val rejected = java.util.concurrent.atomic.AtomicInteger(0)

        repeat(2) { index ->
            thread(name = "vm-store-duplicate-$index") {
                try {
                    check(start.await(5, TimeUnit.SECONDS))
                    store.insertUnique(VmConfig(name = "same-name"))
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
        assertEquals(1, store.list().size)
    }

    private fun inMemoryPreferences(): SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        var editor: SharedPreferences.Editor? = null

        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
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
            SharedPreferences::class.java.classLoader,
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
