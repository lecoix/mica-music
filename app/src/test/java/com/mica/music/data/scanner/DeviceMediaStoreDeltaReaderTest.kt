package com.mica.music.data.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMediaStoreDeltaReaderTest {

    @Test
    fun pendingAudioRowIsTransientRegardlessOfMimeClassification() {
        val decision = deviceAudioEligibilityDecision(
            displayName = "copy.wav",
            mimeType = "audio/x-wav",
            isMusic = true,
            durationMs = 65_000L,
            pending = true,
            trashed = false,
            excluded = false,
            options = ScanOptions(minDurationMs = 60_000L),
        )

        assertEquals(LibraryEligibility.PENDING, decision.eligibility)
        assertEquals(
            DeviceEligibilityAuthority.PROVIDER_PENDING_TRANSIENT,
            decision.authority,
        )
    }

    @Test
    fun unresolvedProviderTypeForKnownAudioExtensionIsTransient() {
        val decision = deviceAudioEligibilityDecision(
            displayName = "copy.wav",
            mimeType = "",
            isMusic = false,
            durationMs = 0L,
            pending = false,
            trashed = false,
            excluded = false,
            options = ScanOptions(minDurationMs = 60_000L),
        )

        assertEquals(LibraryEligibility.FILTERED_OUT, decision.eligibility)
        assertEquals(
            DeviceEligibilityAuthority.PROVIDER_METADATA_TRANSIENT,
            decision.authority,
        )
    }

    @Test
    fun knownShortAudioIsAuthoritativeFilteredInsteadOfTransient() {
        val decision = deviceAudioEligibilityDecision(
            displayName = "short.wav",
            mimeType = "audio/wav",
            isMusic = true,
            durationMs = 15_000L,
            pending = false,
            trashed = false,
            excluded = false,
            options = ScanOptions(minDurationMs = 60_000L),
        )

        assertEquals(LibraryEligibility.FILTERED_OUT, decision.eligibility)
        assertEquals(DeviceEligibilityAuthority.AUTHORITATIVE, decision.authority)
    }

    @Test
    fun unknownNonAudioExtensionRemainsAuthoritativeFiltered() {
        val decision = deviceAudioEligibilityDecision(
            displayName = "note.bin",
            mimeType = "",
            isMusic = false,
            durationMs = 0L,
            pending = false,
            trashed = false,
            excluded = false,
            options = ScanOptions(minDurationMs = 60_000L),
        )

        assertEquals(LibraryEligibility.FILTERED_OUT, decision.eligibility)
        assertEquals(DeviceEligibilityAuthority.AUTHORITATIVE, decision.authority)
    }

    @Test
    fun windowsUsePerVolumeExclusiveInclusiveGenerationBounds() {
        val from = snapshot(
            volume("1234-5678", "sd-v1", 3L),
            volume("external_primary", "v1", 10L),
        )
        val to = snapshot(
            volume("1234-5678", "sd-v1", 5L),
            volume("external_primary", "v1", 14L),
        )

        assertEquals(
            listOf(
                DeviceDeltaWindow("1234-5678", "sd-v1", 3L, 5L),
                DeviceDeltaWindow("external_primary", "v1", 10L, 14L),
            ),
            DeviceMediaStoreDeltaReader.windows(from, to),
        )
    }

    @Test
    fun unchangedVolumeGenerationSkipsAllChannelQueriesButRemainsComplete() {
        val state = snapshot(volume("external_primary", "v1", 10L))
        var calls = 0
        val api = DeviceMediaStoreDeltaQueryApi { _, _ ->
            calls++
            error("must not query unchanged generation window")
        }

        val batch = DeviceMediaStoreDeltaReader.read(api, state, state)

        assertEquals(0, calls)
        assertTrue(batch.complete)
        assertTrue(batch.rows.isEmpty())
        assertEquals(DeviceDeltaChannel.entries.size, batch.statuses.size)
    }

    @Test
    fun oneChannelFailureMakesBatchPartialAndDoesNotHideSuccessfulRows() {
        val from = snapshot(volume("external_primary", "v1", 10L))
        val to = snapshot(volume("external_primary", "v1", 12L))
        val api = DeviceMediaStoreDeltaQueryApi { window, channel ->
            if (channel == DeviceDeltaChannel.FILES_FALLBACK) error("files unavailable")
            listOf(row(window.volumeName, channel, id = channel.ordinal.toLong() + 1L))
        }

        val batch = DeviceMediaStoreDeltaReader.read(api, from, to)

        assertFalse(batch.complete)
        assertEquals(2, batch.rows.size)
        assertEquals(
            DiscoveryCompleteness.PARTIAL,
            batch.statuses.single { it.channel == DeviceDeltaChannel.FILES_FALLBACK }.completeness,
        )
        assertEquals(
            setOf(DeviceDeltaChannel.AUDIO, DeviceDeltaChannel.LYRICS_SIDECAR),
            batch.rows.mapTo(linkedSetOf(), DeviceDeltaRow::channel),
        )
    }

    @Test
    fun sameMediaStoreIdAcrossVolumesIsIdentityContradiction() {
        val from = snapshot(
            volume("1234-5678", "sd-v1", 1L),
            volume("external_primary", "v1", 1L),
        )
        val to = snapshot(
            volume("1234-5678", "sd-v1", 2L),
            volume("external_primary", "v1", 2L),
        )
        val api = DeviceMediaStoreDeltaQueryApi { window, channel ->
            when (channel) {
                DeviceDeltaChannel.AUDIO -> listOf(row(window.volumeName, channel, id = 42L))
                else -> emptyList()
            }
        }

        val batch = DeviceMediaStoreDeltaReader.read(api, from, to)

        assertFalse(batch.complete)
        assertEquals(
            listOf(
                DeviceDeltaIdentityConflict(
                    stableObjectKey = "ms_42",
                    volumeNames = setOf("1234-5678", "external_primary"),
                ),
            ),
            batch.identityConflicts,
        )
    }

    @Test
    fun sameIdAcrossAudioAndFilesOnSameVolumeIsNotVolumeIdentityConflict() {
        val from = snapshot(volume("external_primary", "v1", 1L))
        val to = snapshot(volume("external_primary", "v1", 2L))
        val api = DeviceMediaStoreDeltaQueryApi { window, channel ->
            when (channel) {
                DeviceDeltaChannel.AUDIO,
                DeviceDeltaChannel.FILES_FALLBACK,
                -> listOf(row(window.volumeName, channel, id = 7L))
                DeviceDeltaChannel.LYRICS_SIDECAR -> emptyList()
            }
        }

        val batch = DeviceMediaStoreDeltaReader.read(api, from, to)

        assertTrue(batch.complete)
        assertTrue(batch.identityConflicts.isEmpty())
        assertEquals(2, batch.rows.size)
    }

    private fun row(
        volumeName: String,
        channel: DeviceDeltaChannel,
        id: Long,
    ) = DeviceDeltaRow(
        channel = channel,
        volumeName = volumeName,
        mediaStoreId = id,
        mediaUri = "content://media/$volumeName/$id",
        displayName = "item-$id",
        mimeType = if (channel == DeviceDeltaChannel.AUDIO) "audio/flac" else "",
        relativePath = "Music/",
        sizeBytes = 100L,
        dateModifiedMs = 2_000L,
        generationAdded = 2L,
        generationModified = 2L,
        eligibility = LibraryEligibility.ELIGIBLE,
    )

    private fun snapshot(
        vararg volumes: DeviceVolumeGeneration,
    ): DeviceGenerationSnapshot.Available = DeviceGenerationSnapshot.Available(
        volumes.associateBy(DeviceVolumeGeneration::volumeName),
    )

    private fun volume(
        name: String,
        version: String,
        generation: Long,
    ) = DeviceVolumeGeneration(name, version, generation)
}
