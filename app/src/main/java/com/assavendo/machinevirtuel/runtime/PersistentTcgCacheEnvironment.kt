package com.assavendo.machinevirtuel.runtime

import com.assavendo.machinevirtuel.vm.BackendKind
import com.assavendo.machinevirtuel.vm.GuestArch
import com.assavendo.machinevirtuel.vm.VmConfig
import java.io.File
import java.security.MessageDigest

/**
 * Produces the opt-in environment for the patched QEMU 11.1.3 TCG adapter.
 *
 * The adapter is intentionally disabled unless both the patched-runtime marker and
 * the native ARM64 bridge library are present. Stock DroidVM runtimes therefore
 * continue to run unchanged.
 */
internal object PersistentTcgCacheEnvironment {
    fun build(
        paths: RuntimePaths,
        vm: VmConfig,
        apiLevel: Int,
        supportedAbis: List<String>,
        cpuInfo: String,
        trace: Boolean = false,
    ): Map<String, String> {
        if (vm.backend != BackendKind.QEMU_TCG) return emptyMap()
        if (vm.translationCacheLevel !in 1..3) return emptyMap()

        val library = paths.tcgCacheLibrary(vm.arch)
        val marker = paths.tcgCacheMarker(vm.arch)
        if (!library.isFile || !marker.isFile) return emptyMap()

        val hostFeatures = commonCpuFeatures(cpuInfo)
        if (hostFeatures.isEmpty()) return emptyMap()

        val appData = paths.root.parentFile ?: paths.root
        val cacheRoot = File(
            File(appData, "translation-cache"),
            stableDigest(vm.id).take(32),
        )
        if (!cacheRoot.exists() && !cacheRoot.mkdirs()) return emptyMap()

        val quotas = when (vm.translationCacheLevel) {
            1 -> 256 to (32L * 1024 * 1024)
            2 -> 512 to (64L * 1024 * 1024)
            else -> 2048 to (128L * 1024 * 1024)
        }
        val guestArch = when (vm.arch) {
            GuestArch.ARM64 -> "aarch64"
            GuestArch.X86_64 -> "x86_64"
            GuestArch.X86_32 -> "i386"
        }
        val abi = supportedAbis.firstOrNull().orEmpty()
        if (abi.isBlank()) return emptyMap()

        return mapOf(
            "PHOENIXVM_TCG_CACHE_LIBRARY" to library.absolutePath,
            "PHOENIXVM_TCG_CACHE_DIR" to cacheRoot.absolutePath,
            "PHOENIXVM_TCG_CACHE_VM_ID" to vm.id,
            "PHOENIXVM_TCG_CACHE_MAX_ENTRIES" to quotas.first.toString(),
            "PHOENIXVM_TCG_CACHE_MAX_BYTES" to quotas.second.toString(),
            "PHOENIXVM_GUEST_ARCH" to guestArch,
            "PHOENIXVM_GUEST_CPU_MODEL" to "max",
            "PHOENIXVM_GUEST_BUILD_ID" to guestBuildId(vm),
            "PHOENIXVM_TCG_HOST_ABI" to "android-bionic-api$apiLevel-$abi",
            "PHOENIXVM_TCG_HOST_FEATURES" to hostFeatures.joinToString(","),
            "PHOENIXVM_TCG_CACHE_TRACE" to if (trace) "1" else "0",
        )
    }

    internal fun commonCpuFeatures(cpuInfo: String): List<String> {
        val perCpuFeatures = cpuInfo.lineSequence().mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) {
                null
            } else {
                val key = line.substring(0, separator).trim()
                if (key != "Features" && key != "flags") {
                    null
                } else {
                    line.substring(separator + 1)
                        .trim()
                        .split(Regex("\\s+"))
                        .filter { it.matches(Regex("[A-Za-z0-9_.-]+")) }
                        .toSet()
                        .takeIf { it.isNotEmpty() }
                }
            }
        }.toList()

        if (perCpuFeatures.isEmpty()) return emptyList()
        return perCpuFeatures.drop(1).fold(perCpuFeatures.first()) { common, next ->
            common.intersect(next)
        }.sorted()
    }

    private fun guestBuildId(vm: VmConfig): String {
        val inputs = buildList {
            add(vm.guestOs)
            add(vm.guestVersion)
            add(vm.arch.name)
            add(vm.machineType)
            add(vm.diskFormat.name)
            listOf(
                vm.diskPath,
                vm.isoPath,
                vm.kernelPath,
                vm.initrdPath,
                vm.dtbPath,
                vm.androidSystemPath,
                vm.androidVendorPath,
                vm.androidUserdataPath,
            ).forEach { path ->
                if (path.isNotBlank()) {
                    val file = File(path)
                    add(file.name)
                    add(file.length().toString())
                    add(file.lastModified().toString())
                } else {
                    add("")
                }
            }
        }
        return "guest-" + stableDigest(inputs.joinToString("\u0000"))
    }

    private fun stableDigest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
