package com.assavendo.machinevirtuel.vm

import java.util.UUID

enum class DiskFormat { RAW, QCOW2, VHDX, VDI, VMDK }

data class VmConfig(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var description: String = "",
    var guestOs: String = "Linux ARM64",
    var arch: GuestArch = GuestArch.ARM64,
    var ramMb: Int = 2048,
    var vCpu: Int = 2,
    var diskPath: String = "",
    var diskFormat: DiskFormat = DiskFormat.RAW,
    var isoPath: String = "",
    var sharedDirPath: String = "",
    var sharedDirTag: String = "shared",
    var firmwarePath: String = "",
    var backend: BackendKind = BackendKind.QEMU_TCG,
    var profile: PerformanceProfile = PerformanceProfile.AUTO,
    var state: VmState = VmState.OFF,
    var createdAt: Long = System.currentTimeMillis(),
    var lastStartedAt: Long? = null,
)
