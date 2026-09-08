package com.mica.music.data.library

import com.mica.music.data.scanner.DeviceObjectRevisionRead
import com.mica.music.data.scanner.deviceObjectRevisionFingerprint

/**
 * Maps provider-specific post-probe facts back into the generic observation-authority contract.
 *
 * A probe result is publishable only when this returns [ObjectObservationValidation.Match].
 */
internal object DeviceObjectPostProbeValidator {
    fun validate(
        plan: DeviceAutoProbeObjectPlan,
        currentSourceIdentity: SourceIdentityKey,
        currentActivationEpoch: Long,
        recheck: DeviceObjectRevisionRead,
    ): ObjectObservationValidation {
        val expected = plan.observationStamp
            ?: return ObjectObservationValidation.Unverifiable("observation-stamp-missing")
        if (plan.objectRef == null) {
            return ObjectObservationValidation.Unverifiable("provider-object-ref-missing")
        }

        val genericRecheck = when (recheck) {
            DeviceObjectRevisionRead.Missing -> ObjectObservationRecheck.Missing
            is DeviceObjectRevisionRead.Unavailable ->
                ObjectObservationRecheck.Unavailable(recheck.detail)
            is DeviceObjectRevisionRead.Observed -> {
                val row = recheck.row
                ObjectObservationRecheck.Observed(
                    ObjectObservationStamp(
                        sourceIdentity = currentSourceIdentity,
                        activationEpoch = currentActivationEpoch,
                        stableObjectKey = plan.stableObjectKey,
                        fingerprint = row.deviceObjectRevisionFingerprint(),
                        providerGeneration = row.observedGeneration,
                    ),
                )
            }
        }
        return ObjectObservationValidator.validate(expected, genericRecheck)
    }
}
