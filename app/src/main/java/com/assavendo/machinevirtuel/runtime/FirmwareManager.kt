package com.assavendo.machinevirtuel.runtime

import java.io.File

data class FirmwareInfo(
    val code: File,
    val vars: File,
    val architecture: String,
    val source: String,
    val license: String,
    val sha256: String,
)

object FirmwareManager {
    fun available(paths: RuntimePaths): List<FirmwareInfo> = buildList {
        if (paths.edk2Qemu.isFile && paths.edk2QemuVars.isFile) {
            add(
                FirmwareInfo(
                    paths.edk2Qemu, paths.edk2QemuVars, "aarch64",
                    "DroidVM-Prebuilts / EDK2",
                    "see THIRD_PARTY/DroidVM-Prebuilts.txt",
                    ImageVerifier.sha256(paths.edk2Qemu),
                )
            )
        }
        if (paths.edk2Gunyah.isFile && paths.edk2GunyahVars.isFile) {
            add(
                FirmwareInfo(
                    paths.edk2Gunyah, paths.edk2GunyahVars, "aarch64",
                    "DroidVM-Prebuilts / EDK2 Gunyah",
                    "see THIRD_PARTY/DroidVM-Prebuilts.txt",
                    ImageVerifier.sha256(paths.edk2Gunyah),
                )
            )
        }
        if (paths.edk2X86Code.isFile && paths.edk2X86Vars.isFile) {
            add(
                FirmwareInfo(
                    paths.edk2X86Code, paths.edk2X86Vars, "x86_64",
                    "Debian OVMF package",
                    "BSD-2-Clause / see bundled Debian copyright notice",
                    ImageVerifier.sha256(paths.edk2X86Code),
                )
            )
        }
    }
}
