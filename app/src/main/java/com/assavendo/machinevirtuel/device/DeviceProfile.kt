package com.assavendo.machinevirtuel.device

enum class DeviceFamily { QUALCOMM_SNAPDRAGON, MEDIATEK_DIMENSITY, SAMSUNG_EXYNOS, GOOGLE_TENSOR, OTHER_ARM64 }

enum class ValidationLevel { TESTED, THEORETICAL, UNKNOWN }

data class DeviceProfile(
    val id: String,
    val family: DeviceFamily,
    val vendor: String,
    val modelPattern: String,
    val socPattern: String,
    val arm64: Boolean,
    val recommendedBackend: String,
    val recommendedProfile: String,
    val validation: ValidationLevel,
    val limitations: List<String> = emptyList(),
)

object DeviceProfileDatabase {
    private val profiles = listOf(
        DeviceProfile("qualcomm-generic", DeviceFamily.QUALCOMM_SNAPDRAGON, "Qualcomm", "Snapdragon*", "SM8*", true, "KVM/Gunyah when exposed", "Performance", ValidationLevel.THEORETICAL),
        DeviceProfile("qualcomm-8gen", DeviceFamily.QUALCOMM_SNAPDRAGON, "Qualcomm", "Snapdragon 8 Gen*", "SM8*", true, "KVM/Gunyah", "Gaming", ValidationLevel.THEORETICAL, listOf("Vendor hypervisor exposure varies by Android build.")),
        DeviceProfile("mediatek-dimensity", DeviceFamily.MEDIATEK_DIMENSITY, "MediaTek", "Dimensity*", "MT*", true, "KVM/GenieZone when exposed", "Balanced", ValidationLevel.THEORETICAL),
        DeviceProfile("samsung-exynos", DeviceFamily.SAMSUNG_EXYNOS, "Samsung", "Exynos*", "exynos*", true, "KVM/Halla when exposed", "Compatibility", ValidationLevel.THEORETICAL),
        DeviceProfile("google-tensor", DeviceFamily.GOOGLE_TENSOR, "Google", "Pixel*", "Tensor*", true, "KVM/AVF when exposed", "Development", ValidationLevel.THEORETICAL),
        DeviceProfile("arm64-generic", DeviceFamily.OTHER_ARM64, "Generic", "*", "*", true, "AUTO", "Balanced", ValidationLevel.UNKNOWN),
    )

    fun all(): List<DeviceProfile> = profiles

    fun match(manufacturer: String, model: String, soc: String): DeviceProfile {
        val normalized = listOf(manufacturer, model, soc).joinToString(" ").lowercase()
        return profiles.firstOrNull { profile ->
            wildcard(profile.vendor, normalized) ||
                wildcard(profile.modelPattern, model.lowercase()) ||
                wildcard(profile.socPattern, soc.lowercase())
        } ?: profiles.last()
    }

    private fun wildcard(pattern: String, value: String): Boolean {
        val regex = Regex("^" + Regex.escape(pattern.lowercase()).replace("\\*", ".*") + "$")
        return regex.matches(value)
    }
}
