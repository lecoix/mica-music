package com.mica.music.data.library

/**
 * Object-level authority stamp captured when discovery decides an object needs a probe.
 *
 * An operation token proves the pass is still legal. This stamp separately proves that a probe
 * result still belongs to the same observed revision of the same physical/logical object.
 */
internal data class ObjectObservationStamp(
    val sourceIdentity: SourceIdentityKey,
    val activationEpoch: Long,
    val stableObjectKey: String,
    val fingerprint: String?,
    val providerGeneration: Long? = null,
) {
    init {
        require(stableObjectKey.isNotBlank())
    }
}

internal sealed interface ObjectObservationRecheck {
    data class Observed(
        val stamp: ObjectObservationStamp,
    ) : ObjectObservationRecheck

    data object Missing : ObjectObservationRecheck

    data class Unavailable(
        val detail: String,
    ) : ObjectObservationRecheck
}

internal sealed interface ObjectObservationValidation {
    data object Match : ObjectObservationValidation

    data class Changed(
        val reason: String,
    ) : ObjectObservationValidation

    data object Missing : ObjectObservationValidation

    data class Unverifiable(
        val reason: String,
    ) : ObjectObservationValidation
}

internal object ObjectObservationValidator {
    fun validate(
        expected: ObjectObservationStamp,
        recheck: ObjectObservationRecheck,
    ): ObjectObservationValidation = when (recheck) {
        ObjectObservationRecheck.Missing -> ObjectObservationValidation.Missing

        is ObjectObservationRecheck.Unavailable ->
            ObjectObservationValidation.Unverifiable(recheck.detail)

        is ObjectObservationRecheck.Observed -> validateObserved(expected, recheck.stamp)
    }

    private fun validateObserved(
        expected: ObjectObservationStamp,
        actual: ObjectObservationStamp,
    ): ObjectObservationValidation {
        if (expected.sourceIdentity != actual.sourceIdentity) {
            return ObjectObservationValidation.Changed("source-identity")
        }
        if (expected.activationEpoch != actual.activationEpoch) {
            return ObjectObservationValidation.Changed("activation-epoch")
        }
        if (expected.stableObjectKey != actual.stableObjectKey) {
            return ObjectObservationValidation.Changed("stable-object-key")
        }

        val expectedFingerprint = expected.fingerprint
            ?: return ObjectObservationValidation.Unverifiable("expected-fingerprint-missing")
        val actualFingerprint = actual.fingerprint
            ?: return ObjectObservationValidation.Unverifiable("actual-fingerprint-missing")
        if (expectedFingerprint != actualFingerprint) {
            return ObjectObservationValidation.Changed("fingerprint")
        }

        val expectedGeneration = expected.providerGeneration
        if (
            expectedGeneration != null &&
            actual.providerGeneration != expectedGeneration
        ) {
            return ObjectObservationValidation.Changed("provider-generation")
        }

        return ObjectObservationValidation.Match
    }
}
