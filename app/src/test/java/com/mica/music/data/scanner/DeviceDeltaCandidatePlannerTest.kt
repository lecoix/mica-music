package com.mica.music.data.scanner

import com.mica.music.data.Song
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDeltaCandidatePlannerTest {

    @Test
    fun audioAndFilesFallbackUseFullScannerDuplicateKeyAndPreferAudioRow() {
        val existing = librarySong(
            id = "ms_100",
            fileName = "track.dff",
            folder = "Music",
            size = 123L,
        )
        val audio = row(
            channel = DeviceDeltaChannel.AUDIO,
            id = 100L,
            name = "track.dff",
            folder = "Music/",
            size = 123L,
            generation = 7L,
        )
        val fallback = row(
            channel = DeviceDeltaChannel.FILES_FALLBACK,
            id = 999L,
            name = "track.dff",
            folder = "Music",
            size = 123L,
            generation = 8L,
        )

        val plan = DeviceDeltaCandidatePlanner.plan(
            batch(audio, fallback),
            currentSongs = listOf(existing),
        )

        assertEquals(1, plan.audioCandidates.size)
        val candidate = plan.audioCandidates.single()
        assertEquals(DeviceDeltaChannel.AUDIO, candidate.primaryRow.channel)
        assertEquals("ms_100", candidate.canonicalStableObjectKey)
        assertEquals("ms_100", candidate.existingSongId)
        assertEquals(setOf("ms_100", "ms_999"), candidate.observedStableObjectKeys)
        assertTrue(candidate.requiresProbe)
        assertFalse(plan.hasContradictions)
    }

    @Test
    fun changedSidecarMapsToExistingSongAndChangedAudioCandidateUsingSharedLyricsKey() {
        val existing = librarySong(
            id = "ms_5",
            fileName = "My Song.flac",
            folder = "Music/Album",
            size = 500L,
        )
        val audio = row(
            channel = DeviceDeltaChannel.AUDIO,
            id = 5L,
            name = "MY SONG.FLAC",
            folder = "music/album/",
            size = 500L,
            generation = 10L,
        )
        val lrc = row(
            channel = DeviceDeltaChannel.LYRICS_SIDECAR,
            id = 88L,
            name = "my song.LRC",
            folder = "MUSIC/ALBUM",
            size = 50L,
            generation = 10L,
        )

        val plan = DeviceDeltaCandidatePlanner.plan(
            batch(audio, lrc),
            currentSongs = listOf(existing),
        )

        assertTrue(plan.audioCandidates.single().sidecarChanged)
        val sidecar = plan.sidecarCandidates.single()
        assertEquals(setOf("ms_5"), sidecar.affectedSongIds)
        assertEquals(setOf("ms_5"), sidecar.affectedAudioStableObjectKeys)
        assertEquals(setOf("ms_5"), plan.affectedExistingSongIds)
    }

    @Test
    fun sidecarOnlyDeltaStillTargetsExistingSongWithoutAudioRow() {
        val existing = librarySong(
            id = "ms_6",
            fileName = "only-sidecar.flac",
            folder = "Music",
            size = 600L,
        )
        val ttml = row(
            channel = DeviceDeltaChannel.LYRICS_SIDECAR,
            id = 89L,
            name = "ONLY-SIDECAR.ttml",
            folder = "music/",
            size = 60L,
            generation = 11L,
        )

        val plan = DeviceDeltaCandidatePlanner.plan(
            batch(ttml),
            currentSongs = listOf(existing),
        )

        assertTrue(plan.audioCandidates.isEmpty())
        assertEquals(setOf("ms_6"), plan.sidecarCandidates.single().affectedSongIds)
        assertEquals(setOf("ms_6"), plan.affectedExistingSongIds)
    }

    @Test
    fun transientProviderClassificationIsExposedAsCursorHoldAuthority() {
        val transient = row(
            channel = DeviceDeltaChannel.AUDIO,
            id = 70L,
            name = "copy.wav",
            folder = "Music",
            size = 700L,
            generation = 12L,
            eligibility = LibraryEligibility.FILTERED_OUT,
            eligibilityAuthority = DeviceEligibilityAuthority.PROVIDER_METADATA_TRANSIENT,
        )

        val plan = DeviceDeltaCandidatePlanner.plan(
            batch(transient),
            currentSongs = emptyList(),
        )

        assertEquals(setOf("ms_70"), plan.transientProviderClassificationKeys)
        assertFalse(plan.audioCandidates.single().requiresProbe)
        assertTrue(plan.audioCandidates.single().hasTransientProviderClassification)
    }

    @Test
    fun filteredOrTrashedAudioStillProducesMembershipCandidateButNoProbe() {
        val trashed = row(
            channel = DeviceDeltaChannel.AUDIO,
            id = 7L,
            name = "trashed.flac",
            folder = "Music",
            size = 700L,
            generation = 12L,
            eligibility = LibraryEligibility.TRASHED,
        )

        val candidate = DeviceDeltaCandidatePlanner.plan(
            batch(trashed),
            currentSongs = emptyList(),
        ).audioCandidates.single()

        assertEquals(LibraryEligibility.TRASHED, candidate.primaryRow.eligibility)
        assertFalse(candidate.requiresProbe)
    }

    @Test
    fun duplicateCurrentCatalogIdentityIsReportedAsContradiction() {
        val first = librarySong(
            id = "ms_8",
            fileName = "same.flac",
            folder = "Music",
            size = 800L,
        )
        val second = first.copy(id = "ms_9", mediaUri = "content://media/external/audio/media/9")
        val changed = row(
            channel = DeviceDeltaChannel.AUDIO,
            id = 8L,
            name = "same.flac",
            folder = "Music/",
            size = 800L,
            generation = 13L,
        )

        val plan = DeviceDeltaCandidatePlanner.plan(
            batch(changed),
            currentSongs = listOf(first, second),
        )

        assertTrue(plan.hasContradictions)
        assertTrue(plan.contradictions.single().detail.contains("ms_8"))
        assertTrue(plan.contradictions.single().detail.contains("ms_9"))
    }

    private fun librarySong(
        id: String,
        fileName: String,
        folder: String,
        size: Long,
    ): Song = SongFixtures.song(id).copy(
        mediaUri = "content://media/external/audio/media/${id.removePrefix("ms_")}",
        fileName = fileName,
        folderPath = folder,
        filePath = mediaStoreReadableFilePath(folder, fileName),
        sizeBytes = size,
    )

    private fun row(
        channel: DeviceDeltaChannel,
        id: Long,
        name: String,
        folder: String,
        size: Long,
        generation: Long,
        eligibility: LibraryEligibility = LibraryEligibility.ELIGIBLE,
        eligibilityAuthority: DeviceEligibilityAuthority =
            DeviceEligibilityAuthority.AUTHORITATIVE,
    ): DeviceDeltaRow = DeviceDeltaRow(
        channel = channel,
        volumeName = "external_primary",
        mediaStoreId = id,
        mediaUri = "content://media/external_primary/${channel.name.lowercase()}/$id",
        displayName = name,
        mimeType = if (channel == DeviceDeltaChannel.AUDIO) "audio/flac" else "",
        relativePath = folder,
        sizeBytes = size,
        dateModifiedMs = 1_000L + generation,
        generationAdded = generation,
        generationModified = generation,
        eligibility = eligibility,
        eligibilityAuthority = eligibilityAuthority,
    )

    private fun batch(
        vararg rows: DeviceDeltaRow,
    ): DeviceMediaStoreDeltaBatch = DeviceMediaStoreDeltaBatch(
        windows = listOf(
            DeviceDeltaWindow(
                volumeName = "external_primary",
                providerVersion = "v1",
                fromGenerationExclusive = 1L,
                toGenerationInclusive = 20L,
            ),
        ),
        rows = rows.toList(),
        statuses = DeviceDeltaChannel.entries.map { channel ->
            DeviceDeltaChannelStatus(
                volumeName = "external_primary",
                channel = channel,
                completeness = DiscoveryCompleteness.COMPLETE,
            )
        },
        identityConflicts = emptyList(),
    )
}
