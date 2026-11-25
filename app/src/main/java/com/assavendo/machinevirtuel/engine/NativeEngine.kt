package com.assavendo.machinevirtuel.engine

/**
 * Fail-safe JNI boundary for the Rust policy core.
 *
 * The native layer is never allowed to make the application unusable. When the
 * library is absent or a symbol call fails, Kotlin keeps the conservative
 * fallback policy and the UI can report that the native core is unavailable.
 */
object NativeEngine {
    private val loaded: Boolean

    init {
        loaded = runCatching {
            System.loadLibrary("phoenixvm_core")
            true
        }.getOrDefault(false)
    }

    data class NativeBackendDecision(
        val backend: String,
        val nativeAvailable: Boolean,
    )

    fun isLoaded(): Boolean = loaded
    fun isAvailable(): Boolean = loaded && runCatching {
        nativeVersion()
        true
    }.getOrDefault(false)

    fun version(): String {
        if (!loaded) return "Native core not packaged"
        return runCatching { nativeVersion() }
            .getOrElse { "Native core unavailable" }
    }

    fun selectBackend(
        arm64Guest: Boolean,
        kvm: Boolean,
        gunyah: Boolean,
        genieZone: Boolean,
        halla: Boolean,
        avf: Boolean,
        thermalStatus: Int,
    ): NativeBackendDecision {
        if (!loaded) {
            return NativeBackendDecision("QEMU_TCG", false)
        }

        val result = runCatching {
            nativeSelectBackend(
                arm64Guest,
                kvm,
                gunyah,
                genieZone,
                halla,
                avf,
                thermalStatus,
            )
        }

        return result.fold(
            onSuccess = { NativeBackendDecision(it, true) },
            onFailure = { NativeBackendDecision("QEMU_TCG", false) },
        )
    }

    data class NativeTranslationDecision(
        val tier: String,
        val nativeAvailable: Boolean,
    )

    fun translationDecision(executions: Long): NativeTranslationDecision {
        val safeExecutions = executions.coerceAtLeast(0L)
        val fallback = when {
            safeExecutions >= 100_000L -> "VERY_HOT"
            safeExecutions >= 10_000L -> "HOT"
            safeExecutions >= 100L -> "WARM"
            else -> "COLD"
        }
        if (!loaded) return NativeTranslationDecision(fallback, false)

        return runCatching {
            NativeTranslationDecision(nativeTranslationTier(safeExecutions), true)
        }.getOrElse {
            NativeTranslationDecision(fallback, false)
        }
    }

    fun translationTier(executions: Long): String =
        translationDecision(executions).tier

    @JvmStatic
    private external fun nativeVersion(): String

    @JvmStatic
    private external fun nativeSelectBackend(
        arm64Guest: Boolean,
        kvm: Boolean,
        gunyah: Boolean,
        genieZone: Boolean,
        halla: Boolean,
        avf: Boolean,
        thermalStatus: Int,
    ): String

    @JvmStatic
    private external fun nativeTranslationTier(executions: Long): String
}
