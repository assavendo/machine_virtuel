package com.assavendo.machinevirtuel.runtime

import org.json.JSONObject

/**
 * Snapshot from QEMU 11.x x-query-jit / HMP info jit.
 * These are live TCG statistics, not persistent-cache hits or PhoenixVM artifact-cache counters.
 */
data class QemuTcgStats(
    val translationBlocks: Long? = null,
    val codeBytes: Long? = null,
    val codeCapacityBytes: Long? = null,
    val flushCount: Long? = null,
    val invalidationCount: Long? = null,
    val directJumpCount: Long? = null,
)

object QemuTcgStatsParser {
    private const val N = "([0-9][0-9,]*)"

    fun parseQmpResponse(response: String): QemuTcgStats? = runCatching {
        val json = JSONObject(response)
        val result = json.optJSONObject("return") ?: return null
        val text = result.optString("human-readable-text").takeIf { it.isNotBlank() } ?: return null
        parseHumanReadable(text)
    }.getOrNull()

    fun parseHumanReadable(text: String): QemuTcgStats? {
        fun capture(pattern: Regex): Long? =
            pattern.find(text)?.groupValues?.getOrNull(1)?.replace(",", "")?.toLongOrNull()

        val codeSize = Regex("""(?m)^\s*gen code size\s+$N\s*/\s*$N\s*$""").find(text)
        val codeBytes = codeSize?.groupValues?.getOrNull(1)?.replace(",", "")?.toLongOrNull()
        val capacityBytes = codeSize?.groupValues?.getOrNull(2)?.replace(",", "")?.toLongOrNull()

        val snapshot = QemuTcgStats(
            translationBlocks = capture(Regex("""(?m)^\s*TB count\s+$N\s*$""")),
            codeBytes = codeBytes,
            codeCapacityBytes = capacityBytes,
            flushCount = capture(Regex("""(?m)^\s*TB flush count\s+$N\s*$""")),
            invalidationCount = capture(Regex("""(?m)^\s*TB invalidate count\s+$N\s*$""")),
            directJumpCount = capture(Regex("""(?m)^\s*direct jump count\s+$N""")),
        )
        return snapshot.takeIf {
            it.translationBlocks != null || it.codeBytes != null || it.flushCount != null ||
                it.invalidationCount != null || it.directJumpCount != null
        }
    }
}
