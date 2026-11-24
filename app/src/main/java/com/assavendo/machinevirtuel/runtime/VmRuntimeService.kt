package com.assavendo.machinevirtuel.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.assavendo.machinevirtuel.host.HostCapabilityScanner
import com.assavendo.machinevirtuel.performance.HostMetricsCollector
import com.assavendo.machinevirtuel.performance.VmMetricsRegistry
import com.assavendo.machinevirtuel.vm.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VmRuntimeService : Service() {
    companion object {
        const val ACTION_START = "phoenixvm.START"
        const val ACTION_STOP = "phoenixvm.STOP"
        const val ACTION_PAUSE = "phoenixvm.PAUSE"
        const val ACTION_RESUME = "phoenixvm.RESUME"
        const val EXTRA_VM_ID = "vm_id"
        private const val CHANNEL = "phoenixvm-runtime"
        private const val NOTIFICATION_ID = 1001
        private val processes = ConcurrentHashMap<String, Handle>()

        fun start(context: Context, vmId: String) = context.startForegroundService(
            Intent(context, VmRuntimeService::class.java).setAction(ACTION_START).putExtra(EXTRA_VM_ID, vmId)
        )
        fun stop(context: Context, vmId: String) = context.startService(
            Intent(context, VmRuntimeService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_VM_ID, vmId)
        )
        fun pause(context: Context, vmId: String) = context.startService(
            Intent(context, VmRuntimeService::class.java).setAction(ACTION_PAUSE).putExtra(EXTRA_VM_ID, vmId)
        )
        fun resume(context: Context, vmId: String) = context.startService(
            Intent(context, VmRuntimeService::class.java).setAction(ACTION_RESUME).putExtra(EXTRA_VM_ID, vmId)
        )
        fun vncPort(vmId: String): Int = VncPorts.portFor(vmId)
    }

    private val executor = Executors.newCachedThreadPool()

    override fun onCreate() {
        super.onCreate()
        FilePaths.init(this)
        createChannel()
        startForeground(NOTIFICATION_ID, notification("VM engine ready"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_VM_ID) ?: return START_NOT_STICKY
        when (intent.action) {
            ACTION_START -> executor.execute { startVm(id) }
            ACTION_STOP -> executor.execute { stopVm(id) }
            ACTION_PAUSE -> executor.execute { pauseVm(id) }
            ACTION_RESUME -> executor.execute { resumeVm(id) }
        }
        return START_STICKY
    }

    private fun startVm(id: String) {
        val store = VmStore(this)
        val logger = VmLogger(this, id)
        logger.info("VM start requested")
        val original = store.list().firstOrNull { it.id == id } ?: return
        if (processes[id]?.process?.isAlive == true) return

        val vm = original.copy()
        var tapOwned = false
        var tpmHandle: TpmHandle? = null
        var lastError: Throwable? = null
        try {
            VmSpecValidator.validate(vm).firstOrNull { it.fatal }?.let { error(it.message) }
            val paths = RuntimeInstaller.ensure(this)
            if (vm.networkMode == NetworkMode.TAP) {
                val tap = TapNetworkManager()
                tapOwned = !tap.exists(vm.tapName)
                check(tap.ensureInterface(vm.tapName)) { "Impossible de preparer l'interface TAP " + vm.tapName }
            }
            DiskManager.ensureVmStorage(filesDir, vm, paths)
            DiskManager.ensureNvram(vm, if (vm.backend == BackendKind.GUNYAH) paths.edk2GunyahVars else paths.edk2QemuVars)

            val host = HostCapabilityScanner(this).scan()
            val decision = VmOrchestrator().choose(vm, host)
            val candidates = decision.candidates.map { it.backend }.distinct().filter {
                it != BackendKind.AVF || AvfCapabilityProbe.probe(this).virtualMachineClass
            }
            check(candidates.isNotEmpty()) { "Aucun backend disponible." }

            val dir = FilePaths.vmDir(id)
            val qmp = FilePaths.qmp(id)
            val serial = FilePaths.serial(id)
            val log = FilePaths.log(id)
            qmp.delete()
            serial.delete()
            log.delete()

            var handle: Handle? = null
            for (backend in candidates) {
                var fsHandle: VirtioFsHandle? = null
                try {
                    vm.backend = backend
                    store.update(id) {
                        it.state = VmState.STARTING
                        it.backend = backend
                    }
                    if (vm.sharedDirPath.isNotBlank() &&
                        (backend == BackendKind.QEMU_TCG || backend == BackendKind.KVM)
                    ) {
                        fsHandle = VirtioFsManager.start(paths, vm, dir, log)
                    }
                    if (vm.tpmEnabled && (backend == BackendKind.QEMU_TCG || backend == BackendKind.KVM)) {
                        tpmHandle = TpmManager.start(paths, vm, dir, log)
                    }

                    val candidate = when (backend) {
                        BackendKind.GUNYAH, BackendKind.GENIEZONE, BackendKind.HALLA ->
                            startCrosvm(paths, vm, dir, log, tapOwned)
                        BackendKind.AVF ->
                            throw UnsupportedOperationException(
                                "AVF détecté mais lifecycle VirtualizationService non exposable à cette app sur cet hôte."
                            )
                        else -> {
                            val process = startQemu(paths, vm, dir, qmp, serial, log, fsHandle?.socket, tpmHandle)
                            Handle(process, backend, qmp, tapOwned, fsHandle, tpmHandle)
                        }
                    }

                    if (!candidate.process.isAlive) error("Backend exited immediately.")
                    handle = candidate
                    if (backend != decision.selected) updateNotification("Fallback backend " + backend.label)
                    break
                } catch (t: Throwable) {
                    VirtioFsManager.stop(fsHandle)
                    TpmManager.stop(tpmHandle)
                    tpmHandle = null
                    lastError = t
                    appendLog(log, "BACKEND " + backend.label + " FAILED: " + (t.message ?: "unknown") + "\n")
                }
            }

            val active = handle ?: throw lastError ?: IllegalStateException("Tous les backends ont échoué.")
            vm.state = VmState.RUNNING
            vm.lastStartedAt = System.currentTimeMillis()
            store.save(store.list().map { if (it.id == id) vm else it })
            processes[id] = active
            updateNotification("VM " + vm.name + " running • " + active.backend.label)
            logger.info("VM running on backend " + active.backend.label)
            executor.execute { monitorVm(id, active, vm.monitoringIntervalMs) }

            executor.execute {
                val exit = runCatching { active.process.waitFor() }.getOrDefault(-1)
                val current = VmStore(this).list()
                current.firstOrNull { it.id == id }?.let {
                    it.state = if (exit == 0) VmState.OFF else VmState.ERROR
                }
                VmStore(this).save(current)
                processes.remove(id)
                VirtioFsManager.stop(active.virtioFs)
                TpmManager.stop(active.tpm)
                if (active.tapOwned) {
                    VmStore(this).list().firstOrNull { it.id == id }?.tapName
                        ?.takeIf { it.isNotBlank() }?.let { TapNetworkManager().removeInterface(it) }
                }
                logger.info("VM stopped with exit code " + exit)
                updateNotification("VM " + vm.name + " stopped • exit " + exit)
            }
        } catch (t: Throwable) {
            logger.error("VM start failed", t)
            vm.state = VmState.ERROR
            store.save(store.list().map { if (it.id == id) vm else it })
            updateNotification("VM " + vm.name + ": " + (t.message ?: "start error"))
        }
    }

    private fun monitorVm(id: String, handle: Handle, intervalMs: Long) {
        val collector = HostMetricsCollector(this)
        while (processes[id] === handle && handle.process.isAlive) {
            runCatching {
                val sample = collector.sample()
                VmMetricsRegistry.record(id, sample)
                if (sample.availableMemoryMb < 256L) updateNotification("VM memory pressure • Android available RAM < 256 MB")
                if (sample.isThrottling) updateNotification("VM thermal throttling detected")
            }
            Thread.sleep(intervalMs.coerceIn(250L, 5000L))
        }
    }

    private fun startQemu(
        paths: RuntimePaths,
        vm: VmConfig,
        dir: File,
        qmp: File,
        serial: File,
        log: File,
        virtioFsSocket: File?,
        tpmHandle: TpmHandle?,
    ): Process {
        val command = QemuCommandBuilder.build(paths, vm, qmp, serial, log, virtioFsSocket, tpmHandle)
        val process = RootProcessLauncher.start(
            command, dir, mapOf("LD_LIBRARY_PATH" to paths.libDir.absolutePath), preferRoot = false
        )
        attachLogs(process, log)
        return process
    }

    private fun startCrosvm(
        paths: RuntimePaths,
        vm: VmConfig,
        dir: File,
        log: File,
        tapOwned: Boolean,
    ): Handle {
        val command = CrosvmCommandBuilder.build(paths, vm)
        val process = RootProcessLauncher.start(
            command, dir, mapOf("LD_LIBRARY_PATH" to paths.libDir.absolutePath), preferRoot = false
        )
        attachLogs(process, log)
        return Handle(process, vm.backend, FilePaths.qmp(vm.id), tapOwned, null)
    }

    private fun attachLogs(process: Process, log: File) {
        Thread {
            process.inputStream.use { input ->
                log.outputStream(append = true).buffered().use { output -> input.copyTo(output) }
            }
        }.start()
        Thread {
            process.errorStream.use { input ->
                log.outputStream(append = true).buffered().use { output -> input.copyTo(output) }
            }
        }.start()
    }

    private fun appendLog(file: File, value: String) {
        runCatching { file.appendText(value) }
    }

    private fun pauseVm(id: String) {
        val handle = processes[id] ?: return
        if ((handle.backend == BackendKind.QEMU_TCG || handle.backend == BackendKind.KVM) &&
            QmpController.pause(handle.qmp)
        ) {
            VmStore(this).update(id) { it.state = VmState.PAUSED }
            updateNotification("VM paused")
        }
    }

    private fun resumeVm(id: String) {
        val handle = processes[id] ?: return
        if ((handle.backend == BackendKind.QEMU_TCG || handle.backend == BackendKind.KVM) &&
            QmpController.resume(handle.qmp)
        ) {
            VmStore(this).update(id) { it.state = VmState.RUNNING }
            updateNotification("VM resumed")
        }
    }

    private fun stopVm(id: String) {
        val handle = processes[id] ?: return
        VmStore(this).update(id) { it.state = VmState.STOPPING }
        if (QmpController.systemPowerdown(handle.qmp)) {
            if (!handle.process.waitFor(10, TimeUnit.SECONDS)) handle.process.destroy()
        } else handle.process.destroy()
        VirtioFsManager.stop(handle.virtioFs)
        TpmManager.stop(handle.tpm)
        processes.remove(id)
        if (handle.tapOwned) {
            VmStore(this).list().firstOrNull { it.id == id }?.tapName
                ?.takeIf { it.isNotBlank() }?.let { TapNetworkManager().removeInterface(it) }
        }
        VmStore(this).update(id) { it.state = VmState.OFF }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "PhoenixVM runtime", NotificationManager.IMPORTANCE_LOW)
        )
    }
    private fun notification(text: String): Notification =
        Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("PhoenixVM").setContentText(text).setOngoing(true).build()
    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        processes.values.toList().forEach {
            VirtioFsManager.stop(it.virtioFs)
            TpmManager.stop(it.tpm)
            it.process.destroy()
        }
        processes.clear()
        executor.shutdownNow()
        super.onDestroy()
    }

    private data class Handle(
        val process: Process,
        val backend: BackendKind,
        val qmp: File,
        val tapOwned: Boolean,
        val virtioFs: VirtioFsHandle?,
        val tpm: TpmHandle?,
    )
}
