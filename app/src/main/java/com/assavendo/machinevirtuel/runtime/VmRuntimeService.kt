package com.assavendo.machinevirtuel.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.assavendo.machinevirtuel.host.HostCapabilityScanner
import com.assavendo.machinevirtuel.hypervisor.BackendLaunchSpec
import com.assavendo.machinevirtuel.hypervisor.CrosvmBackend
import com.assavendo.machinevirtuel.hypervisor.HypervisorBackend
import com.assavendo.machinevirtuel.hypervisor.QemuBackend
import com.assavendo.machinevirtuel.performance.HostMetricsCollector
import com.assavendo.machinevirtuel.performance.ResourceAdmissionController
import com.assavendo.machinevirtuel.performance.DeviceTopology
import com.assavendo.machinevirtuel.performance.GlobalVmScheduler
import com.assavendo.machinevirtuel.performance.PerformanceOptimizer
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
        fun queryStatus(vmId: String): String? = processes[vmId]?.let { handle ->
            handle.qmp?.let { QmpController.queryStatus(it) }
                ?: handle.controlSocket?.takeIf { it.exists() }?.let { "crosvm-running" }
        }
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

            val topology = DeviceTopology.read()
            val schedule = GlobalVmScheduler().schedule(store.list(), topology, host.availableMemoryMb)
                .firstOrNull { it.vmId == id }
            val desiredVcpus = schedule?.recommendedVcpus ?: minOf(vm.vCpu, host.cpuCount.coerceAtLeast(1))
            val thermalDecision = PerformanceOptimizer().decide(vm, HostMetricsCollector(this).sample())
            vm.vCpu = minOf(desiredVcpus, thermalDecision.vCpu).coerceAtLeast(1)
            vm.monitoringIntervalMs = thermalDecision.monitoringIntervalMs
            logger.info(
                "CPU scheduler: " + vm.vCpu + " vCPU; " +
                    (schedule?.reason ?: "topology fallback") + "; " + thermalDecision.reason
            )

            val decision = VmOrchestrator().choose(vm, host)
            val candidates = mutableListOf<HypervisorBackend>()
            for (backend in decision.candidates.map { it.backend }) {
                when (backend) {
                    BackendKind.AVF -> Unit
                    BackendKind.GUNYAH, BackendKind.GENIEZONE, BackendKind.HALLA -> {
                        val candidate = CrosvmBackend(backend)
                        if (paths.crosvm.canExecute() && candidate.detect(host, vm)) candidates += candidate
                    }
                    BackendKind.KVM -> {
                        val cros = CrosvmBackend(BackendKind.KVM)
                        if (paths.crosvm.canExecute() && cros.detect(host, vm)) candidates += cros
                        val qemu = QemuBackend(BackendKind.KVM)
                        if (qemu.detect(host, vm) && paths.qemu.canExecute()) candidates += qemu
                    }
                    BackendKind.QEMU_TCG -> {
                        val available = paths.qemu.canExecute() &&
                            (vm.arch == GuestArch.ARM64 || when (vm.arch) {
                                GuestArch.X86_64 -> paths.qemuX86.canExecute() || paths.qemuX86Headless.canExecute()
                                GuestArch.X86_32 -> paths.qemuI386.canExecute()
                                else -> false
                            })
                        if (available) candidates += QemuBackend(BackendKind.QEMU_TCG)
                    }
                }
            }
            check(candidates.isNotEmpty()) {
                "Aucun backend réellement exécutable pour cette VM."
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
                    vm.backend = backend.kind
                    store.update(id) {
                        it.state = VmState.STARTING
                        it.backend = backend.kind
                    }
                    if (vm.sharedDirPath.isNotBlank() && backend.kind == BackendKind.QEMU_TCG) {
                        fsHandle = VirtioFsManager.start(paths, vm, dir, log)
                    }
                    if (vm.tpmEnabled && backend.kind == BackendKind.QEMU_TCG) {
                        tpmHandle = TpmManager.start(paths, vm, dir, log)
                    }

                    val spec = backend.launchSpec(paths, vm.copy(backend = backend.kind), qmp, serial, log)
                    val controlSocket = spec.controlSocket
                    controlSocket?.delete()
                    val process = startBackend(spec, dir, paths, vm.arch, log)
                    try {
                        val ready = spec.qmpSocket
                        if (ready != null) {
                            awaitQmpReady(process, ready)
                        } else {
                            check(controlSocket != null) { "Backend " + backend.kind.label + " has no readiness channel." }
                            awaitCrosvmReady(process, controlSocket)
                        }
                    } catch (t: Throwable) {
                        process.destroy()
                        if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
                        throw t
                    }
                    handle = Handle(
                        process = process,
                        backend = backend.kind,
                        qmp = spec.qmpSocket,
                        controlSocket = controlSocket,
                        tapOwned = tapOwned,
                        virtioFs = fsHandle,
                        tpm = tpmHandle,
                    )
                    if (backend.kind != decision.selected) {
                        updateNotification("Fallback backend " + backend.kind.label)
                        logger.warn("Fallback backend selected: " + backend.kind.label)
                        VmEventBus.emit(VmEvent(id, VmEventType.FALLBACK_ACTIVATED, message = backend.kind.label))
                    }
                    break
                } catch (t: Throwable) {
                    VirtioFsManager.stop(fsHandle)
                    TpmManager.stop(tpmHandle)
                    lastError = t
                    appendLog(log, "BACKEND " + backend.kind.label + " FAILED: " + (t.message ?: "unknown") + "\n")
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

            executor.execute { monitorVm(id, active, vm) }
            executor.execute {
                val exit = runCatching { active.process.waitFor() }.getOrDefault(-1)
                val currentStore = VmStore(this)
                val current = currentStore.list()
                current.firstOrNull { it.id == id }?.let {
                    it.state = if (exit == 0) VmState.OFF else VmState.ERROR
                    if (exit != 0) {
                        val plan = RecoveryManager.plan(
                            filesDir,
                            it,
                            "Backend " + active.backend.label + " exited with code " + exit
                        )
                        RecoveryPlanStore.write(filesDir, plan)
                        VmEventBus.emit(
                            VmEvent(
                                id,
                                VmEventType.RECOVERY_AVAILABLE,
                                message = plan.actions.joinToString(" • ")
                            )
                        )
                    } else {
                        RecoveryPlanStore.clear(filesDir, id)
                    }
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
            if (tapOwned && vm.tapName.isNotBlank()) {
                runCatching { TapNetworkManager().removeInterface(vm.tapName) }
            }
            logger.error("VM start failed", t)
            vm.state = VmState.ERROR
            store.save(store.list().map { if (it.id == id) vm else it })
            val plan = RecoveryManager.plan(filesDir, vm, t.message ?: "VM start failed")
            RecoveryPlanStore.write(filesDir, plan)
            VmEventBus.emit(VmEvent(id, VmEventType.VM_ERROR, message = t.message ?: "VM start failed"))
            VmEventBus.emit(
                VmEvent(id, VmEventType.RECOVERY_AVAILABLE, message = plan.actions.joinToString(" • "))
            )
            updateNotification("VM " + vm.name + ": " + (t.message ?: "start error"))
        }
    }

    private fun monitorVm(id: String, handle: Handle, vm: VmConfig) {
        val collector = HostMetricsCollector(this, handle.qmp?.let(::findQemuPid))
        var lastBalloonRequestMs = 0L
        while (processes[id] === handle && handle.process.isAlive) {
            runCatching {
                val sample = collector.sample()
                VmMetricsRegistry.record(id, sample)
                if (sample.availableMemoryMb < 256L) {
                    VmEventBus.emit(VmEvent(id, VmEventType.MEMORY_WARNING, message = "available RAM < 256 MB"))
                    updateNotification("VM memory pressure • Android available RAM < 256 MB")
                } else if (sample.availableMemoryMb < 512L && vm.balloonEnabled && vm.ramMb >= 768 && handle.qmp != null) {
                    val now = System.currentTimeMillis()
                    if (now - lastBalloonRequestMs >= 30_000L) {
                        val targetMb = (vm.ramMb - 256).coerceAtLeast(512)
                        val targetBytes = targetMb.toLong() * 1024L * 1024L
                        if (QmpController.setBalloonTarget(handle.qmp, targetBytes)) {
                            lastBalloonRequestMs = now
                            VmEventBus.emit(
                                VmEvent(id, VmEventType.MEMORY_WARNING,
                                    message = "QMP accepted guest balloon target " + targetMb +
                                        " MiB; guest driver/reclaim still required")
                            )
                            updateNotification("Memory pressure • guest balloon target requested")
                        }
                    }
                }
                if (sample.isThrottling) {
                    VmEventBus.emit(VmEvent(id, VmEventType.THERMAL_WARNING, message = "thermal throttling"))
                    updateNotification("VM thermal throttling detected")
                }
            }
            Thread.sleep(vm.monitoringIntervalMs.coerceIn(250L, 5000L))
        }
    }

    private fun awaitQmpReady(process: Process, qmp: File) {
        val deadline = System.currentTimeMillis() + 15_000L
        var lastStatus: String? = null
        while (System.currentTimeMillis() < deadline) {
            check(process.isAlive) { "QEMU a quitté avant d'ouvrir son canal QMP." }
            lastStatus = QmpController.queryStatus(qmp)
            if (lastStatus != null) return
            Thread.sleep(100L)
        }
        error("QEMU ne fournit pas de réponse QMP après 15 secondes; VM non publiée comme démarrée. Dernier état: " +
            (lastStatus ?: "aucune réponse"))
    }

    private fun findQemuPid(qmp: File): Long? {
        val entries = File("/proc").listFiles() ?: return null
        for (entry in entries) {
            if (!entry.name.all { it.isDigit() }) continue
            val cmdline = runCatching { File(entry, "cmdline").readBytes().toString(Charsets.UTF_8) }
                .getOrNull() ?: continue
            if (cmdline.contains("qemu-system") && cmdline.contains(qmp.absolutePath)) {
                return entry.name.toLongOrNull()
            }
        }
        return null
    }

    private fun startBackend(
        spec: BackendLaunchSpec,
        dir: File,
        paths: RuntimePaths,
        arch: GuestArch,
        log: File,
    ): Process {
        val environment = buildMap {
            putAll(spec.environment)
            put("LD_LIBRARY_PATH", paths.libraryDir(arch).absolutePath)
        }
        val process = RootProcessLauncher.start(
            spec.command, dir, environment, preferRoot = false
        )
        attachLogs(process, log)
        return process
    }

    private fun awaitCrosvmReady(process: Process, socket: File) {
        val deadline = System.currentTimeMillis() + 15_000L
        while (System.currentTimeMillis() < deadline) {
            check(process.isAlive) { "crosvm a quitté avant d'ouvrir son canal de contrôle." }
            if (socket.isFile || socket.exists()) return
            Thread.sleep(100L)
        }
        error("crosvm ne fournit pas de socket de contrôle après 15 secondes.")
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
        val ok = when {
            handle.qmp != null -> QmpController.pause(handle.qmp)
            handle.controlSocket != null -> runCrosvmControl(RuntimePaths(this), "suspend", handle.controlSocket)
            else -> false
        }
        if (ok) {
            VmStore(this).update(id) { it.state = VmState.PAUSED }
            VmEventBus.emit(VmEvent(id, VmEventType.VM_PAUSED))
            updateNotification("VM paused")
        }
    }

    private fun resumeVm(id: String) {
        val handle = processes[id] ?: return
        val ok = when {
            handle.qmp != null -> QmpController.resume(handle.qmp)
            handle.controlSocket != null -> runCrosvmControl(RuntimePaths(this), "resume", handle.controlSocket)
            else -> false
        }
        if (ok) {
            VmStore(this).update(id) { it.state = VmState.RUNNING }
            VmEventBus.emit(VmEvent(id, VmEventType.VM_RESUMED))
            updateNotification("VM resumed")
        }
    }

    private fun rebootVm(id: String) {
        val handle = processes[id] ?: return
        if (handle.qmp != null && QmpController.reset(handle.qmp)) {
            updateNotification("VM reboot requested")
        } else {
            updateNotification("Reboot unavailable for the selected crosvm backend")
        }
    }

    private fun stopVm(id: String, force: Boolean) {
        val handle = processes[id] ?: return
        VmStore(this).update(id) { it.state = VmState.STOPPING }
        val clean = when {
            force -> false
            handle.qmp != null -> QmpController.systemPowerdown(handle.qmp)
            handle.controlSocket != null -> runCrosvmControl(RuntimePaths(this), "stop", handle.controlSocket)
            else -> false
        }
        if (clean) {
            if (!handle.process.waitFor(10, TimeUnit.SECONDS)) handle.process.destroy()
        } else {
            handle.process.destroy()
            if (force && handle.process.isAlive) handle.process.destroyForcibly()
        }
        handle.controlSocket?.delete()
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

    private fun runCrosvmControl(paths: RuntimePaths, action: String, socket: File): Boolean {
        require(action in setOf("suspend", "resume", "stop")) { "Action crosvm non autorisée." }
        if (!socket.exists()) return false
        val process = runCatching {
            ProcessBuilder(paths.crosvm.absolutePath, action, socket.absolutePath)
                .redirectErrorStream(true)
                .also { it.environment()["LD_LIBRARY_PATH"] = paths.libDir.absolutePath }
                .start()
        }.getOrNull() ?: return false
        val completed = process.waitFor(5, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return false
        }
        return process.exitValue() == 0
    }

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
        val qmp: File?,
        val controlSocket: File?,
        val tapOwned: Boolean,
        val virtioFs: VirtioFsHandle?,
        val tpm: TpmHandle?,
    )
}
