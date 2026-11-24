package com.assavendo.machinevirtuel.vm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class VmStore(context: Context) {
    private val prefs = context.getSharedPreferences("phoenixvm_vms", Context.MODE_PRIVATE)

    fun list(): MutableList<VmConfig> {
        val result = mutableListOf<VmConfig>()
        val array = JSONArray(prefs.getString("vms", "[]") ?: "[]")
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val forwards = mutableListOf<PortForward>()
            val pf = o.optJSONArray("portForwards")
            if (pf != null) {
                for (j in 0 until pf.length()) {
                    val item = pf.getJSONObject(j)
                    forwards += PortForward(
                        protocol = item.optString("protocol", "tcp"),
                        hostPort = item.optInt("hostPort"),
                        guestPort = item.optInt("guestPort"),
                    )
                }
            }
            result += VmConfig(
                id = o.getString("id"),
                name = o.getString("name"),
                description = o.optString("description"),
                guestOs = o.optString("guestOs", "Linux ARM64"),
                arch = GuestArch.valueOf(o.optString("arch", GuestArch.ARM64.name)),
                ramMb = o.optInt("ramMb", 2048),
                vCpu = o.optInt("vCpu", 2),
                diskPath = o.optString("diskPath"),
                diskFormat = DiskFormat.valueOf(o.optString("diskFormat", DiskFormat.RAW.name)),
                isoPath = o.optString("isoPath"),
                sharedDirPath = o.optString("sharedDirPath"),
                sharedDirTag = o.optString("sharedDirTag", "shared"),
                sharedDirReadOnly = o.optBoolean("sharedDirReadOnly", false),
                firmwarePath = o.optString("firmwarePath"),
                backend = BackendKind.valueOf(o.optString("backend", BackendKind.QEMU_TCG.name)),
                profile = PerformanceProfile.valueOf(o.optString("profile", PerformanceProfile.AUTO.name)),
                gpuBackend = GpuBackend.valueOf(o.optString("gpuBackend", GpuBackend.AUTO.name)),
                displayWidth = o.optInt("displayWidth", 1280),
                displayHeight = o.optInt("displayHeight", 720),
                audioEnabled = o.optBoolean("audioEnabled", true),
                balloonEnabled = o.optBoolean("balloonEnabled", false),
                memoryPreallocate = o.optBoolean("memoryPreallocate", false),
                ioThreads = o.optBoolean("ioThreads", true),
                networkMode = NetworkMode.valueOf(o.optString("networkMode", NetworkMode.NAT.name)),
                tapName = o.optString("tapName"),
                portForwards = forwards,
                state = VmState.valueOf(o.optString("state", VmState.OFF.name)),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                lastStartedAt = if (o.has("lastStartedAt")) o.optLong("lastStartedAt") else null,
            )
        }
        return result
    }

    fun save(items: List<VmConfig>) {
        val array = JSONArray()
        items.forEach { vm ->
            val pf = JSONArray()
            vm.portForwards.forEach {
                pf.put(JSONObject().apply {
                    put("protocol", it.protocol)
                    put("hostPort", it.hostPort)
                    put("guestPort", it.guestPort)
                })
            }
            array.put(JSONObject().apply {
                put("id", vm.id)
                put("name", vm.name)
                put("description", vm.description)
                put("guestOs", vm.guestOs)
                put("arch", vm.arch.name)
                put("ramMb", vm.ramMb)
                put("vCpu", vm.vCpu)
                put("diskPath", vm.diskPath)
                put("diskFormat", vm.diskFormat.name)
                put("isoPath", vm.isoPath)
                put("sharedDirPath", vm.sharedDirPath)
                put("sharedDirTag", vm.sharedDirTag)
                put("sharedDirReadOnly", vm.sharedDirReadOnly)
                put("firmwarePath", vm.firmwarePath)
                put("backend", vm.backend.name)
                put("profile", vm.profile.name)
                put("gpuBackend", vm.gpuBackend.name)
                put("displayWidth", vm.displayWidth)
                put("displayHeight", vm.displayHeight)
                put("audioEnabled", vm.audioEnabled)
                put("balloonEnabled", vm.balloonEnabled)
                put("memoryPreallocate", vm.memoryPreallocate)
                put("ioThreads", vm.ioThreads)
                put("networkMode", vm.networkMode.name)
                put("tapName", vm.tapName)
                put("portForwards", pf)
                put("state", vm.state.name)
                put("createdAt", vm.createdAt)
                vm.lastStartedAt?.let { put("lastStartedAt", it) }
            })
        }
        prefs.edit().putString("vms", array.toString()).apply()
    }

    fun update(id: String, block: (VmConfig) -> Unit): VmConfig? {
        val items = list()
        val vm = items.firstOrNull { it.id == id } ?: return null
        block(vm)
        save(items)
        return vm
    }
}
