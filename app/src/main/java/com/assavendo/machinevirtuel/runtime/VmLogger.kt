package com.assavendo.machinevirtuel.runtime

import android.content.Context
import android.util.Log
import java.io.File

enum class LogLevel { ERROR, WARN, INFO, DEBUG, TRACE }

class VmLogger(private val context: Context, private val vmId: String) {
    private val file = File(context.filesDir, "vms/" + vmId + "/runtime.log")

    @Synchronized
    fun log(level: LogLevel, message: String, throwable: Throwable? = null) {
        file.parentFile?.mkdirs()
        val line = buildString {
            append(System.currentTimeMillis())
            append(" [").append(level.name).append("] ")
            append(message)
            throwable?.let { append(" | ").append(it::class.java.simpleName).append(": ").append(it.message) }
            append('\n')
        }
        runCatching { file.appendText(line) }
        when (level) {
            LogLevel.ERROR -> Log.e("PhoenixVM/" + vmId.take(8), message, throwable)
            LogLevel.WARN -> Log.w("PhoenixVM/" + vmId.take(8), message, throwable)
            LogLevel.INFO -> Log.i("PhoenixVM/" + vmId.take(8), message)
            LogLevel.DEBUG, LogLevel.TRACE -> Log.d("PhoenixVM/" + vmId.take(8), message)
        }
    }

    fun error(message: String, t: Throwable? = null) = log(LogLevel.ERROR, message, t)
    fun warn(message: String) = log(LogLevel.WARN, message)
    fun info(message: String) = log(LogLevel.INFO, message)
    fun debug(message: String) = log(LogLevel.DEBUG, message)
    fun trace(message: String) = log(LogLevel.TRACE, message)

    fun readLast(maxBytes: Int = 256 * 1024): String {
        if (!file.isFile) return ""
        val text = file.readText()
        return if (text.length <= maxBytes) text else text.takeLast(maxBytes)
    }
}
