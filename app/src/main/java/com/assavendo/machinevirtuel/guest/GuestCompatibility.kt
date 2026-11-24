package com.assavendo.machinevirtuel.guest

import com.assavendo.machinevirtuel.vm.GuestArch

enum class GuestStatus { SUPPORTED_TARGET, THEORETICAL, UNTESTED }

data class GuestCompatibility(
    val id: String,
    val name: String,
    val architecture: GuestArch,
    val status: GuestStatus,
    val fullSystem: Boolean = true,
    val notes: String = "",
)

object GuestCompatibilityCatalog {
    val entries: List<GuestCompatibility> = listOf(
        GuestCompatibility("linux-arm64", "Linux ARM64", GuestArch.ARM64, GuestStatus.SUPPORTED_TARGET, notes = "Primary target."),
        GuestCompatibility("windows-arm64", "Windows ARM64", GuestArch.ARM64, GuestStatus.THEORETICAL, notes = "Requires valid ARM64 installation media and VirtIO drivers."),
        GuestCompatibility("android-arm64", "Android ARM64", GuestArch.ARM64, GuestStatus.THEORETICAL, notes = "Requires guest kernel/system/vendor/userdata and boot configuration."),
        GuestCompatibility("bsd-arm64", "BSD ARM64", GuestArch.ARM64, GuestStatus.THEORETICAL),
        GuestCompatibility("linux-x86_64", "Linux x86_64", GuestArch.X86_64, GuestStatus.THEORETICAL, notes = "Requires qemu-system-x86_64 runtime."),
        GuestCompatibility("windows-x86_64", "Windows x86/x64", GuestArch.X86_64, GuestStatus.THEORETICAL, notes = "Requires x86 QEMU runtime, UEFI and VirtIO-capable guest drivers."),
        GuestCompatibility("legacy-x86", "DOS / legacy x86", GuestArch.X86_32, GuestStatus.THEORETICAL, notes = "Requires qemu-system-i386 runtime."),
        GuestCompatibility("custom-arm64", "Custom ARM64", GuestArch.ARM64, GuestStatus.UNTESTED),
    )

    fun forArch(arch: GuestArch): List<GuestCompatibility> = entries.filter { it.architecture == arch }
}
