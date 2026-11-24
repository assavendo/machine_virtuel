package com.assavendo.machinevirtuel.runtime

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class RfbClient(
    private val host: String,
    private val port: Int,
    private val listener: Listener,
) {
    interface Listener {
        fun onSize(width: Int, height: Int)
        fun onFrame(x: Int, y: Int, width: Int, height: Int, argb: IntArray)
        fun onError(error: Throwable)
    }

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var socket: Socket? = null
    @Volatile private var output: BufferedOutputStream? = null
    private var width = 1
    private var height = 1

    fun connect() {
        executor.execute {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 5000)
                s.tcpNoDelay = true
                socket = s
                val input = BufferedInputStream(s.getInputStream(), 64 * 1024)
                output = BufferedOutputStream(s.getOutputStream(), 16 * 1024)
                handshake(input)
                readLoop(input)
            } catch (t: Throwable) {
                listener.onError(t)
            }
        }
    }

    fun close() {
        socket?.close()
        executor.shutdownNow()
    }

    fun key(down: Boolean, keysym: Int) {
        val out = output ?: return
        synchronized(out) {
            out.write(byteArrayOf(4, if (down) 1 else 0, 0, 0))
            out.write(intBytes(keysym))
            out.flush()
        }
    }

    fun pointer(buttonMask: Int, x: Int, y: Int) {
        val out = output ?: return
        synchronized(out) {
            out.write(byteArrayOf(5, buttonMask.toByte()))
            out.write(shortBytes(x))
            out.write(shortBytes(y))
            out.flush()
        }
    }

    private fun handshake(input: BufferedInputStream) {
        val version = readFixed(input, 12)
        require(String(version, StandardCharsets.US_ASCII).startsWith("RFB")) { "Invalid RFB server version" }
        output!!.write(version)
        output!!.flush()

        val count = input.read()
        require(count > 0) { "RFB server offered no security types" }
        val types = ByteArray(count)
        readFully(input, types)
        require(types.any { it.toInt() == 1 }) { "RFB security type None is unavailable" }
        output!!.write(1)
        output!!.flush()
        require(readInt(input) == 0) { "RFB security negotiation failed" }

        output!!.write(1)
        output!!.flush()

        width = readU16(input)
        height = readU16(input)
        readFixed(input, 16)
        val nameLength = readInt(input)
        if (nameLength > 0) readFixed(input, nameLength)
        listener.onSize(width, height)

        val format = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        format.put(0.toByte()).put(ByteArray(3))
        format.put(32.toByte()).put(24.toByte()).put(0.toByte()).put(1.toByte())
        format.putShort(255).putShort(255).putShort(255)
        format.put(16.toByte()).put(8.toByte()).put(0.toByte()).put(ByteArray(3))
        output!!.write(format.array())

        val encodings = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        encodings.put(2.toByte()).put(ByteArray(3)).putShort(1).putInt(0)
        output!!.write(encodings.array())

        sendFramebufferRequest(false)
    }

    private fun readLoop(input: BufferedInputStream) {
        while (!Thread.currentThread().isInterrupted) {
            when (input.read()) {
                -1 -> return
                0 -> readFramebufferUpdate(input)
                2 -> Unit
                3 -> readFixed(input, 7)
                else -> throw IllegalStateException("Unsupported RFB server message")
            }
            sendFramebufferRequest(true)
        }
    }

    private fun readFramebufferUpdate(input: BufferedInputStream) {
        readFixed(input, 1)
        val rectCount = readU16(input)
        repeat(rectCount) {
            val x = readU16(input)
            val y = readU16(input)
            val w = readU16(input)
            val h = readU16(input)
            val encoding = readInt(input)
            require(encoding == 0) { "Unsupported RFB encoding: $encoding" }
            val raw = ByteArray(w * h * 4)
            readFully(input, raw)
            val pixels = IntArray(w * h)
            var p = 0
            for (i in pixels.indices) {
                val b = raw[p++].toInt() and 255
                val g = raw[p++].toInt() and 255
                val r = raw[p++].toInt() and 255
                p++
                pixels[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
            listener.onFrame(x, y, w, h, pixels)
        }
    }

    private fun sendFramebufferRequest(incremental: Boolean) {
        val out = output ?: return
        synchronized(out) {
            val request = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
                 .put(3.toByte())
                .put(if (incremental) 1.toByte() else 0.toByte())
                .putShort(0).putShort(0)
                .putShort(width.toShort()).putShort(height.toShort())
                .array()
            out.write(request)
            out.flush()
        }
    }

    private fun shortBytes(value: Int) =
        byteArrayOf((value ushr 8).toByte(), value.toByte())

    private fun intBytes(value: Int) =
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )

    private fun readFixed(input: BufferedInputStream, size: Int): ByteArray {
        val result = ByteArray(size)
        readFully(input, result)
        return result
    }

    private fun readFully(input: BufferedInputStream, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val n = input.read(data, offset, data.size - offset)
            check(n > 0) { "RFB connection closed" }
            offset += n
        }
    }

    private fun readU16(input: BufferedInputStream): Int =
        (input.read() shl 8) or input.read()

    private fun readInt(input: BufferedInputStream): Int {
        val data = readFixed(input, 4)
        return ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).int
    }
}
