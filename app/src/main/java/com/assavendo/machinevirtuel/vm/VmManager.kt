package com.assavendo.machinevirtuel.vm

import android.content.Context
import com.assavendo.machinevirtuel.cache.TranslationCacheManager
import com.assavendo.machinevirtuel.host.HostCapabilityScanner
import com.assavendo.machinevirtuel.runtime.DiskManager
import com.assavendo.machinevirtuel.runtime.RuntimeDiagnostics
import com.assavendo.machinevirtuel.runtime.RuntimeInstaller
import com.assavendo.machinevirtuel.runtime.RuntimePaths

class VmManager(private val context: Context) {
    private val store = VmStore(context)
    private val scanner = HostCapabilityScanner(context)
    private val cache = TranslationCacheManager(context)

    fun list(): List<VmConfig> = store.list()
    fun capabilities() = scanner.scan()
    fun cacheStats() = cache.stats()

    fun createDefault(name: String = "Linux ARM64 VM"): VmConfig {
        val caps = capabilities()
        val vm = VmConfig(
            name = name,
            guestOs = "Linux ARM64",
            arch = GuestArch.ARM64,
            ramMb = 2048.coerceAtMost((caps.maxMemoryMb / 2).toInt().coerceAtLeast(1024)),
            vCpu = minOf(4, caps.cpuCount.coerceAtLeast(1)),
            backend = BackendSelector.select(GuestArch.ARM64, caps),
        )
        DiskManager.ensureVmStorage(context.filesDir, vm)
        val items = store.list()
        items += vm
        store.save(items)
        return vm
    }

    fun setIso(id: String, path: String): VmConfig? =
        store.update(id) { it.isoPath = path }

    fun markStarting(id: String): VmConfig? =
        store.update(id) { it.state = VmState.STARTING }

    fun stop(id: String) {
        store.update(id) { it.state = VmState.OFF }
    }

    fun delete(id: String) {
        val vm = store.list().firstOrNull { it.id == id }
        store.save(store.list().filterNot { it.id == id })
        cache.clear(id)
        vm?.diskPath?.takeIf { it.isNotBlank() }?.let { java.io.File(it).delete() }
        vm?.firmwarePath?.takeIf { it.isNotBlank() }?.let { java.io.File(it).delete() }
    }

    fun runtimeRoot(): String = RuntimePaths(context).root.absolutePath

    fun runtimeReady(): Boolean =
        runCatching { RuntimeInstaller.ensure(context); true }.getOrDefault(false)

    fun runtimeDiagnostics() =
        RuntimeDiagnostics.run(context)
}
