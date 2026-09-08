package com.mica.music.data.library

import com.mica.music.data.scanner.DeviceDeltaChannel
import com.mica.music.data.scanner.DeviceDeltaRow
import com.mica.music.data.scanner.DeviceObjectRef
import com.mica.music.data.scanner.DeviceObjectRevisionRead
import com.mica.music.data.scanner.LibraryEligibility
import com.mica.music.data.scanner.deviceObjectRevisionFingerprint
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceObjectPostProbeValidatorTest {

    private val source = SourceIdentityKey.device()
    private val row = DeviceDeltaRow(
        channel = DeviceDeltaChannel.AUDIO,
        volumeName = "external_primary",
        mediaStoreId = 42L,
        mediaUri = "content://media/external_primary/audio/42",
        displayName = "track.flac",
        mimeType = "audio/flac",
        relativePath = "Music",
        sizeBytes = 100L,
        dateModifiedMs = 2_000L,
        generationAdded = 10L,
        generationModified = 12L,
        eligibility = LibraryEligibility.ELIGIBLE,
    )
    private val plan = DeviceAutoProbeObjectPlan(
        stableObjectKey = "ms_42",
        mediaUri = row.mediaUri,
        reasons = setOf(DeviceAutoProbeReason.MEDIASTORE_REVISION_CHANGED),
        work = setOf(DeviceAutoProbeWork.AUDIO_METADATA),
        disposition = DeviceAutoProbeDisposition.READY,
        observationStamp = ObjectObservationStamp(
            sourceIdentity = source,
            activationEpoch = 7L,
            stableObjectKey = "ms_42",
            fingerprint = row.deviceObjectRevisionFingerprint(),
            providerGeneration = row.observedGeneration,
        ),
        objectRef = DeviceObjectRef(
            channel = row.channel,
            volumeName = row.volumeName,
            mediaStoreId = row.mediaStoreId,
        ),
    )

    @Test
    fun unchangedRowMatches() {
        assertEquals(
            ObjectObservationValidation.Match,
            DeviceObjectPostProbeValidator.validate(
                plan = plan,
                currentSourceIdentity = source,
                currentActivationEpoch = 7L,
                recheck = DeviceObjectRevisionRead.Observed(row),
            ),
        )
    }

    @Test
    fun changedRevisionRejectsProbeResult() {
        assertEquals(
            ObjectObservationValidation.Changed("fingerprint"),
            DeviceObjectPostProbeValidator.validate(
                plan = plan,
                currentSourceIdentity = source,
                currentActivationEpoch = 7L,
                recheck = DeviceObjectRevisionRead.Observed(
                    row.copy(dateModifiedMs = 3_000L, generationModified = 13L),
                ),
            ),
        )
    }

    @Test
    fun sourceOrActivationChangeRejectsEvenIfProviderRowMatches() {
        assertEquals(
            ObjectObservationValidation.Changed("source-identity"),
            DeviceObjectPostProbeValidator.validate(
                plan = plan,
                currentSourceIdentity = SourceIdentityKey.folder("content://tree/other"),
                currentActivationEpoch = 7L,
                recheck = DeviceObjectRevisionRead.Observed(row),
            ),
        )
        assertEquals(
            ObjectObservationValidation.Changed("activation-epoch"),
            DeviceObjectPostProbeValidator.validate(
                plan = plan,
                currentSourceIdentity = source,
                currentActivationEpoch = 8L,
                recheck = DeviceObjectRevisionRead.Observed(row),
            ),
        )
    }

    @Test
    fun missingOrUnavailableNeverMatches() {
        assertEquals(
            ObjectObservationValidation.Missing,
            DeviceObjectPostProbeValidator.validate(
                plan = plan,
                currentSourceIdentity = source,
                currentActivationEpoch = 7L,
                recheck = DeviceObjectRevisionRead.Missing,
            ),
        )
        assertEquals(
            ObjectObservationValidation.Unverifiable("provider failed"),
            DeviceObjectPostProbeValidator.validate(
                plan = plan,
                currentSourceIdentity = source,
                currentActivationEpoch = 7L,
                recheck = DeviceObjectRevisionRead.Unavailable("provider failed"),
            ),
        )
    }

    @Test
    fun retryOnlyPlanWithoutProviderRefCannotCommitProbeResult() {
        val retryOnly = plan.copy(
            objectRef = null,
            observationStamp = plan.observationStamp?.copy(
                fingerprint = "retry-revision",
                providerGeneration = null,
            ),
            disposition = DeviceAutoProbeDisposition.REQUERY_OBJECT_REVISION,
        )

        assertEquals(
            ObjectObservationValidation.Unverifiable("provider-object-ref-missing"),
            DeviceObjectPostProbeValidator.validate(
                plan = retryOnly,
                currentSourceIdentity = source,
                currentActivationEpoch = 7L,
                recheck = DeviceObjectRevisionRead.Observed(row),
            ),
        )
    }
}
