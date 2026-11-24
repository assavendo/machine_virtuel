package com.assavendo.machinevirtuel.host

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

class HostCapabilityScanner(private val context: Context) {
    fun scan(): HostCapabilities {
        val pm = context.packageManager
        val runtime = Runtime.getRuntime()
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
        val vulkan = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
        val openGl = pm.hasSystemFeature(PackageManager.FEATURE_OPENGLES_EXTENSION_PACK)
        val thermal = if (Build.VERSION.SDK_INT >= 29) {
            context.getSystemService(android.os.ThermalManager::class.java)?.currentThermalStatus ?: 0
        } else 0
        val data = context.filesDir
        val stat = android.os.StatFs(data.absolutePath)
        val freeMb = stat.availableBytes / (1024L * 1024L)
        val selinux = runCatching { File("/sys/fs/selinux/enforce").readText().trim() == "1" }.getOrDefault(true)
        val avf = Build.VERSION.SDK_INT >= 31 && runCatching {
            Class.forName("android.system.virtualmachine.VirtualMachine")
            true
        }.getOrDefault(false)
        return HostCapabilities(
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            cpuCount = runtime.availableProcessors(),
            maxMemoryMb = runtime.maxMemory() / (1024L * 1024L),
            totalMemoryMb = memoryInfo.totalMem / (1024L * 1024L),
            availableMemoryMb = memoryInfo.availMem / (1024L * 1024L),
            vulkan = vulkan,
            vulkanLevel = if (vulkan) 1 else 0,
            openGlEs = openGl,
            kvm = File("/dev/kvm").canRead() && File("/dev/kvm").canWrite(),
            gunyah = File("/dev/gunyah").exists(),
            genieZone = File("/dev/gzvm").exists(),
            halla = File("/dev/halla").exists(),
            avfHint = Build.VERSION.SDK_INT >= 31,
            avfAvailable = avf,
            rootedHint = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su").any { File(it).exists() },
            selinuxEnforcing = selinux,
            kernelVersion = runCatching { File("/proc/version").readText().trim() }.getOrDefault("unknown"),
            apiLevel = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            soc = Build.HARDWARE,
            usbHost = pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST),
            externalDisplayHint = Build.VERSION.SDK_INT >= 26,
            storageFreeMb = freeMb,
            thermalStatus = thermal,
        )
    }
}
