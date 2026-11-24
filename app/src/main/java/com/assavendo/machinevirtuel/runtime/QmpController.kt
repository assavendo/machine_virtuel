package com.assavendo.machinevirtuel.runtime

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File
import java.nio.charset.StandardCharsets

object QmpController {
    fun systemPowerdown(socketFile: File): Boolean =
        execute(socketFile, """{"execute":"system_powerdown"}""")

    fun pause(socketFile: File): Boolean =
        execute(socketFile, """{"execute":"stop"}""")

    fun resume(socketFile: File): Boolean =
        execute(socketFile, """{"execute":"cont"}""")

    fun reset(socketFile: File): Boolean =
        execute(socketFile, """{"execute":"system_reset"}""")

    fun quit(socketFile: File): Boolean =
        execute(socketFile, """{"execute":"quit"}""")

    fun queryStatus(socketFile: File): String? =
        executeForResponse(socketFile, """{"execute":"query-status"}""")

    private fun execute(socketFile: File, command: String): Boolean =
        executeForResponse(socketFile, command) != null

    private fun executeForResponse(socketFile: File, command: String): String? {
        if (!socketFile.exists()) return null
        return runCatching {
            LocalSocket().use { socket ->
                socket.connect(
                    LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM)
                )
                val input = socket.inputStream
                val output = socket.outputStream
                readObject(input)
                writeLine(output, """{"execute":"qmp_capabilities"}""")
                readObject(input)
                writeLine(output, command)
                readObject(input)
            }
        }.getOrNull()
    }

    private fun writeLine(output: java.io.OutputStream, value: String) {
        output.write((value + "\r\n").toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun readObject(input: java.io.InputStream): String {
        val buffer = ByteArray(16 * 1024)
        val count = input.read(buffer)
        check(count > 0) { "QMP socket closed" }
        return String(buffer, 0, count, StandardCharsets.UTF_8)
    }
}
