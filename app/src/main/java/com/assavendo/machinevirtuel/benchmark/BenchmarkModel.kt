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

data class BenchmarkComparison(
    val baselineScore: Double,
    val optimizedScore: Double,
    val deltaPercent: Double,
    val memoryDeltaMb: Long = 0,
    val temperatureDeltaC: Double? = null,
    val stabilityPassed: Boolean = true,
    val compatibilityPassed: Boolean = true,
) {
    val accepted: Boolean
        get() = optimizedScore > 0.0 &&
            deltaPercent >= 0.0 &&
            stabilityPassed &&
            compatibilityPassed
}

object BenchmarkAcceptance {
    fun compare(
        baselineScore: Double,
        optimizedScore: Double,
        baselineMemoryMb: Long = 0,
        optimizedMemoryMb: Long = 0,
        baselineTemperatureC: Double? = null,
        optimizedTemperatureC: Double? = null,
        stabilityPassed: Boolean = true,
        compatibilityPassed: Boolean = true,
    ): BenchmarkComparison {
        require(baselineScore > 0.0 && optimizedScore >= 0.0)
        val delta = (optimizedScore - baselineScore) / baselineScore * 100.0
        return BenchmarkComparison(
            baselineScore = baselineScore,
            optimizedScore = optimizedScore,
            deltaPercent = delta,
            memoryDeltaMb = optimizedMemoryMb - baselineMemoryMb,
            temperatureDeltaC = if (baselineTemperatureC != null && optimizedTemperatureC != null)
                optimizedTemperatureC - baselineTemperatureC else null,
            stabilityPassed = stabilityPassed,
            compatibilityPassed = compatibilityPassed,
        )
    }
}

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
