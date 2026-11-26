package com.assavendo.machinevirtuel.runtime

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QemuTcgStatsParserTest {
    private val sample = """
        Accelerator settings:
        one-insn-per-tb: off

        Translation buffer state:
        gen code size       123456/8388608
        TB count            321
        TB avg target size  48 max=256 bytes
        TB avg host size    112 bytes (expansion ratio: 2.3)
        direct jump count   240 (74%) (2 jumps=31 9%)

        Statistics:
        TB flush count      4
        TB invalidate count 27
        TLB full flushes    3
    """.trimIndent()

    @Test
    fun parsesLiveTcgBlockCountCodeCapacityAndInvalidationCounters() {
        val stats = requireNotNull(QemuTcgStatsParser.parseHumanReadable(sample))

        assertEquals(321L, stats.translationBlocks)
        assertEquals(123456L, stats.codeBytes)
        assertEquals(8388608L, stats.codeCapacityBytes)
        assertEquals(4L, stats.flushCount)
        assertEquals(27L, stats.invalidationCount)
        assertEquals(240L, stats.directJumpCount)
    }

    @Test
    fun parsesQmpHumanReadableTextEnvelope() {
        val response = JSONObject()
            .put(
                "return",
                JSONObject().put("human-readable-text", sample),
            )
            .toString()

        val stats = requireNotNull(QemuTcgStatsParser.parseQmpResponse(response))
        assertEquals(321L, stats.translationBlocks)
        assertEquals(27L, stats.invalidationCount)
    }

    @Test
    fun unknownQmpShapeAndNonTcgResponseRemainUnavailable() {
        assertNull(QemuTcgStatsParser.parseQmpResponse("""{"error":{"class":"CommandNotFound"}}"""))
        assertNull(QemuTcgStatsParser.parseQmpResponse("""{"return":{"status":"running"}}"""))
        assertNull(QemuTcgStatsParser.parseHumanReadable("Accelerator settings:\none-insn-per-tb: off"))
    }

    @Test
    fun toleratesPartialStatisticsWhenQemuBuildOmitsOptionalCounters() {
        val stats = requireNotNull(
            QemuTcgStatsParser.parseHumanReadable(
                """
                Translation buffer state:
                TB count            8
                gen code size       512/4096
                """.trimIndent()
            )
        )
        assertEquals(8L, stats.translationBlocks)
        assertEquals(512L, stats.codeBytes)
        assertNull(stats.flushCount)
        assertNull(stats.invalidationCount)
    }
}
