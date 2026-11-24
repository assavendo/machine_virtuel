package com.assavendo.machinevirtuel.runtime

import android.os.Build
import java.io.File

data class RuntimeDiagnostic(
    val component: String,
    val ok: Boolean,
    val detail: String,
)

object RuntimeDiagnostics {
    fun run(context: android.content.Context): List<RuntimeDiagnostic> {
        val paths = RuntimePaths(context)
        val results = mutableListOf<RuntimeDiagnostic>()

        fun file(name: String, value: File) {
            results += RuntimeDiagnostic(
                component = name,
                ok = value.isFile && value.canRead(),
                detail = value.absolutePath,
            )
        }

        file("QEMU ARM64", paths.qemu)
        file("QEMU x86_64", paths.qemuX86)
        file("QEMU i386", paths.qemuI386)
        file("qemu-img", paths.qemuImg)
        file("crosvm", paths.crosvm)
        file("UEFI EDK2 ARM64 code", paths.edk2Qemu)
        file("UEFI EDK2 ARM64 NVRAM template", paths.edk2QemuVars)
        file("UEFI OVMF x86_64 code (optional)", paths.edk2X86Code)
        file("UEFI OVMF x86_64 vars (optional)", paths.edk2X86Vars)

        val virgl = File(paths.libDir, "libvirglrenderer.so")
        val gfxstream = File(paths.libDir, "libgfxstream_backend.so")
        results += RuntimeDiagnostic(
            component = "x86 runtime library directory",
            ok = paths.x86LibDir.isDirectory,
            detail = paths.x86LibDir.absolutePath,
        )
        results += RuntimeDiagnostic(
            component = "x86 QEMU data directory",
            ok = paths.qemuShareX86.isDirectory,
            detail = paths.qemuShareX86.absolutePath,
        )
        file("VirGL renderer", virgl)
        file("Gfxstream backend", gfxstream)

        results += RuntimeDiagnostic(
            component = "x86_64 system runtime",
            ok = paths.hasX86SystemQemu() && paths.hasX86Uefi(),
            detail = if (paths.hasX86SystemQemu() && paths.hasX86Uefi()) {
                "QEMU x86 et OVMF x86_64 présents."
            } else {
                "QEMU x86 et/ou OVMF x86_64 absent(s); Windows/Linux x86_64 ne sera pas lancé avec le firmware ARM64."
            },
        )

        val marker = paths.marker()
        results += RuntimeDiagnostic(
            component = "Runtime revision",
            ok = marker.isFile && marker.readText().isNotBlank(),
            detail = if (marker.isFile) marker.readText() else "missing",
        )
        results += RuntimeDiagnostic(
            component = "Host ABI",
            ok = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" },
            detail = Build.SUPPORTED_ABIS.joinToString(),
        )
        return results
    }
}
