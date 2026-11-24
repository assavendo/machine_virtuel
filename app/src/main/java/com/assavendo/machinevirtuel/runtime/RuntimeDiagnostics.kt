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
        file("qemu-img", paths.qemuImg)
        file("crosvm", paths.crosvm)
        file("UEFI code", paths.edk2Qemu)
        file("UEFI NVRAM template", paths.edk2QemuVars)

        val virgl = File(paths.libDir, "libvirglrenderer.so")
        val gfxstream = File(paths.libDir, "libgfxstream_backend.so")
        file("VirGL renderer", virgl)
        file("Gfxstream backend", gfxstream)

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
