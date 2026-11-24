package com.assavendo.machinevirtuel.runtime

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
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
        if (!socketFile.isFile) return null
        return runCatching {
            LocalSocket().use { socket ->
                socket.setSoTimeout(3_000)
                socket.connect(
                    LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM)
                )
                BufferedReader(InputStreamReader(socket.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    BufferedWriter(OutputStreamWriter(socket.outputStream, StandardCharsets.UTF_8)).use { writer ->
                        val greeting = reader.readLine() ?: error("QMP closed before greeting.")
                        check(greeting.contains("\"QMP\"")) { "Invalid QMP greeting." }
                        send(writer, """{"execute":"qmp_capabilities"}""")
                        readCommandResult(reader)
                        send(writer, command)
                        readCommandResult(reader)
                    }
                }
            }
        }.getOrNull()
    }

    private fun send(writer: BufferedWriter, command: String) {
        writer.write(command)
        writer.write("\r\n")
        writer.flush()
    }

    private fun readCommandResult(reader: BufferedReader): String? {
        while (true) {
            val line = reader.readLine() ?: return null
            if (line.contains("\"return\"")) return line
            if (line.contains("\"error\"")) return null
            // QMP events may be interleaved with command responses.
        }
    }
}
