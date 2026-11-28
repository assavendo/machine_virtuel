package com.assavendo.machinevirtuel.runtime

import com.assavendo.machinevirtuel.vm.BackendKind
import com.assavendo.machinevirtuel.vm.GuestArch
import com.assavendo.machinevirtuel.vm.VmConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersistentTcgCacheEnvironmentTest {
    private data class Fixture(val root: File, val paths: RuntimePaths)

    private fun fixture(): Fixture {
        val root = createTempDir(prefix = "tcg-cache-runtime-")
        val base = File(root, "runtime")
        val optional = File(root, "runtime-addons")
        return Fixture(root, RuntimePaths(base, optional))
    }

    @Test
    fun stockRuntimeDoesNotReceiveCacheEnvironment() {
        val f = fixture()
        try {
            val vm = VmConfig(name = "stock", backend = BackendKind.QEMU_TCG)
            assertTrue(
                PersistentTcgCacheEnvironment.build(
                    paths = f.paths,
                    vm = vm,
                    apiLevel = 35,
                    supportedAbis = listOf("arm64-v8a"),
                    cpuInfo = "processor : 0\nFeatures : fp asimd aes",
                ).isEmpty()
            )
        } finally {
            f.root.deleteRecursively()
        }
    }

    @Test
    fun patchedX86RuntimeGetsVmIsolatedValidatedEnvironment() {
        val f = fixture()
        try {
            val vm = VmConfig(
                name = "x86 guest",
                arch = GuestArch.X86_64,
                backend = BackendKind.QEMU_TCG,
                translationCacheLevel = 2,
                guestOs = "Android x86_64",
                guestVersion = "14",
            )
            f.paths.tcgCacheLibrary(vm.arch).apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1))
            }
            f.paths.tcgCacheMarker(vm.arch).apply {
                parentFile?.mkdirs()
                writeText("QEMU 11.1.3 PhoenixVM TCG cache v1")
            }
            val cpuInfo = """
                processor : 0
                Features : fp asimd aes pmull crc32
                processor : 1
                Features : fp asimd aes pmull
            """.trimIndent()

            val env = PersistentTcgCacheEnvironment.build(
                paths = f.paths,
                vm = vm,
                apiLevel = 35,
                supportedAbis = listOf("arm64-v8a"),
                cpuInfo = cpuInfo,
                trace = true,
            )

            assertEquals(f.paths.tcgCacheLibrary(vm.arch).absolutePath, env["PHOENIXVM_TCG_CACHE_LIBRARY"])
            assertEquals(vm.id, env["PHOENIXVM_TCG_CACHE_VM_ID"])
            assertEquals("x86_64", env["PHOENIXVM_GUEST_ARCH"])
            assertEquals("max", env["PHOENIXVM_GUEST_CPU_MODEL"])
            assertEquals("android-bionic-api35-arm64-v8a", env["PHOENIXVM_TCG_HOST_ABI"])
            assertEquals("aes,asimd,fp,pmull", env["PHOENIXVM_TCG_HOST_FEATURES"])
            assertEquals("512", env["PHOENIXVM_TCG_CACHE_MAX_ENTRIES"])
            assertEquals((64L * 1024 * 1024).toString(), env["PHOENIXVM_TCG_CACHE_MAX_BYTES"])
            assertEquals("1", env["PHOENIXVM_TCG_CACHE_TRACE"])
            assertTrue(env["PHOENIXVM_GUEST_BUILD_ID"].orEmpty().startsWith("guest-"))
            assertTrue(env["PHOENIXVM_TCG_CACHE_DIR"].orEmpty().contains("translation-cache"))
        } finally {
            f.root.deleteRecursively()
        }
    }

    @Test
    fun kvmAndDisabledCacheLevelsNeverEnablePersistentTcgCache() {
        val f = fixture()
        try {
            val vm = VmConfig(
                name = "disabled",
                arch = GuestArch.ARM64,
                backend = BackendKind.KVM,
                translationCacheLevel = 3,
            )
            f.paths.tcgCacheLibrary(vm.arch).apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1))
            }
            f.paths.tcgCacheMarker(vm.arch).apply {
                parentFile?.mkdirs()
                writeText("marker")
            }
            val cpuInfo = "processor : 0\nFeatures : fp asimd aes"
            assertTrue(
                PersistentTcgCacheEnvironment.build(
                    f.paths, vm, 35, listOf("arm64-v8a"), cpuInfo,
                ).isEmpty()
            )

            vm.backend = BackendKind.QEMU_TCG
            vm.translationCacheLevel = 0
            assertTrue(
                PersistentTcgCacheEnvironment.build(
                    f.paths, vm, 35, listOf("arm64-v8a"), cpuInfo,
                ).isEmpty()
            )
        } finally {
            f.root.deleteRecursively()
        }
    }

    @Test
    fun missingOrNonCommonCpuFeaturesKeepCacheDisabled() {
        val f = fixture()
        try {
            val vm = VmConfig(name = "features", backend = BackendKind.QEMU_TCG)
            f.paths.tcgCacheLibrary(vm.arch).apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1))
            }
            f.paths.tcgCacheMarker(vm.arch).apply {
                parentFile?.mkdirs()
                writeText("marker")
            }
            assertFalse(
                PersistentTcgCacheEnvironment.build(
                    f.paths, vm, 35, listOf("arm64-v8a"), "Processor : 0",
                ).containsKey("PHOENIXVM_TCG_HOST_FEATURES")
            )
            assertTrue(
                PersistentTcgCacheEnvironment.commonCpuFeatures(
                    "processor : 0\nFeatures : aes asimd\nprocessor : 1\nFeatures : crc32 fp"
                ).isEmpty()
            )
        } finally {
            f.root.deleteRecursively()
        }
    }
}
