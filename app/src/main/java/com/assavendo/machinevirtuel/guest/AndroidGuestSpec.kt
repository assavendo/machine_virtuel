package com.assavendo.machinevirtuel.guest

import java.io.File

data class AndroidGuestSpec(
    val systemImage: File,
    val userdataImage: File,
    val vendorImage: File?,
    val kernel: File?,
    val initramfs: File?,
    val bootloader: File?,
    val adbEnabled: Boolean,
    val displayWidth: Int,
    val displayHeight: Int,
)

data class WindowsGuestSpec(
    val installationIso: File,
    val architecture: String,
    val uefiRequired: Boolean,
    val secureBootRequested: Boolean,
    val virtioDriversExpected: Boolean,
    val tpmRequired: Boolean,
)

object GuestSpecValidator {
    fun validateAndroid(spec: AndroidGuestSpec): List<String> = buildList {
        if (!spec.systemImage.isFile) add("Android system image missing.")
        if (!spec.userdataImage.isFile) add("Android userdata image missing.")
        if (spec.displayWidth < 320 || spec.displayHeight < 240) add("Invalid guest display size.")
        if (spec.adbEnabled && !spec.systemImage.canRead()) add("ADB requested but system image is unreadable.")
    }

    fun validateWindows(spec: WindowsGuestSpec): List<String> = buildList {
        if (!spec.installationIso.isFile) add("Windows installation ISO missing.")
        if (!spec.architecture.equals("ARM64", true) && !spec.architecture.equals("x86_64", true)) add("Unsupported Windows guest architecture.")
        if (!spec.uefiRequired) add("Windows path should use UEFI.")
    }
}
