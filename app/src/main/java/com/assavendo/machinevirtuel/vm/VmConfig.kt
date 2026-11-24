package com.assavendo.machinevirtuel.vm

import java.util.UUID

enum class DiskFormat { RAW, QCOW2, VHDX, VDI, VMDK }
enum class GpuBackend { AUTO, SOFTWARE, VIRGL, GFXSTREAM }
enum class NetworkMode { DISABLED, NAT, TAP }

data class PortForward(
    val protocol: String = "tcp",
    val hostPort: Int,
    val guestPort: Int,
)

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
    var sharedDirReadOnly: Boolean = false,
    var firmwarePath: String = "",
    var backend: BackendKind = BackendKind.QEMU_TCG,
    var profile: PerformanceProfile = PerformanceProfile.AUTO,
    var gpuBackend: GpuBackend = GpuBackend.AUTO,
    var displayWidth: Int = 1280,
    var displayHeight: Int = 720,
    var audioEnabled: Boolean = true,
    var balloonEnabled: Boolean = false,
    var memoryPreallocate: Boolean = false,
    var ioThreads: Boolean = true,
    var networkMode: NetworkMode = NetworkMode.NAT,
    var tapName: String = "",
    var portForwards: List<PortForward> = emptyList(),
    var state: VmState = VmState.OFF,
    var createdAt: Long = System.currentTimeMillis(),
    var lastStartedAt: Long? = null,
)
