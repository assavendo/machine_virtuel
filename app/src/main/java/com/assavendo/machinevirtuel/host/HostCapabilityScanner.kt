package com.assavendo.machinevirtuel.host

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import java.io.File

class HostCapabilityScanner(private val context: Context) {
    fun scan(): HostCapabilities {
        val pm = context.packageManager
        val runtime = Runtime.getRuntime()
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
        val vulkan = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
        val glVersion = activity.deviceConfigurationInfo?.reqGlEsVersion ?: 0
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            runCatching { power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE }
                .getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }
        val data = context.filesDir
        val stat = android.os.StatFs(data.absolutePath)
        val freeMb = stat.availableBytes / (1024L * 1024L)
        val selinux = runCatching {
            File("/sys/fs/selinux/enforce").readText().trim() == "1"
        }.getOrDefault(true)

        // AVF's VirtualMachine APIs are system APIs with a restricted permission.
        // API presence alone does not grant an ordinary installed app permission to create pVMs.
        val avfApiHint = Build.VERSION.SDK_INT >= 34

        return HostCapabilities(
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            cpuCount = runtime.availableProcessors(),
            maxMemoryMb = runtime.maxMemory() / (1024L * 1024L),
            totalMemoryMb = memoryInfo.totalMem / (1024L * 1024L),
            availableMemoryMb = memoryInfo.availMem / (1024L * 1024L),
            vulkan = vulkan,
            vulkanLevel = if (vulkan) 1 else 0,
            openGlEs = glVersion > 0,
            kvm = accessibleDevice("/dev/kvm"),
            gunyah = accessibleDevice("/dev/gunyah"),
            genieZone = accessibleDevice("/dev/gzvm"),
            halla = accessibleDevice("/dev/halla"),
            avfHint = avfApiHint,
            avfAvailable = false,
            rootedHint = listOf(
                "/system/bin/su", "/system/xbin/su", "/sbin/su",
                "/vendor/bin/su", "/debug_ramdisk/su"
            ).any { File(it).canExecute() },
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

    private fun accessibleDevice(path: String): Boolean {
        val device = File(path)
        return device.exists() && device.canRead() && device.canWrite()
    }
}
