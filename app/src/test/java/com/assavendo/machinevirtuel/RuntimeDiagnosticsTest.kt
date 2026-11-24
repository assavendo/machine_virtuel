package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.runtime.RuntimeDiagnostic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeDiagnosticsTest {
    @Test
    fun diagnosticDataKeepsComponentResultAndDetail() {
        val value = RuntimeDiagnostic("QEMU", true, "/runtime/qemu")
        assertTrue(value.ok)
        assertEquals("QEMU", value.component)
        assertEquals("/runtime/qemu", value.detail)
    }
}
