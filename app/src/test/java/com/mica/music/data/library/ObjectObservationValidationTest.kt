package com.mica.music.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectObservationValidationTest {

    private val source = SourceIdentityKey.device()
    private val expected = ObjectObservationStamp(
        sourceIdentity = source,
        activationEpoch = 7L,
        stableObjectKey = "ms_42",
        fingerprint = "AUDIO|external_primary|42|100|200|ELIGIBLE",
        providerGeneration = 12L,
    )

    @Test
    fun exactObservationMatches() {
        assertEquals(
            ObjectObservationValidation.Match,
            ObjectObservationValidator.validate(
                expected,
                ObjectObservationRecheck.Observed(expected),
            ),
        )
    }

    @Test
    fun fingerprintChangeRejectsProbeResult() {
        assertEquals(
            ObjectObservationValidation.Changed("fingerprint"),
            ObjectObservationValidator.validate(
                expected,
                ObjectObservationRecheck.Observed(
                    expected.copy(fingerprint = expected.fingerprint + "|changed"),
                ),
            ),
        )
    }

    @Test
    fun providerGenerationChangeRejectsProbeResult() {
        assertEquals(
            ObjectObservationValidation.Changed("provider-generation"),
            ObjectObservationValidator.validate(
                expected,
                ObjectObservationRecheck.Observed(
                    expected.copy(providerGeneration = 13L),
                ),
            ),
        )
    }

    @Test
    fun sourceActivationAndStableIdentityChangesAreRejected() {
        val sourceChanged = ObjectObservationValidator.validate(
            expected,
            ObjectObservationRecheck.Observed(
                expected.copy(sourceIdentity = SourceIdentityKey.folder("content://tree/other")),
            ),
        )
        val activationChanged = ObjectObservationValidator.validate(
            expected,
            ObjectObservationRecheck.Observed(expected.copy(activationEpoch = 8L)),
        )
        val stableKeyChanged = ObjectObservationValidator.validate(
            expected,
            ObjectObservationRecheck.Observed(expected.copy(stableObjectKey = "ms_43")),
        )

        assertEquals(ObjectObservationValidation.Changed("source-identity"), sourceChanged)
        assertEquals(ObjectObservationValidation.Changed("activation-epoch"), activationChanged)
        assertEquals(ObjectObservationValidation.Changed("stable-object-key"), stableKeyChanged)
    }

    @Test
    fun missingAndUnavailableNeverValidate() {
        assertEquals(
            ObjectObservationValidation.Missing,
            ObjectObservationValidator.validate(expected, ObjectObservationRecheck.Missing),
        )
        val unavailable = ObjectObservationValidator.validate(
            expected,
            ObjectObservationRecheck.Unavailable("provider down"),
        )
        assertEquals(
            ObjectObservationValidation.Unverifiable("provider down"),
            unavailable,
        )
    }

    @Test
    fun missingFingerprintIsUnverifiableRatherThanAFalseMatch() {
        val missingExpected = ObjectObservationValidator.validate(
            expected.copy(fingerprint = null),
            ObjectObservationRecheck.Observed(expected.copy(fingerprint = null)),
        )
        val missingActual = ObjectObservationValidator.validate(
            expected,
            ObjectObservationRecheck.Observed(expected.copy(fingerprint = null)),
        )

        assertEquals(
            ObjectObservationValidation.Unverifiable("expected-fingerprint-missing"),
            missingExpected,
        )
        assertEquals(
            ObjectObservationValidation.Unverifiable("actual-fingerprint-missing"),
            missingActual,
        )
    }

    @Test
    fun retryStampWithoutProviderGenerationCanStillMatchSameFingerprint() {
        val retryStamp = expected.copy(
            fingerprint = "retry-revision",
            providerGeneration = null,
        )
        val rechecked = retryStamp.copy(providerGeneration = 999L)

        assertTrue(
            ObjectObservationValidator.validate(
                retryStamp,
                ObjectObservationRecheck.Observed(rechecked),
            ) is ObjectObservationValidation.Match,
        )
    }
}
