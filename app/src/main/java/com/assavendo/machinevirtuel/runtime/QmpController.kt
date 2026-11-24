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

    private fun execute(socketFile: File, command: String): Boolean {
        if (!socketFile.exists()) return false
        return runCatching {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                val input = socket.inputStream
                val output = socket.outputStream
                readObject(input)
                writeLine(output, """{"execute":"qmp_capabilities"}""")
                readObject(input)
                writeLine(output, command)
                readObject(input)
                true
            }
        }.getOrDefault(false)
    }

    private fun writeLine(output: java.io.OutputStream, value: String) {
        output.write((value + "\r\n").toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun readObject(input: java.io.InputStream) {
        val buffer = ByteArray(8192)
        check(input.read(buffer) > 0) { "QMP socket closed" }
    }
}
