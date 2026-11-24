package com.assavendo.machinevirtuel.vm

enum class VmMode(val label: String) {
    AUTO("Auto"),
    PERFORMANCE("Performance"),
    COMPATIBILITY("Compatibilité"),
    EXPERT("Expert"),
}

enum class PerformancePreset(val label: String) {
    COMPATIBILITY("Compatibility"),
    BALANCED("Balanced"),
    PERFORMANCE("Performance"),
    ULTRA_PERFORMANCE("Ultra Performance"),
    BATTERY_SAVER("Battery Saver"),
    GAMING("Gaming"),
    OFFICE("Office"),
    SERVER("Server"),
    DEVELOPMENT("Development"),
}

data class ResourcePolicy(
    val minHostMemoryReserveMb: Long = 768,
    val maxCpuSharePercent: Int = 100,
    val maxRamPercentOfHost: Int = 70,
    val allowDynamicResize: Boolean = true,
    val allowThermalDegrade: Boolean = true,
)
