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

    fun queryVcpuThreadIds(socketFile: File): List<Long> =
        queryThreadIds(socketFile, "query-cpus-fast")

    /** Returns the host TIDs of QEMU's named I/O threads when the build exposes them. */
    fun queryIoThreadIds(socketFile: File): List<Long> =
        queryThreadIds(socketFile, "query-iothreads")

    private fun queryThreadIds(socketFile: File, command: String): List<Long> =
        runCatching {
            val response = executeForResponse(
                socketFile,
                """{"execute":"$command"}"""
            ) ?: return@runCatching emptyList()
            val values = org.json.JSONObject(response).optJSONArray("return")
                ?: return@runCatching emptyList()
            buildList {
                for (index in 0 until values.length()) {
                    val item = values.optJSONObject(index) ?: continue
                    val threadId = item.optLong("thread-id", -1L)
                    if (threadId > 0L) add(threadId)
                }
            }.distinct()
        }.getOrDefault(emptyList())

    fun setBalloonTarget(socketFile: File, targetBytes: Long): Boolean {
        require(targetBytes >= 256L * 1024L * 1024L) { "Balloon target must preserve at least 256 MiB." }
        return execute(
            socketFile,
            """{"execute":"balloon","arguments":{"value":$targetBytes}}"""
        )
    }

    private fun execute(socketFile: File, command: String): Boolean =
        executeForResponse(socketFile, command) != null

    private fun executeForResponse(socketFile: File, command: String): String? {
        if (!socketFile.exists()) return null
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
                        check(readCommandResult(reader) != null) { "QMP capability negotiation failed." }
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
