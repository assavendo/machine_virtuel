package com.assavendo.machinevirtuel.runtime

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.assavendo.machinevirtuel.performance.GuestCpuCounters
import com.assavendo.machinevirtuel.performance.GuestCpuStatsParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Short-lived, fail-safe QEMU Guest Agent client. A new UNIX socket is opened for each
 * sample so a stale or half-consumed response cannot poison the next monitoring interval.
 * The guest must have qemu-guest-agent installed and running; failure returns null.
 */
object QemuGuestAgentClient {
    private val requestIds = AtomicLong(System.currentTimeMillis().coerceAtLeast(1L))

    fun queryLinuxCpuStats(socketPath: File, timeoutMs: Int = 650): List<GuestCpuCounters>? {
        if (!socketPath.isAbsolute || socketPath.absolutePath.length > 100) return null
        val id = requestIds.incrementAndGet().coerceAtLeast(1L)
        val socket = LocalSocket()
        return try {
            socket.soTimeout = timeoutMs.coerceIn(100, 2_000)
            socket.connect(
                LocalSocketAddress(
                    socketPath.absolutePath,
                    LocalSocketAddress.Namespace.FILESYSTEM,
                )
            )
            val input = socket.inputStream
            val output = socket.outputStream

            // QGA's delimited sync starts with 0xff and discards stale bytes until its
            // matching response. This is required when opening a new stream connection.
            output.write(0xff)
            writeCommand(
                output,
                JSONObject()
                    .put("execute", "guest-sync-delimited")
                    .put("arguments", JSONObject().put("id", id))
            )
            val syncLine = readLineAfterSentinel(input) ?: return null
            val syncResponse = runCatching { JSONObject(syncLine) }.getOrNull() ?: return null
            if (syncResponse.optLong("return", Long.MIN_VALUE) != id) return null

            writeCommand(output, JSONObject().put("execute", "guest-get-cpustats"))
            val responseLine = readLine(input) ?: return null
            val response = runCatching { JSONObject(responseLine) }.getOrNull() ?: return null
            if (response.has("error")) return null
            GuestCpuStatsParser.parse(response)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun writeCommand(output: java.io.OutputStream, command: JSONObject) {
        output.write((command.toString() + "\r\n").toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun readLineAfterSentinel(input: InputStream): String? {
        while (true) {
            val value = input.read()
            if (value < 0) return null
            if (value == 0xff) return readLine(input)
        }
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() <= 64 * 1024) {
            val value = input.read()
            if (value < 0) return null
            if (value == '\n'.code) {
                return bytes.toByteArray().toString(StandardCharsets.UTF_8).trimEnd('\r')
            }
            bytes.write(value)
        }
        return null
    }
}
