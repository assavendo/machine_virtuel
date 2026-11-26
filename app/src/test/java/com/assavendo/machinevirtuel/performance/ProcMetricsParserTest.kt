package com.assavendo.machinevirtuel.performance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProcMetricsParserTest {
    @Test
    fun systemCpuParserSeparatesIdleAndIoWaitAndExcludesGuestDoubleCounting() {
        val parsed = ProcMetricsParser.systemCpuTimes(
            "cpu 100 20 30 400 50 6 7 8 900 100"
        )

        requireNotNull(parsed)
        assertEquals(621L, parsed.totalTicks)
        assertEquals(400L, parsed.idleTicks)
        assertEquals(50L, parsed.ioWaitTicks)
    }

    @Test
    fun processIoParserUsesBlockIoCountersAndAllowsExtraProcFields() {
        val parsed = ProcMetricsParser.processIo(
            """
            rchar: 9000
            wchar: 8000
            syscr: 12
            syscw: 13
            read_bytes: 4096
            write_bytes: 8192
            cancelled_write_bytes: 1024
            """.trimIndent()
        )

        requireNotNull(parsed)
        assertEquals(4096L, parsed.readBytes)
        assertEquals(8192L, parsed.writeBytes)
    }

    @Test
    fun parsersRejectMalformedOrIncompleteProcData() {
        assertNull(ProcMetricsParser.systemCpuTimes("cpu 1 2 3"))
        assertNull(ProcMetricsParser.systemCpuTimes("cpu0 1 2 3 4 5"))
        assertNull(ProcMetricsParser.processIo("read_bytes: 10"))
        assertNull(ProcMetricsParser.processIo("read_bytes: -1\\nwrite_bytes: 2"))
    }
}
