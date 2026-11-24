package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.vm.*
import kotlin.test.Test
import kotlin.test.assertTrue

class VmSpecValidatorTest {
    @Test fun invalidRamIsFatal() {
        val vm = VmConfig(name = "bad", ramMb = 128)
        assertTrue(VmSpecValidator.validate(vm).any { it.field == "ramMb" && it.fatal })
    }

    @Test fun x86HardwareBackendIsRejected() {
        val vm = VmConfig(name = "bad", arch = GuestArch.X86_64, backend = BackendKind.KVM)
        assertTrue(VmSpecValidator.validate(vm).any { it.field == "backend" })
    }
}
