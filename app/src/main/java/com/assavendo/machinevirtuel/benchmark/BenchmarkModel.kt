package com.assavendo.machinevirtuel.benchmark

data class BenchmarkEnvironmentSnapshot(
    val deviceModel: String,
    val manufacturer: String,
    val androidApi: Int,
    val kernel: String,
    val soc: String,
    val backend: String,
    val guestArchitecture: String,
    val ramMb: Int,
    val vcpus: Int,
    val temperatureC: Double?,
)

data class BenchmarkCase(
    val id: String,
    val category: String,
    val name: String,
    val durationHintMinutes: Int,
    val availableWithoutPhysicalGuest: Boolean,
)

object BenchmarkCatalog {
    val cases = listOf(
        BenchmarkCase("cpu-single", "CPU", "Integer/SHA single-thread", 1, true),
        BenchmarkCase("cpu-multi", "CPU", "Multi-vCPU host/guest", 5, false),
        BenchmarkCase("memory-bandwidth", "Memory", "Copy/bandwidth", 2, true),
        BenchmarkCase("memory-latency", "Memory", "Latency/random access", 2, true),
        BenchmarkCase("io-sequential", "I/O", "Sequential read/write", 5, false),
        BenchmarkCase("io-random", "I/O", "Random IOPS", 5, false),
        BenchmarkCase("gpu-2d", "GPU", "2D rendering", 5, false),
        BenchmarkCase("gpu-vulkan", "GPU", "Vulkan/3D", 10, false),
        BenchmarkCase("boot-linux", "System", "Boot/launch/stop", 5, false),
        BenchmarkCase("sustained-30m", "Sustained", "30-minute sustained", 30, false),
        BenchmarkCase("sustained-60m", "Sustained", "60-minute sustained", 60, false),
    )
}
