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
import com.assavendo.machinevirtuel.cache.TranslationCacheManager
import com.assavendo.machinevirtuel.performance.AdaptiveResourceCoordinator
import com.assavendo.machinevirtuel.performance.VmCpuThreadSampler
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
    private val lifecycleCommands = PerVmCommandQueue(executor) { id, failure ->
        runCatching { VmLogger(this, id).error("VM lifecycle command failed", failure) }
        VmEventBus.emit(
            VmEvent(
                id,
                VmEventType.VM_ERROR,
                message = failure.message ?: "VM lifecycle command failed",
            )
        )
    }
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
            ACTION_START -> lifecycleCommands.execute(id) { startVm(id) }
            ACTION_STOP -> lifecycleCommands.execute(id) { stopVm(id, false) }
            ACTION_FORCE_STOP -> lifecycleCommands.execute(id) { stopVm(id, true) }
            ACTION_PAUSE -> lifecycleCommands.execute(id) { pauseVm(id) }
            ACTION_RESUME -> lifecycleCommands.execute(id) { resumeVm(id) }
            ACTION_REBOOT -> lifecycleCommands.execute(id) { rebootVm(id) }
        }
        return START_STICKY
    }

    private fun startVm(id: String) {
        val store = VmStore(this)
        val logger = VmLogger(this, id)
        val original = store.list().firstOrNull { it.id == id } ?: return
        val existing = processes[id]
        if (existing != null) {
            if (existing.process.isAlive) return
            val exit = runCatching { existing.process.exitValue() }.getOrDefault(-1)
            finalizeBackendExit(
                id,
                existing,
                exit,
                requestedStop = existing.stopRequested,
                recoveryRequired = existing.recoveryRequiredOnExit,
            )
        }

        val vm = original.copy()
        var tapOwned = false
        var lastError: Throwable? = null
        var launchedHandle: Handle? = null

        try {
            VmSpecValidator.validate(vm).firstOrNull { it.fatal }?.let { error(it.message) }
            val paths = RuntimeInstaller.ensure(this)
            val usbAttachments = UsbPassthroughManager.resolveAll(this, vm.usbPassthrough)

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
            val launchCandidates = if (usbAttachments.isNotEmpty()) {
                candidates.filterIsInstance<QemuBackend>()
            } else {
                candidates
            }
            check(launchCandidates.isNotEmpty()) {
                if (usbAttachments.isNotEmpty()) {
                    "Le passthrough USB hôte exige un backend QEMU système réellement exécutable."
                } else {
                    "Aucun backend réellement exécutable pour cette VM."
                }
            }

            val dir = FilePaths.vmDir(id)
            val qmp = FilePaths.qmp(id)
            val serial = FilePaths.serial(id)
            val log = FilePaths.log(id)
            qmp.delete()
            serial.delete()
            log.delete()

            var handle: Handle? = null
            for (backend in launchCandidates) {
                var fsHandle: VirtioFsHandle? = null
                var tpmHandle: TpmHandle? = null
                try {
                    vm.backend = backend.kind
                    store.update(id) {
                        it.state = VmState.STARTING
                        it.backend = backend.kind
                    }
                    if (vm.sharedDirPath.isNotBlank() && backend.supportsVirtioFsSidecar()) {
                        fsHandle = VirtioFsManager.start(paths, vm, dir, log)
                    }
                    if (vm.tpmEnabled && backend.supportsTpmSidecar()) {
                        tpmHandle = TpmManager.start(paths, vm, dir, log)
                    }

                    val spec = backend.launchSpec(
                        paths,
                        vm.copy(backend = backend.kind),
                        qmp,
                        serial,
                        log,
                        usbPassthrough = usbAttachments,
                    )
                    val controlSocket = spec.controlSocket
                    controlSocket?.delete()
                    val process = startBackend(
                        spec,
                        dir,
                        paths,
                        vm.arch,
                        log,
                        preferRoot = usbAttachments.any { it.requiresRoot },
                    )
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
            launchedHandle = active
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
                lifecycleCommands.execute(id) {
                    finalizeBackendExit(
                        id,
                        active,
                        exit,
                        requestedStop = active.stopRequested,
                        recoveryRequired = active.recoveryRequiredOnExit,
                    )
                }
            }
        } catch (t: Throwable) {
            launchedHandle?.let { active ->
                if (VmProcessTerminator.terminate(active.process, graceful = false) != VmProcessTermination.STILL_ALIVE) {
                    processes.remove(id, active)
                    cleanupHandleResources(id, active)
                }
            }
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
        val coordinator = AdaptiveResourceCoordinator()
        var lastBalloonRequestMs = 0L
        var lastPolicyMessage = ""
        var lastPolicyInputsRefreshMs = 0L
        var lastTelemetryRefreshMs = 0L
        var cachedHost: com.assavendo.machinevirtuel.host.HostCapabilities? = null
        var cachedTopology: com.assavendo.machinevirtuel.performance.CpuTopology? = null
        var cachedVms: List<VmConfig> = emptyList()
        var cachedVcpuThreadIds: List<Long> = emptyList()
        var cachedTranslationAggregate = TranslationCacheManager(this).aggregate(id)
        val vcpuSampler = VmCpuThreadSampler()
        val audioMonitor = if (vm.audioEnabled) {
            AudioLogMonitor(FilePaths.log(id))
        } else {
            null
        }

        while (processes[id] === handle && handle.process.isAlive) {
            runCatching {
                val sample = collector.sample()
                val now = System.currentTimeMillis()
                if (cachedHost == null || now - lastPolicyInputsRefreshMs >= 5_000L) {
                    val store = VmStore(this)
                    cachedHost = HostCapabilityScanner(this).scan()
                    cachedTopology = DeviceTopology.read()
                    cachedVms = store.list()
                    lastPolicyInputsRefreshMs = now
                }
                if (handle.qmp != null && now - lastTelemetryRefreshMs >= 5_000L) {
                    cachedVcpuThreadIds = QmpController.queryVcpuThreadIds(handle.qmp)
                    cachedTranslationAggregate = TranslationCacheManager(this).aggregate(id)
                    lastTelemetryRefreshMs = now
                }

                val vCpuUtilization = vcpuSampler.sample(cachedVcpuThreadIds)
                val audioUnderruns = audioMonitor?.sample()
                VmMetricsRegistry.record(
                    id,
                    sample,
                    fps = VmMetricsRegistry.last(id, 1).asReversed().firstOrNull()?.fps,
                    vCpuUtilization = vCpuUtilization,
                    cacheHitRate = cachedTranslationAggregate.cacheHitRate,
                    translationMs = cachedTranslationAggregate.averageTranslationMs,
                    audioUnderruns = audioUnderruns,
                    audioLatencyConfiguredMs = vm.audioBufferMs.toDouble().takeIf { vm.audioEnabled },
                )
                val host = cachedHost ?: error("Host capability cache unavailable.")
                val topology = cachedTopology ?: error("CPU topology cache unavailable.")
                val decision = coordinator.decide(
                    vm = vm,
                    allVms = cachedVms,
                    topology = topology,
                    host = host,
                    metrics = sample,
                )
                if (decision.reason != lastPolicyMessage) {
                    lastPolicyMessage = decision.reason
                    VmEventBus.emit(
                        VmEvent(
                            id,
                            VmEventType.RESOURCE_POLICY_UPDATED,
                            message =
                                "vCPU " + decision.recommendedVcpus + "/" + decision.configuredVcpus +
                                    " • RAM " + decision.recommendedRamMb + "/" + decision.configuredRamMb +
                                    " MiB • " + decision.reason
                        )
                    )
                }

                if (sample.availableMemoryMb < 256L) {
                    VmEventBus.emit(VmEvent(id, VmEventType.MEMORY_WARNING, message = "available RAM < 256 MB"))
                    updateNotification("VM memory pressure • Android available RAM < 256 MB")
                }

                if (decision.shouldRequestBalloon && handle.qmp != null) {
                    val now = System.currentTimeMillis()
                    if (now - lastBalloonRequestMs >= 30_000L) {
                        val targetBytes = decision.recommendedRamMb.toLong() * 1024L * 1024L
                        if (QmpController.setBalloonTarget(handle.qmp, targetBytes)) {
                            lastBalloonRequestMs = now
                            VmEventBus.emit(
                                VmEvent(
                                    id,
                                    VmEventType.MEMORY_WARNING,
                                    message = "QMP accepted adaptive guest balloon target " +
                                        decision.recommendedRamMb + " MiB; guest driver/reclaim still required"
                                )
                            )
                            updateNotification("Adaptive memory pressure policy applied")
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
        preferRoot: Boolean = false,
    ): Process {
        val environment = buildMap {
            putAll(spec.environment)
            put("LD_LIBRARY_PATH", paths.libraryDir(arch).absolutePath)
        }
        val process = RootProcessLauncher.start(
            spec.command, dir, environment, preferRoot = preferRoot
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
        if (!handle.process.isAlive) return
        val store = VmStore(this)
        val vm = store.list().firstOrNull { it.id == id } ?: return
        if (vm.state != VmState.RUNNING) return

        val ok = when {
            handle.qmp != null -> QmpController.pause(handle.qmp)
            handle.controlSocket != null ->
                runCrosvmControl(RuntimePaths(this), "suspend", handle.controlSocket)
            else -> false
        }
        if (ok && processes[id] === handle && handle.process.isAlive) {
            store.update(id) { it.state = VmState.PAUSED }
            VmEventBus.emit(VmEvent(id, VmEventType.VM_PAUSED))
            updateNotification("VM paused")
        }
    }

    private fun resumeVm(id: String) {
        val handle = processes[id] ?: return
        if (!handle.process.isAlive) return
        val store = VmStore(this)
        val vm = store.list().firstOrNull { it.id == id } ?: return
        if (vm.state != VmState.PAUSED) return

        val ok = when {
            handle.qmp != null -> QmpController.resume(handle.qmp)
            handle.controlSocket != null ->
                runCrosvmControl(RuntimePaths(this), "resume", handle.controlSocket)
            else -> false
        }
        if (ok && processes[id] === handle && handle.process.isAlive) {
            store.update(id) { it.state = VmState.RUNNING }
            VmEventBus.emit(VmEvent(id, VmEventType.VM_RESUMED))
            updateNotification("VM resumed")
        }
    }

    private fun rebootVm(id: String) {
        val handle = processes[id] ?: return
        if (!handle.process.isAlive) return
        val state = VmStore(this).list().firstOrNull { it.id == id }?.state ?: return
        if (state != VmState.RUNNING && state != VmState.PAUSED) return
        if (handle.qmp != null && QmpController.reset(handle.qmp)) {
            updateNotification("VM reboot requested")
        } else {
            updateNotification("Reboot unavailable for the selected crosvm backend")
        }
    }

    private fun stopVm(id: String, force: Boolean) {
        val store = VmStore(this)
        val current = store.list().firstOrNull { it.id == id } ?: return
        val handle = processes[id]
        if (handle == null) {
            markMissingRuntimeHandle(id, current)
            return
        }

        if (!handle.process.isAlive) {
            val exit = runCatching { handle.process.exitValue() }.getOrDefault(-1)
            finalizeBackendExit(
                id,
                handle,
                exit,
                requestedStop = handle.stopRequested,
                recoveryRequired = handle.recoveryRequiredOnExit,
            )
            return
        }
        handle.stopRequested = true
        if (force) handle.recoveryRequiredOnExit = true

        if (current.state == VmState.PAUSED && !force) {
            val resumed = when {
                handle.qmp != null -> QmpController.resume(handle.qmp)
                handle.controlSocket != null ->
                    runCrosvmControl(RuntimePaths(this), "resume", handle.controlSocket)
                else -> false
            }
            if (!resumed) {
                VmLogger(this, id).warn("Could not resume paused guest before graceful shutdown; bounded termination fallback will be used.")
            }
        }

        store.update(id) { it.state = VmState.STOPPING }
        val gracefulCommandAccepted = when {
            force -> false
            handle.qmp != null -> QmpController.systemPowerdown(handle.qmp)
            handle.controlSocket != null ->
                runCrosvmControl(RuntimePaths(this), "stop", handle.controlSocket)
            else -> false
        }
        if (!gracefulCommandAccepted) handle.recoveryRequiredOnExit = true
        val termination = VmProcessTerminator.terminate(
            process = handle.process,
            graceful = !force && gracefulCommandAccepted,
        )
        if (termination == VmProcessTermination.STILL_ALIVE) {
            handle.recoveryRequiredOnExit = true
            val message = "Backend process did not exit after graceful/forced termination attempts."
            store.update(id) { it.state = VmState.ERROR }
            VmLogger(this, id).warn(message)
            VmEventBus.emit(VmEvent(id, VmEventType.VM_ERROR, message = message))
            updateNotification("VM " + current.name + ": termination could not be confirmed")
            return
        }
        if (termination == VmProcessTermination.ALREADY_EXITED) {
            val exit = runCatching { handle.process.exitValue() }.getOrDefault(-1)
            finalizeBackendExit(
                id,
                handle,
                exit,
                requestedStop = handle.stopRequested,
                recoveryRequired = handle.recoveryRequiredOnExit,
            )
            return
        }

        val exit = runCatching { handle.process.exitValue() }.getOrDefault(0)
        finalizeBackendExit(
            id,
            handle,
            exit,
            requestedStop = true,
            recoveryRequired = handle.recoveryRequiredOnExit ||
                termination != VmProcessTermination.GRACEFUL,
        )
    }

    private fun markMissingRuntimeHandle(id: String, current: VmConfig) {
        if (current.state !in setOf(
                VmState.STARTING,
                VmState.RUNNING,
                VmState.PAUSED,
                VmState.SUSPENDED,
                VmState.STOPPING,
            )
        ) return

        val message = "Runtime process handle is missing for VM state " + current.state.name
        val store = VmStore(this)
        store.update(id) { it.state = VmState.RECOVERY }
        val plan = RecoveryManager.plan(filesDir, current, message)
        RecoveryPlanStore.write(filesDir, plan)
        VmEventBus.emit(VmEvent(id, VmEventType.VM_ERROR, message = message))
        VmEventBus.emit(
            VmEvent(id, VmEventType.RECOVERY_AVAILABLE, message = plan.actions.joinToString(" • "))
        )
        updateNotification("VM " + current.name + ": recovery required")
    }

    private fun finalizeBackendExit(
        id: String,
        handle: Handle,
        exitCode: Int,
        requestedStop: Boolean,
        recoveryRequired: Boolean = false,
    ) {
        // A delayed waiter for an old process must never remove or overwrite a newer VM run.
        if (!processes.remove(id, handle)) return
        cleanupHandleResources(id, handle)

        val store = VmStore(this)
        val current = store.list()
        val vm = current.firstOrNull { it.id == id }
        val disposition = VmExitPolicy.resolve(exitCode, requestedStop, recoveryRequired)
        val needsRecovery = disposition.requiresRecoveryPlan
        if (vm != null) {
            vm.state = disposition.state
            if (needsRecovery) {
                val reason = if (disposition.unexpectedFailure) {
                    "Backend " + handle.backend.label + " exited unexpectedly with code " + exitCode
                } else {
                    "Backend " + handle.backend.label + " exited after an unclean stop attempt; guest filesystem consistency is not guaranteed"
                }
                val plan = RecoveryManager.plan(filesDir, vm, reason)
                RecoveryPlanStore.write(filesDir, plan)
                VmEventBus.emit(
                    VmEvent(
                        id,
                        VmEventType.RECOVERY_AVAILABLE,
                        message = plan.actions.joinToString(" • "),
                    )
                )
            } else {
                RecoveryPlanStore.clear(filesDir, id)
            }
            store.save(current)
        }

        val eventType = if (disposition.unexpectedFailure) VmEventType.VM_ERROR else VmEventType.VM_STOPPED
        val message = when {
            disposition.unexpectedFailure -> "unexpected exit=$exitCode"
            disposition.state == VmState.RECOVERY -> "unclean termination; recovery required"
            else -> "exit=$exitCode"
        }
        VmEventBus.emit(VmEvent(id, eventType, message = message))
        VmLogger(this, id).info("VM stopped: " + message)
        updateNotification("VM stopped • " + message)
    }

    private fun cleanupHandleResources(id: String, handle: Handle) {
        handle.controlSocket?.delete()
        runCatching { VirtioFsManager.stop(handle.virtioFs) }
            .onFailure { VmLogger(this, id).warn("virtiofsd cleanup failed: " + (it.message ?: "unknown")) }
        runCatching { TpmManager.stop(handle.tpm) }
            .onFailure { VmLogger(this, id).warn("swtpm cleanup failed: " + (it.message ?: "unknown")) }
        if (handle.tapOwned) {
            VmStore(this).list().firstOrNull { it.id == id }?.tapName
                ?.takeIf { it.isNotBlank() }?.let { tapName ->
                    runCatching { TapNetworkManager().removeInterface(tapName) }
                        .onFailure {
                            VmLogger(this, id).warn("TAP cleanup failed: " + (it.message ?: "unknown"))
                        }
                }
        }
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
        processes.entries.toList().forEach { (id, handle) ->
            val store = VmStore(this)
            val vm = store.list().firstOrNull { it.id == id }
            if (vm != null && vm.state != VmState.OFF && vm.state != VmState.ERROR) {
                store.update(id) { it.state = VmState.RECOVERY }
                val plan = RecoveryManager.plan(
                    filesDir,
                    vm,
                    "Android runtime service was destroyed while the VM process was active.",
                )
                RecoveryPlanStore.write(filesDir, plan)
            }
            runCatching { handle.process.destroyForcibly() }
            cleanupHandleResources(id, handle)
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
    ) {
        @Volatile
        var stopRequested: Boolean = false

        @Volatile
        var recoveryRequiredOnExit: Boolean = false
    }
}
