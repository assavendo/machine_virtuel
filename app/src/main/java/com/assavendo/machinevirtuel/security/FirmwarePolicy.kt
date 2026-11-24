package com.assavendo.machinevirtuel.security

import com.assavendo.machinevirtuel.runtime.FirmwareInfo
import java.io.File

data class FirmwarePolicy(
    val allowExternalFirmware: Boolean = false,
    val requireKnownSource: Boolean = true,
    val requireHash: Boolean = true,
    val secureBootRequested: Boolean = false,
)

object FirmwareValidator {
    fun validate(info: FirmwareInfo, policy: FirmwarePolicy): List<String> {
        val errors = mutableListOf<String>()
        if (policy.requireKnownSource && info.source.isBlank()) errors += "Firmware source missing."
        if (policy.requireHash && info.sha256.isBlank()) errors += "Firmware hash missing."
        if (!info.code.isFile || !info.vars.isFile) errors += "Firmware/NVRAM files missing."
        if (policy.secureBootRequested &&
            !info.architecture.equals("aarch64", ignoreCase = true) &&
            !info.architecture.equals("x86_64", ignoreCase = true)
        ) {
            errors += "Secure Boot is unsupported for this firmware architecture."
        }
        return errors
    }

    fun isPathAllowed(file: File, trustedRoots: List<File>): Boolean =
        runCatching { SecurityGuards.validateUserPath(file, trustedRoots); true }.getOrDefault(false)
}
