package com.assavendo.machinevirtuel.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.assavendo.machinevirtuel.vm.BackendKind
import com.assavendo.machinevirtuel.vm.VmState
import com.assavendo.machinevirtuel.vm.VmStore
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

        fun start(context: Context, vmId: String) {
            context.startForegroundService(Intent(context, VmRuntimeService::class.java)
                .setAction(ACTION_START).putExtra(EXTRA_VM_ID, vmId))
        }
        fun stop(context: Context, vmId: String) {
            context.startService(Intent(context, VmRuntimeService::class.java)
                .setAction(ACTION_STOP).putExtra(EXTRA_VM_ID, vmId))
        }
        fun pause(context: Context, vmId: String) {
            context.startService(Intent(context, VmRuntimeService::class.java)
                .setAction(ACTION_PAUSE).putExtra(EXTRA_VM_ID, vmId))
        }
        fun resume(context: Context, vmId: String) {
            context.startService(Intent(context, VmRuntimeService::class.java)
                .setAction(ACTION_RESUME).putExtra(EXTRA_VM_ID, vmId))
        }
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
        val original = store.list().firstOrNull { it.id == id } ?: return
        if (processes[id]?.process?.isAlive == true) return

        val vm = original.copy()
        var tapOwned = false
        try {
            val paths = RuntimeInstaller.ensure(this)
            if (vm.networkMode == com.assavendo.machinevirtuel.vm.NetworkMode.TAP) {
                val tap = TapNetworkManager()
                tapOwned = !tap.exists(vm.tapName)
                check(tap.ensureInterface(vm.tapName)) {
                    "Impossible de preparer l'interface TAP " + vm.tapName
                }
            }
            DiskManager.ensureVmStorage(filesDir, vm, paths)
            DiskManager.ensureNvram(vm, if (vm.backend == BackendKind.GUNYAH) paths.edk2GunyahVars else paths.edk2QemuVars)
            store.save(store.list().map { if (it.id == id) vm else it })

            val dir = FilePaths.vmDir(id)
            val qmp = FilePaths.qmp(id)
            val serial = FilePaths.serial(id)
            val log = FilePaths.log(id)
            qmp.delete()
            serial.delete()
            log.delete()

            val handle = if (
                vm.backend == BackendKind.GUNYAH ||
                vm.backend == BackendKind.GENIEZONE ||
                vm.backend == BackendKind.HALLA
            ) {
                startCrosvm(paths, vm, dir, log)
            } else {
                var activeBackend = vm.backend
                var process = startQemu(paths, vm, dir, qmp, serial, log)
                if (!process.isAlive && activeBackend == BackendKind.KVM) {
                    activeBackend = BackendKind.QEMU_TCG
                    vm.backend = activeBackend
                    process = startQemu(paths, vm, dir, qmp, serial, log)
                }
                Handle(process, activeBackend, qmp, tapOwned)
            }

            check(handle.process.isAlive) { "Backend exited immediately; inspect " + log.absolutePath }

            vm.state = VmState.RUNNING
            vm.lastStartedAt = System.currentTimeMillis()
            store.save(store.list().map { if (it.id == id) vm else it })
            processes[id] = handle
            updateNotification("VM " + vm.name + " running • " + handle.backend.label)

            executor.execute {
                val exit = runCatching { handle.process.waitFor() }.getOrDefault(-1)
                val current = VmStore(this).list()
                current.firstOrNull { it.id == id }?.let {
                    it.state = if (exit == 0) VmState.OFF else VmState.ERROR
                    VmStore(this).save(current)
                }
                processes.remove(id)
                if (handle.tapOwned) {
                    TapNetworkManager().removeInterface(vm.tapName)
                }
                updateNotification("VM " + vm.name + " stopped • exit " + exit)
            }
        } catch (t: Throwable) {
            vm.state = VmState.ERROR
            store.save(store.list().map { if (it.id == id) vm else it })
            updateNotification("VM " + vm.name + ": " + (t.message ?: "start error"))
        }
    }

    private fun startQemu(
        paths: RuntimePaths,
        vm: com.assavendo.machinevirtuel.vm.VmConfig,
        dir: File,
        qmp: File,
        serial: File,
        log: File,
    ): Process {
        val command = QemuCommandBuilder.build(paths, vm, qmp, serial, log)
        val env = mapOf(
            "LD_LIBRARY_PATH" to paths.libDir.absolutePath,
            "QEMU_AUDIO_DRV" to "none",
        )
        val process = RootProcessLauncher.start(command, dir, env, preferRoot = true)
        attachLogs(process, log)
        return process
    }

    private fun startCrosvm(
        paths: RuntimePaths,
        vm: com.assavendo.machinevirtuel.vm.VmConfig,
        dir: File,
        log: File,
    ): Handle {
        val command = CrosvmCommandBuilder.build(paths, vm)
        val process = RootProcessLauncher.start(
            command, dir, mapOf("LD_LIBRARY_PATH" to paths.libDir.absolutePath), preferRoot = true
        )
        attachLogs(process, log)
        return Handle(process, vm.backend, FilePaths.qmp(vm.id), tapOwned)
    }

    private fun attachLogs(process: Process, log: File) {
        Thread { process.inputStream.use { input -> log.outputStream(append = true).buffered().use { output -> input.copyTo(output) } } }.start()
        Thread { process.errorStream.use { input -> log.outputStream(append = true).buffered().use { output -> input.copyTo(output) } } }.start()
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
        if (QmpController.systemPowerdown(handle.qmp)) {
            if (!handle.process.waitFor(10, TimeUnit.SECONDS)) handle.process.destroy()
        } else {
            handle.process.destroy()
        }
        processes.remove(id)
        if (handle.tapOwned) {
            VmStore(this).list().firstOrNull { it.id == id }?.tapName?.let {
                TapNetworkManager().removeInterface(it)
            }
        }
        VmStore(this).update(id) { it.state = VmState.OFF }
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
        processes.values.toList().forEach { it.process.destroy() }
        processes.clear()
        executor.shutdownNow()
        super.onDestroy()
    }

    private data class Handle(
        val process: Process,
        val backend: BackendKind,
        val qmp: File,
        val tapOwned: Boolean,
    )
}
