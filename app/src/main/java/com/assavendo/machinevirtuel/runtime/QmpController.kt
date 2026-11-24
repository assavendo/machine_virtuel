package com.assavendo.machinevirtuel.runtime

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File
import java.nio.charset.StandardCharsets

object QmpController {
    fun systemPowerdown(socketFile: File): Boolean {
        if (!socketFile.exists()) return false
        return runCatching {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                val input = socket.inputStream
                val output = socket.outputStream
                readObject(input)
                writeLine(output, """{"execute":"qmp_capabilities"}""")
                readObject(input)
                writeLine(output, """{"execute":"system_powerdown"}""")
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
        val count = input.read(buffer)
        check(count > 0) { "QMP socket closed" }
    }
}
