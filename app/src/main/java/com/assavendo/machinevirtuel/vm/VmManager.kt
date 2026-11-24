package com.assavendo.machinevirtuel.vm

import android.content.Context
import com.assavendo.machinevirtuel.cache.TranslationCacheManager
import com.assavendo.machinevirtuel.host.HostCapabilityScanner
import com.assavendo.machinevirtuel.runtime.RuntimeRegistry

sealed class StartResult {
    data class Started(val processId: Long?, val backend: BackendKind) : StartResult()
    data class NotReady(val reason: String, val backend: BackendKind) : StartResult()
}

class VmManager(context: Context) {
    private val store = VmStore(context)
    private val scanner = HostCapabilityScanner(context)
    private val runtimeRegistry = RuntimeRegistry(context)
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
            ramMb = 2048.coerceAtMost((caps.maxMemoryMb / 2).toInt().coerceAtLeast(768)),
            vCpu = 2.coerceAtMost(caps.cpuCount.coerceAtLeast(1)),
            backend = BackendSelector.select(GuestArch.ARM64, caps),
        )
        val items = store.list()
        items += vm
        store.save(items)
        return vm
    }

    fun start(id: String): StartResult {
        val items = store.list()
        val vm = items.firstOrNull { it.id == id }
            ?: return StartResult.NotReady("VM introuvable", BackendKind.QEMU_TCG)

        val backend = BackendSelector.select(vm.arch, capabilities())
        vm.backend = backend

        if (!runtimeRegistry.isInstalled(backend)) {
            vm.state = VmState.OFF
            store.save(items)
            return StartResult.NotReady(
                "Runtime " + backend.label + " absent. Installe un runtime QEMU/crosvm réellement compatible avant de démarrer une VM.",
                backend,
            )
        }

        vm.state = VmState.OFF
        store.save(items)
        return StartResult.NotReady(
            "Runtime " + backend.label + " détecté, mais le pipeline de lancement full-system n'est pas encore branché. La VM reste volontairement OFF pour éviter un faux démarrage.",
            backend,
        )
    }

    fun stop(id: String) {
        val items = store.list()
        items.firstOrNull { it.id == id }?.let {
            it.state = VmState.OFF
            store.save(items)
        }
    }

    fun delete(id: String) {
        store.save(store.list().filterNot { it.id == id })
        cache.clear(id)
    }

    fun runtimeRoot(): String = runtimeRegistry.rootPath()
}
