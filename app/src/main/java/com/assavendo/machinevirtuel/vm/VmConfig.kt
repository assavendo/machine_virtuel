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
    var guestVersion: String = "",
    var arch: GuestArch = GuestArch.ARM64,
    var machineType: String = "virt",
    var ramMb: Int = 2048,
    var vCpu: Int = 2,
    var diskPath: String = "",
    var diskFormat: DiskFormat = DiskFormat.RAW,
    var diskSizeGiB: Long = 16,
    var isoPath: String = "",
    // Optional full-system Android/Linux boot artifacts. Empty means UEFI/ISO boot.
    var kernelPath: String = "",
    var initrdPath: String = "",
    var dtbPath: String = "",
    var kernelCommandLine: String = "",
    var androidSystemPath: String = "",
    var androidVendorPath: String = "",
    var androidUserdataPath: String = "",
    var firmwarePath: String = "",
    var firmwareKind: String = "UEFI",
    var secureBoot: Boolean = false,
    var tpmEnabled: Boolean = false,
    var tpmStatePath: String = "",
    var sharedDirPath: String = "",
    var sharedDirTag: String = "shared",
    var sharedDirReadOnly: Boolean = false,
    var backend: BackendKind = BackendKind.QEMU_TCG,
    var mode: VmMode = VmMode.AUTO,
    var performancePreset: PerformancePreset = PerformancePreset.BALANCED,
    var profile: PerformanceProfile = PerformanceProfile.AUTO,
    var gpuBackend: GpuBackend = GpuBackend.AUTO,
    var displayWidth: Int = 1280,
    var displayHeight: Int = 720,
    var dpi: Int = 160,
    var fullscreen: Boolean = false,
    var audioEnabled: Boolean = true,
    var audioFrequencyHz: Int = 48000,
    var audioChannels: Int = 2,
    var audioBufferMs: Int = 20,
    var balloonEnabled: Boolean = false,
    var memoryPreallocate: Boolean = false,
    var ioThreads: Boolean = true,
    var networkMode: NetworkMode = NetworkMode.NAT,
    var tapName: String = "",
    var portForwards: List<PortForward> = emptyList(),
    var clipboardEnabled: Boolean = false,
    var touchEnabled: Boolean = true,
    var mouseEnabled: Boolean = true,
    var keyboardEnabled: Boolean = true,
    var gamepadEnabled: Boolean = false,
    var sharedFilesExplicitOnly: Boolean = true,
    var bootOrder: List<String> = listOf("disk", "cdrom", "network"),
    var autoSnapshotOnInstall: Boolean = true,
    var autoSnapshotIntervalMin: Int = 0,
    var maxSnapshots: Int = 8,
    var securityMode: String = "STANDARD",
    var vmPriority: Int = 50,
    var monitoringIntervalMs: Long = 1000,
    var translationCacheLevel: Int = 2,
    var createdAt: Long = System.currentTimeMillis(),
    var lastStartedAt: Long? = null,
    var state: VmState = VmState.OFF,
)
