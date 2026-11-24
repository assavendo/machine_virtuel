package com.assavendo.machinevirtuel.vm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class VmStore(context: Context) {
    private val prefs = context.getSharedPreferences("phoenixvm_vms", Context.MODE_PRIVATE)
    private val schemaVersion = 3

    fun list(): MutableList<VmConfig> {
        val result = mutableListOf<VmConfig>()
        val array = JSONArray(prefs.getString("vms", "[]") ?: "[]")
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            result += decode(o)
        }
        return result
    }

    fun save(items: List<VmConfig>) {
        val array = JSONArray()
        items.forEach { array.put(encode(it)) }
        prefs.edit().putInt("schemaVersion", schemaVersion).putString("vms", array.toString()).apply()
    }

    fun update(id: String, block: (VmConfig) -> Unit): VmConfig? {
        val items = list()
        val vm = items.firstOrNull { it.id == id } ?: return null
        block(vm)
        save(items)
        return vm
    }

    private fun decode(o: JSONObject): VmConfig {
        val forwards = mutableListOf<PortForward>()
        o.optJSONArray("portForwards")?.let { pf ->
            for (j in 0 until pf.length()) {
                val item = pf.optJSONObject(j) ?: continue
                forwards += PortForward(
                    protocol = item.optString("protocol", "tcp"),
                    hostPort = item.optInt("hostPort"),
                    guestPort = item.optInt("guestPort"),
                )
            }
        }
        val bootOrder = mutableListOf<String>()
        o.optJSONArray("bootOrder")?.let { array ->
            for (j in 0 until array.length()) bootOrder += array.optString(j)
        }
        return VmConfig(
            id = o.optString("id", java.util.UUID.randomUUID().toString()),
            name = o.optString("name", "VM"),
            description = o.optString("description"),
            guestOs = o.optString("guestOs", "Linux ARM64"),
            guestVersion = o.optString("guestVersion"),
            arch = enumOr(GuestArch.ARM64) { GuestArch.valueOf(o.optString("arch", GuestArch.ARM64.name)) },
            machineType = o.optString("machineType", "virt"),
            ramMb = o.optInt("ramMb", 2048),
            vCpu = o.optInt("vCpu", 2),
            diskPath = o.optString("diskPath"),
            diskFormat = enumOr(DiskFormat.RAW) { DiskFormat.valueOf(o.optString("diskFormat", DiskFormat.RAW.name)) },
            diskSizeGiB = o.optLong("diskSizeGiB", 16L),
            isoPath = o.optString("isoPath"),
            firmwarePath = o.optString("firmwarePath"),
            firmwareKind = o.optString("firmwareKind", "UEFI"),
            secureBoot = o.optBoolean("secureBoot", false),
            tpmEnabled = o.optBoolean("tpmEnabled", false),
            tpmStatePath = o.optString("tpmStatePath"),
            sharedDirPath = o.optString("sharedDirPath"),
            sharedDirTag = o.optString("sharedDirTag", "shared"),
            sharedDirReadOnly = o.optBoolean("sharedDirReadOnly", false),
            backend = enumOr(BackendKind.QEMU_TCG) { BackendKind.valueOf(o.optString("backend", BackendKind.QEMU_TCG.name)) },
            mode = enumOr(VmMode.AUTO) { VmMode.valueOf(o.optString("mode", VmMode.AUTO.name)) },
            performancePreset = enumOr(PerformancePreset.BALANCED) { PerformancePreset.valueOf(o.optString("performancePreset", PerformancePreset.BALANCED.name)) },
            profile = enumOr(PerformanceProfile.AUTO) { PerformanceProfile.valueOf(o.optString("profile", PerformanceProfile.AUTO.name)) },
            gpuBackend = enumOr(GpuBackend.AUTO) { GpuBackend.valueOf(o.optString("gpuBackend", GpuBackend.AUTO.name)) },
            displayWidth = o.optInt("displayWidth", 1280),
            displayHeight = o.optInt("displayHeight", 720),
            dpi = o.optInt("dpi", 160),
            fullscreen = o.optBoolean("fullscreen", false),
            audioEnabled = o.optBoolean("audioEnabled", true),
            audioFrequencyHz = o.optInt("audioFrequencyHz", 48000),
            audioChannels = o.optInt("audioChannels", 2),
            audioBufferMs = o.optInt("audioBufferMs", 20),
            balloonEnabled = o.optBoolean("balloonEnabled", false),
            memoryPreallocate = o.optBoolean("memoryPreallocate", false),
            ioThreads = o.optBoolean("ioThreads", true),
            networkMode = enumOr(NetworkMode.NAT) { NetworkMode.valueOf(o.optString("networkMode", NetworkMode.NAT.name)) },
            tapName = o.optString("tapName"),
            portForwards = forwards,
            clipboardEnabled = o.optBoolean("clipboardEnabled", false),
            touchEnabled = o.optBoolean("touchEnabled", true),
            mouseEnabled = o.optBoolean("mouseEnabled", true),
            keyboardEnabled = o.optBoolean("keyboardEnabled", true),
            gamepadEnabled = o.optBoolean("gamepadEnabled", false),
            sharedFilesExplicitOnly = o.optBoolean("sharedFilesExplicitOnly", true),
            bootOrder = if (bootOrder.isEmpty()) listOf("disk", "cdrom", "network") else bootOrder,
            autoSnapshotOnInstall = o.optBoolean("autoSnapshotOnInstall", true),
            autoSnapshotIntervalMin = o.optInt("autoSnapshotIntervalMin", 0),
            maxSnapshots = o.optInt("maxSnapshots", 8),
            securityMode = o.optString("securityMode", "STANDARD"),
            vmPriority = o.optInt("vmPriority", 50),
            monitoringIntervalMs = o.optLong("monitoringIntervalMs", 1000L),
            translationCacheLevel = o.optInt("translationCacheLevel", 2),
            state = enumOr(VmState.OFF) { VmState.valueOf(o.optString("state", VmState.OFF.name)) },
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            lastStartedAt = if (o.has("lastStartedAt")) o.optLong("lastStartedAt") else null,
        )
    }

    private fun encode(vm: VmConfig): JSONObject = JSONObject().apply {
        put("id", vm.id); put("name", vm.name); put("description", vm.description)
        put("guestOs", vm.guestOs); put("guestVersion", vm.guestVersion); put("arch", vm.arch.name)
        put("machineType", vm.machineType); put("ramMb", vm.ramMb); put("vCpu", vm.vCpu)
        put("diskPath", vm.diskPath); put("diskFormat", vm.diskFormat.name); put("diskSizeGiB", vm.diskSizeGiB)
        put("isoPath", vm.isoPath); put("firmwarePath", vm.firmwarePath); put("firmwareKind", vm.firmwareKind)
        put("secureBoot", vm.secureBoot); put("tpmEnabled", vm.tpmEnabled); put("tpmStatePath", vm.tpmStatePath)
        put("sharedDirPath", vm.sharedDirPath); put("sharedDirTag", vm.sharedDirTag); put("sharedDirReadOnly", vm.sharedDirReadOnly)
        put("backend", vm.backend.name); put("mode", vm.mode.name); put("performancePreset", vm.performancePreset.name)
        put("profile", vm.profile.name); put("gpuBackend", vm.gpuBackend.name)
        put("displayWidth", vm.displayWidth); put("displayHeight", vm.displayHeight); put("dpi", vm.dpi); put("fullscreen", vm.fullscreen)
        put("audioEnabled", vm.audioEnabled); put("audioFrequencyHz", vm.audioFrequencyHz); put("audioChannels", vm.audioChannels); put("audioBufferMs", vm.audioBufferMs)
        put("balloonEnabled", vm.balloonEnabled); put("memoryPreallocate", vm.memoryPreallocate); put("ioThreads", vm.ioThreads)
        put("networkMode", vm.networkMode.name); put("tapName", vm.tapName)
        val pf = JSONArray()
        vm.portForwards.forEach { pf.put(JSONObject().apply { put("protocol", it.protocol); put("hostPort", it.hostPort); put("guestPort", it.guestPort) }) }
        put("portForwards", pf)
        put("clipboardEnabled", vm.clipboardEnabled); put("touchEnabled", vm.touchEnabled); put("mouseEnabled", vm.mouseEnabled)
        put("keyboardEnabled", vm.keyboardEnabled); put("gamepadEnabled", vm.gamepadEnabled); put("sharedFilesExplicitOnly", vm.sharedFilesExplicitOnly)
        put("bootOrder", JSONArray(vm.bootOrder)); put("autoSnapshotOnInstall", vm.autoSnapshotOnInstall)
        put("autoSnapshotIntervalMin", vm.autoSnapshotIntervalMin); put("maxSnapshots", vm.maxSnapshots)
        put("securityMode", vm.securityMode); put("vmPriority", vm.vmPriority)
        put("monitoringIntervalMs", vm.monitoringIntervalMs); put("translationCacheLevel", vm.translationCacheLevel)
        put("state", vm.state.name); put("createdAt", vm.createdAt); vm.lastStartedAt?.let { put("lastStartedAt", it) }
    }

    private fun <T> enumOr(default: T, supplier: () -> T): T = runCatching { supplier() }.getOrDefault(default)
}
