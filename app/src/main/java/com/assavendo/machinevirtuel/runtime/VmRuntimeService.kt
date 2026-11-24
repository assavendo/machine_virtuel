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
import com.assavendo.machinevirtuel.performance.ResourceAdmissionController
import com.assavendo.machinevirtuel.performance.VmMetricsRegistry
import com.assavendo.machinevirtuel.vm.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VmRuntimeService : Service() {
    companion object {
        const val ACTION_START = "phoenixvm.START"
        const val ACTION_STOP = "phoenixvm.STOP"
        const val ACTION_PAUSE = "phoenixvm.PAUSE"
        const val ACTION_RESUME = "phoenixvm.RESUME"
        const val ACTION_REBOOT = "phoenixvm.REBOOT"
        const val ACTION_FORCE_STOP = "phoenixvm.FORCE_STOP"
        const val ACTION_QUERY_STATUS = "phoenixvm.QUERY_STATUS"
        const val EXTRA_VM_ID = "vm_id"
        private const val CHANNEL = "phoenixvm-runtime"
        private const val NOTIFICATION_ID = 1001
        private val processes = ConcurrentHashMap<String, Handle>()

        fun start(context: Context, vmId: String) = send(context, ACTION_START, vmId, true)
        fun stop(context: Context, vmId: String) = send(context, ACTION_STOP, vmId)
        fun forceStop(context: Context, vmId: String) = send(context, ACTION_FORCE_STOP, vmId)
        fun pause(context: Context, vmId: String) = send(context, ACTION_PAUSE, vmId)
        fun resume(context: Context, vmId: String) = send(context, ACTION_RESUME, vmId)
        fun reboot(context: Context, vmId: String) = send(context, ACTION_REBOOT, vmId)
        fun queryStatus(vmId: String): String? = processes[vmId]?.let { QmpController.queryStatus(it.qmp) }
        fun vncPort(vmId: String): Int = VncPorts.portFor(vmId)

        private fun send(context: Context, action: String, vmId: String, foreground: Boolean = false) {
            val intent = Intent(context, VmRuntimeService::class.java).setAction(action).putExtra(EXTRA_VM_ID, vmId)
            if (foreground) context.startForegroundService(intent) else context.startService(intent)
        }
    }

    private val executor = Executors.newCachedThreadPool()
    private val admissionLock = Any()

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
            ACTION_STOP -> executor.execute { stopVm(id, false) }
            ACTION_FORCE_STOP -> executor.execute { stopVm(id, true) }
            ACTION_PAUSE -> executor.execute { pauseVm(id) }
            ACTION_RESUME -> executor.execute { resumeVm(id) }
            ACTION_REBOOT -> executor.execute { rebootVm(id) }
        }
        return START_STICKY
    }

    private fun startVm(id: String) {
        val store = VmStore(this)
        val logger = VmLogger(this, id)
        val original = store.list().firstOrNull { it.id == id } ?: return
        if (processes[id]?.process?.isAlive == true) return

        val vm = original.copy()
        var tapOwned = false
        var lastError: Throwable? = null

        try {
            VmSpecValidator.validate(vm).firstOrNull { it.fatal }?.let { error(it.message) }
            val paths = RuntimeInstaller.ensure(this)

            if (vm.networkMode == NetworkMode.TAP) {
                val tap = TapNetworkManager()
                tapOwned = !tap.exists(vm.tapName)
                check(tap.ensureInterface(vm.tapName)) {
                    "Impossible de preparer l'interface TAP " + vm.tapName
                }
            }

            val host = HostCapabilityScanner(this).scan()
            synchronized(admissionLock) {
                val admission = ResourceAdmissionController.check(vm, store.list(), host)
                check(admission.allowed) { admission.reason }
                store.update(id) { it.state = VmState.STARTING }
            }

            DiskManager.ensureVmStorage(filesDir, vm, paths)
            DiskManager.ensureNvram(vm, DiskManager.firmwareTemplate(paths, vm))

            val decision = VmOrchestrator().choose(vm, host)
            val candidates = decision.candidates.map { it.backend }.distinct().filter { backend ->
                when (backend) {
                    BackendKind.AVF -> false
                    BackendKind.GUNYAH, BackendKind.GENIEZONE, BackendKind.HALLA -> false
                    BackendKind.KVM -> host.kvm
                    BackendKind.QEMU_TCG -> paths.qemu.canExecute() &&
                        (vm.arch == GuestArch.ARM64 || when (vm.arch) {
                            GuestArch.X86_64 -> paths.qemuX86.canExecute() || paths.qemuX86Headless.canExecute()
                            GuestArch.X86_32 -> paths.qemuI386.canExecute()
                            else -> false
                        })
                }
            }
            check(candidates.isNotEmpty()) {
                "Aucun backend réellement exécutable pour cette VM. Les backends privilégiés sont conservés comme indisponibles jusqu'à validation du runtime complet sur l'appareil."
            }

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
                var tpmHandle: TpmHandle? = null
                try {
                    vm.backend = backend
                    store.update(id) {
                        it.state = VmState.STARTING
                        it.backend = backend
                    }
                    if (vm.sharedDirPath.isNotBlank() && backend == BackendKind.QEMU_TCG) {
                        fsHandle = VirtioFsManager.start(paths, vm, dir, log)
                    }
                    if (vm.tpmEnabled && backend == BackendKind.QEMU_TCG) {
                        tpmHandle = TpmManager.start(paths, vm, dir, log)
                    }

                    val process = startQemu(
                        paths, vm, dir, qmp, serial, log, fsHandle?.socket, tpmHandle
                    )
                    if (!process.isAlive) error("QEMU exited immediately.")
                    handle = Handle(process, backend, qmp, tapOwned, fsHandle, tpmHandle)
                    if (backend != decision.selected) {
                        updateNotification("Fallback backend " + backend.label)
                        logger.warn("Fallback backend selected: " + backend.label)
                        VmEventBus.emit(VmEvent(id, VmEventType.FALLBACK_ACTIVATED, message = backend.label))
                    }
                    break
                } catch (t: Throwable) {
                    VirtioFsManager.stop(fsHandle)
                    TpmManager.stop(tpmHandle)
                    lastError = t
                    appendLog(log, "BACKEND " + backend.label + " FAILED: " + (t.message ?: "unknown") + "\n")
                }
            }

            val active = handle ?: throw lastError ?: IllegalStateException("Tous les backends ont échoué.")
            vm.backend = active.backend
            vm.state = VmState.RUNNING
            vm.lastStartedAt = System.currentTimeMillis()
            store.save(store.list().map { if (it.id == id) vm else it })
            processes[id] = active
            VmEventBus.emit(VmEvent(id, VmEventType.VM_STARTED, message = active.backend.label))
            logger.info("VM running on backend " + active.backend.label)
            updateNotification("VM " + vm.name + " running • " + active.backend.label)

            executor.execute { monitorVm(id, active, vm.monitoringIntervalMs) }
            executor.execute {
                val exit = runCatching { active.process.waitFor() }.getOrDefault(-1)
                val currentStore = VmStore(this)
                val current = currentStore.list()
                current.firstOrNull { it.id == id }?.let {
                    it.state = if (exit == 0) VmState.OFF else VmState.ERROR
                }
                currentStore.save(current)
                processes.remove(id)
                VirtioFsManager.stop(active.virtioFs)
                TpmManager.stop(active.tpm)
                if (active.tapOwned) {
                    VmStore(this).list().firstOrNull { it.id == id }?.tapName
                        ?.takeIf { it.isNotBlank() }?.let { TapNetworkManager().removeInterface(it) }
                }
                VmEventBus.emit(VmEvent(id, if (exit == 0) VmEventType.VM_STOPPED else VmEventType.VM_ERROR, message = "exit=$exit"))
                logger.info("VM stopped with exit code " + exit)
                updateNotification("VM " + vm.name + " stopped • exit " + exit)
            }
        } catch (t: Throwable) {
            logger.error("VM start failed", t)
            vm.state = VmState.ERROR
            store.save(store.list().map { if (it.id == id) vm else it })
            VmEventBus.emit(VmEvent(id, VmEventType.VM_ERROR, message = t.message ?: "VM start failed"))
            updateNotification("VM " + vm.name + ": " + (t.message ?: "start error"))
        }
    }

    private fun monitorVm(id: String, handle: Handle, intervalMs: Long) {
        val collector = HostMetricsCollector(this, handle.process.pid())
        while (processes[id] === handle && handle.process.isAlive) {
            runCatching {
                val sample = collector.sample()
                VmMetricsRegistry.record(id, sample)
                if (sample.availableMemoryMb < 256L) {
                    VmEventBus.emit(VmEvent(id, VmEventType.MEMORY_WARNING, message = "available RAM < 256 MB"))
                    updateNotification("VM memory pressure • Android available RAM < 256 MB")
                }
                if (sample.isThrottling) {
                    VmEventBus.emit(VmEvent(id, VmEventType.THERMAL_WARNING, message = "thermal throttling"))
                    updateNotification("VM thermal throttling detected")
                }
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
        val command = QemuCommandBuilder.build(
            paths, vm, qmp, serial, log, virtioFsSocket, tpmHandle
        )
        val process = RootProcessLauncher.start(
            command, dir,
            mapOf("LD_LIBRARY_PATH" to paths.libDir.absolutePath),
            preferRoot = false
        )
        attachLogs(process, log)
        return process
    }

    private fun attachLogs(process: Process, log: File) {
        Thread {
            process.inputStream.use { input ->
                FileOutputStream(log, true).buffered().use { output -> input.copyTo(output) }
            }
        }.start()
        Thread {
            process.errorStream.use { input ->
                FileOutputStream(log, true).buffered().use { output -> input.copyTo(output) }
            }
        }.start()
    }

    private fun appendLog(file: File, value: String) {
        runCatching { file.appendText(value) }
    }

    private fun pauseVm(id: String) {
        val handle = processes[id] ?: return
        if (qmpControlled(handle.backend) && QmpController.pause(handle.qmp)) {
            VmStore(this).update(id) { it.state = VmState.PAUSED }
            VmEventBus.emit(VmEvent(id, VmEventType.VM_PAUSED))
            updateNotification("VM paused")
        }
    }

    private fun resumeVm(id: String) {
        val handle = processes[id] ?: return
        if (qmpControlled(handle.backend) && QmpController.resume(handle.qmp)) {
            VmStore(this).update(id) { it.state = VmState.RUNNING }
            VmEventBus.emit(VmEvent(id, VmEventType.VM_RESUMED))
            updateNotification("VM resumed")
        }
    }

    private fun rebootVm(id: String) {
        val handle = processes[id] ?: return
        if (qmpControlled(handle.backend) && QmpController.reset(handle.qmp)) {
            updateNotification("VM reboot requested")
        } else {
            updateNotification("Reboot unavailable for current backend")
        }
    }

    private fun stopVm(id: String, force: Boolean) {
        val handle = processes[id] ?: return
        VmStore(this).update(id) { it.state = VmState.STOPPING }
        val clean = !force && qmpControlled(handle.backend) &&
            QmpController.systemPowerdown(handle.qmp)
        if (clean) {
            if (!handle.process.waitFor(10, TimeUnit.SECONDS)) handle.process.destroy()
        } else {
            handle.process.destroy()
            if (force && handle.process.isAlive) handle.process.destroyForcibly()
        }
        VirtioFsManager.stop(handle.virtioFs)
        TpmManager.stop(handle.tpm)
        processes.remove(id)
        if (handle.tapOwned) {
            VmStore(this).list().firstOrNull { it.id == id }?.tapName
                ?.takeIf { it.isNotBlank() }?.let { TapNetworkManager().removeInterface(it) }
        }
        VmStore(this).update(id) { it.state = VmState.OFF }
        VmEventBus.emit(VmEvent(id, VmEventType.VM_STOPPED))
    }

    private fun qmpControlled(backend: BackendKind): Boolean =
        backend == BackendKind.QEMU_TCG || backend == BackendKind.KVM

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "PhoenixVM runtime", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("PhoenixVM")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
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
