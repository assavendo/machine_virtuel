package com.assavendo.machinevirtuel.runtime

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Best-effort runtime audio telemetry.
 *
 * The counter is only populated when the selected backend actually emits
 * recognizable underrun/overrun/XRUN messages into its log. Absence of such
 * messages remains indistinguishable from zero at the backend level, so the
 * monitor reports null until at least one recognizable audio event is observed.
 */
class AudioLogMonitor(private val logFile: File) {
    private var offset = 0L
    private var observedEvent = false
    private var count = 0L

    @Synchronized
    fun sample(): Long? {
        if (!logFile.isFile) return null
        val length = logFile.length()
        if (length < offset) offset = 0L
        return runCatching {
            RandomAccessFile(logFile, "r").use { file ->
                file.seek(offset)
                while (true) {
                    val line = file.readLine() ?: break
                    val normalized = line.lowercase(Locale.ROOT)
                    if (
                        normalized.contains("underrun") ||
                        normalized.contains("overrun") ||
                        normalized.contains("xrun")
                    ) {
                        count++
                        observedEvent = true
                    }
                }
                offset = file.filePointer
            }
            count.takeIf { observedEvent }
        }.getOrNull()
    }

    fun reset() {
        synchronized(this) {
            offset = 0L
            observedEvent = false
            count = 0L
        }
    }
}
