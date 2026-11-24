package com.assavendo.machinevirtuel.api

import android.content.Context
import com.assavendo.machinevirtuel.performance.HostMetricsCollector
import com.assavendo.machinevirtuel.vm.VmManager
import com.assavendo.machinevirtuel.vm.VmStore
import com.assavendo.machinevirtuel.runtime.VmRuntimeService
import java.io.File

class VmEngine(private val context: Context) : VmEngineApi {
    private val manager = VmManager(context)
    private val store = VmStore(context)
    private val metricsCollector = HostMetricsCollector(context)

    override fun create(config: com.assavendo.machinevirtuel.vm.VmConfig) = manager.create(config)
    override fun start(id: String) {
        manager.markStarting(id)
        VmRuntimeService.start(context, id)
    }
    override fun stop(id: String) = VmRuntimeService.stop(context, id)
    override fun pause(id: String) = VmRuntimeService.pause(context, id)
    override fun resume(id: String) = VmRuntimeService.resume(context, id)
    override fun snapshot(id: String, label: String) = manager.snapshot(id, label).absolutePath
    override fun restore(id: String, snapshot: String) = manager.restoreSnapshot(id, File(snapshot))
    override fun delete(id: String) = manager.delete(id)
    override fun clone(id: String, newName: String) = manager.clone(id, newName)
    override fun export(id: String, output: File) = manager.exportVm(id, output)
    override fun importPackage(packageFile: File) = manager.importVm(packageFile)
    override fun metrics(id: String): Map<String, Number> {
        val sample = metricsCollector.sample()
        return mapOf(
            "host_cpu_percent" to sample.processCpuPercent,
            "available_memory_mb" to sample.availableMemoryMb,
            "thermal_status" to sample.thermalStatus,
            "temperature_c" to (sample.skinTemperatureC ?: -1.0),
        )
    }
    override fun capabilities() = manager.capabilities()
    override fun selectBackend(id: String) = manager.selectBackend(id)
    override fun optimize(id: String) = manager.optimize(id)
}
