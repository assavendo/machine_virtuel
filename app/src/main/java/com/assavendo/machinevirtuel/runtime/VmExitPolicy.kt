package com.assavendo.machinevirtuel.runtime

import com.assavendo.machinevirtuel.vm.VmState

internal data class VmExitDisposition(
    val state: VmState,
    val requiresRecoveryPlan: Boolean,
    val unexpectedFailure: Boolean,
)

/**
 * Maps backend exit observations to persisted VM state without treating a non-zero exit
 * as clean merely because a stop request happened to be pending.
 */
internal object VmExitPolicy {
    fun resolve(
        exitCode: Int,
        requestedStop: Boolean,
        recoveryRequired: Boolean,
    ): VmExitDisposition {
        val unexpectedFailure = exitCode != 0 && (!requestedStop || !recoveryRequired)
        return when {
            unexpectedFailure -> VmExitDisposition(
                state = VmState.ERROR,
                requiresRecoveryPlan = true,
                unexpectedFailure = true,
            )
            recoveryRequired -> VmExitDisposition(
                state = VmState.RECOVERY,
                requiresRecoveryPlan = true,
                unexpectedFailure = false,
            )
            else -> VmExitDisposition(
                state = VmState.OFF,
                requiresRecoveryPlan = false,
                unexpectedFailure = false,
            )
        }
    }
}
