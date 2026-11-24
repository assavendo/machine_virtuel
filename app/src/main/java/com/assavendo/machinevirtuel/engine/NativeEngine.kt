package com.assavendo.machinevirtuel.engine
object NativeEngine {
    private var loaded=false
    init {
        try { System.loadLibrary("phoenixvm_core"); loaded=true }
        catch (_: Throwable) { loaded=false }
    }
    fun isLoaded()=loaded
    fun version(): String = if (loaded) runCatching { nativeVersion() }.getOrElse { "Native core unavailable" } else "Native core not packaged"
    @JvmStatic private external fun nativeVersion(): String
}
