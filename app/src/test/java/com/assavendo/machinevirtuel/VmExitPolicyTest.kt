package com.assavendo.machinevirtuel

import com.assavendo.machinevirtuel.runtime.VmExitPolicy
import com.assavendo.machinevirtuel.vm.VmState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VmExitPolicyTest {
    @Test
    fun cleanRequestedExitBecomesOffWithoutRecovery() {
        val result = VmExitPolicy.resolve(0, requestedStop = true, recoveryRequired = false)
        assertEquals(VmState.OFF, result.state)
        assertFalse(result.requiresRecoveryPlan)
        assertFalse(result.unexpectedFailure)
    }

    @Test
    fun unexpectedNonZeroExitBecomesErrorAndRequiresRecovery() {
        val result = VmExitPolicy.resolve(17, requestedStop = false, recoveryRequired = false)
        assertEquals(VmState.ERROR, result.state)
        assertTrue(result.requiresRecoveryPlan)
        assertTrue(result.unexpectedFailure)
    }

    @Test
    fun delayedExitAfterStopTimeoutRetainsRecoveryState() {
        val result = VmExitPolicy.resolve(0, requestedStop = true, recoveryRequired = true)
        assertEquals(VmState.RECOVERY, result.state)
        assertTrue(result.requiresRecoveryPlan)
        assertFalse(result.unexpectedFailure)
    }

    @Test
    fun forcedNonZeroExitIsRecoveryRatherThanUnexpectedFailure() {
        val result = VmExitPolicy.resolve(137, requestedStop = true, recoveryRequired = true)
        assertEquals(VmState.RECOVERY, result.state)
        assertTrue(result.requiresRecoveryPlan)
        assertFalse(result.unexpectedFailure)
    }

    @Test
    fun nonZeroExitFromRequestedButNonRecoveryStopIsStillAnError() {
        val result = VmExitPolicy.resolve(143, requestedStop = true, recoveryRequired = false)
        assertEquals(VmState.ERROR, result.state)
        assertTrue(result.requiresRecoveryPlan)
        assertTrue(result.unexpectedFailure)
    }
}
