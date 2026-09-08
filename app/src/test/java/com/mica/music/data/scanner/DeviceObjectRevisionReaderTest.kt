package com.mica.music.data.scanner

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceObjectRevisionReaderTest {

    private val ref = DeviceObjectRef(
        channel = DeviceDeltaChannel.AUDIO,
        volumeName = "external_primary",
        mediaStoreId = 42L,
    )

    @Test
    fun exactRowIsObserved() {
        val row = row()
        assertEquals(
            DeviceObjectRevisionRead.Observed(row),
            DeviceObjectRevisionReader.recheck(
                api = DeviceObjectRevisionQueryApi { row },
                ref = ref,
            ),
        )
    }

    @Test
    fun missingRowIsExplicitMissing() {
        assertEquals(
            DeviceObjectRevisionRead.Missing,
            DeviceObjectRevisionReader.recheck(
                api = DeviceObjectRevisionQueryApi { null },
                ref = ref,
            ),
        )
    }

    @Test
    fun providerFailureIsUnavailableNotMissing() {
        val result = DeviceObjectRevisionReader.recheck(
            api = DeviceObjectRevisionQueryApi { throw IOException("provider failed") },
            ref = ref,
        )

        assertEquals(
            DeviceObjectRevisionRead.Unavailable("provider failed"),
            result,
        )
    }

    @Test
    fun mismatchedIdentityCannotMasqueradeAsPostProbeMatch() {
        val result = DeviceObjectRevisionReader.recheck(
            api = DeviceObjectRevisionQueryApi {
                row().copy(mediaStoreId = 43L)
            },
            ref = ref,
        )

        assertTrue(result is DeviceObjectRevisionRead.Unavailable)
    }

    @Test
    fun fingerprintIncludesRevisionAndEligibilityFacts() {
        val base = row()
        assertEquals(
            "AUDIO|external_primary|42|100|2000|ELIGIBLE|AUTHORITATIVE",
            base.deviceObjectRevisionFingerprint(),
        )
        assertTrue(
            base.copy(sizeBytes = 101L).deviceObjectRevisionFingerprint() !=
                base.deviceObjectRevisionFingerprint(),
        )
        assertTrue(
            base.copy(eligibility = LibraryEligibility.PENDING).deviceObjectRevisionFingerprint() !=
                base.deviceObjectRevisionFingerprint(),
        )
        assertTrue(
            base.copy(
                eligibilityAuthority = DeviceEligibilityAuthority.PROVIDER_PENDING_TRANSIENT,
            ).deviceObjectRevisionFingerprint() != base.deviceObjectRevisionFingerprint(),
        )
    }

    private fun row() = DeviceDeltaRow(
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
}
