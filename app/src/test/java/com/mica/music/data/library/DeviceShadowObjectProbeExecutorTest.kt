package com.mica.music.data.library

import com.mica.music.data.LyricLine
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsOrigin
import com.mica.music.data.LyricsProbeResult
import com.mica.music.data.LyricsSlots
import com.mica.music.data.Song
import com.mica.music.data.toLyricsDocumentCompat
import com.mica.music.data.scanner.DeviceDeltaChannel
import com.mica.music.data.scanner.DeviceDeltaFolderCasingPlan
import com.mica.music.data.scanner.DeviceDeltaRow
import com.mica.music.data.scanner.DeviceObjectRef
import com.mica.music.data.scanner.DeviceShadowCanonicalAspect
import com.mica.music.data.scanner.DeviceShadowProbeDraftObservation
import com.mica.music.data.scanner.DeviceShadowProbeDraftQueryApi
import com.mica.music.data.scanner.DiscoveryCompleteness
import com.mica.music.data.scanner.DiscoveryPartitionStatus
import com.mica.music.data.scanner.DiscoveryPartitions
import com.mica.music.data.scanner.ExternalLyricsRef
import com.mica.music.data.scanner.LibraryEligibility
import com.mica.music.data.scanner.MediaStoreLyricsSidecarInventoryResult
import com.mica.music.data.scanner.ScannedSong
import com.mica.music.data.scanner.TrackDraft
import com.mica.music.data.scanner.deviceObjectRevisionFingerprint
import com.mica.music.data.scanner.mediaStoreSongLyricsKey
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceShadowObjectProbeExecutorTest {

    private val source = SourceIdentityKey.device()
    private val row = DeviceDeltaRow(
        channel = DeviceDeltaChannel.AUDIO,
        volumeName = "external_primary",
        mediaStoreId = 42L,
        mediaUri = "content://media/external_primary/audio/42",
        displayName = "track.flac",
        mimeType = "audio/flac",
        relativePath = "Music/",
        sizeBytes = 100L,
        dateModifiedMs = 2_000L,
        generationAdded = 10L,
        generationModified = 12L,
        eligibility = LibraryEligibility.ELIGIBLE,
    )
    private val current = SongFixtures.song("ms_42").copy(
        mediaUri = row.mediaUri,
        fileName = row.displayName,
        folderPath = "Music",
        filePath = "Music/track.flac",
        sizeBytes = row.sizeBytes,
        dateModifiedMs = row.dateModifiedMs,
        videoCoverUri = "content://stale/video-cover",
        videoCoverRevision = "stale-cover",
        musicVideoUri = "content://stale/music-video",
        musicVideoRevision = "stale-mv",
    )
    private val plan = DeviceAutoProbePlan(
        objects = listOf(
            DeviceAutoProbeObjectPlan(
                stableObjectKey = current.id,
                mediaUri = row.mediaUri,
                reasons = setOf(DeviceAutoProbeReason.MEDIASTORE_REVISION_CHANGED),
                work = setOf(DeviceAutoProbeWork.AUDIO_METADATA),
                disposition = DeviceAutoProbeDisposition.READY,
                observationStamp = ObjectObservationStamp(
                    sourceIdentity = source,
                    activationEpoch = 7L,
                    stableObjectKey = current.id,
                    fingerprint = row.deviceObjectRevisionFingerprint(),
                    providerGeneration = row.observedGeneration,
                ),
                objectRef = DeviceObjectRef(
                    channel = row.channel,
                    volumeName = row.volumeName,
                    mediaStoreId = row.mediaStoreId,
                ),
            ),
        ),
        heavyProbeParallelism = 1,
    )

    @Test
    fun successRequiresMatchingPreAndPostRevisionAndResolvesOnlyProbeOwnedAspects() {
        var probeCalls = 0
        val result = execute(
            draftRow = row,
            postRow = row,
            audioProbe = DeviceShadowAudioProbeApi { draft, cached ->
                probeCalls += 1
                ScannedSong(
                    song = cached!!.copy(
                        title = "updated",
                        mediaUri = draft.mediaUri,
                        fileName = draft.displayName.orEmpty(),
                        folderPath = draft.folderPath,
                        filePath = draft.filePath,
                        sizeBytes = draft.sizeBytes,
                        dateModifiedMs = draft.dateModifiedMs,
                        externalLyricsSignature = draft.externalLyricsSignature,
                    ),
                    lyrics = LyricsProbeResult.Complete(LyricsSlots()),
                )
            },
        )

        assertEquals(1, probeCalls)
        assertTrue(result.issues.isEmpty())
        val resolved = result.resolvedObjectsByStableObjectKey.getValue(current.id)
        assertTrue(DeviceShadowCanonicalAspect.TAG_METADATA in resolved.resolvedAspects)
        assertTrue(DeviceShadowCanonicalAspect.REPLAY_GAIN in resolved.resolvedAspects)
        assertTrue(DeviceShadowCanonicalAspect.EXTERNAL_LYRICS in resolved.resolvedAspects)
        assertTrue(DeviceShadowCanonicalAspect.EMBEDDED_LYRICS_PROBE in resolved.resolvedAspects)
        assertTrue(DeviceShadowCanonicalAspect.VIDEO_COVER in resolved.resolvedAspects)
        assertTrue(DeviceShadowCanonicalAspect.MUSIC_VIDEO in resolved.resolvedAspects)
        assertEquals(null, resolved.song.videoCoverUri)
        assertEquals("", resolved.song.videoCoverRevision)
        assertEquals(null, resolved.song.musicVideoUri)
        assertEquals("", resolved.song.musicVideoRevision)
    }

    @Test
    fun lyricsReadFailureKeepsLyricsAspectsUnresolvedAndReportsProbeIssue() {
        val result = execute(
            draftRow = row,
            postRow = row,
            audioProbe = DeviceShadowAudioProbeApi { _, cached ->
                ScannedSong(
                    song = cached!!.copy(title = "metadata still usable"),
                    lyrics = LyricsProbeResult.ReadFailed,
                )
            },
        )

        val resolved = result.resolvedObjectsByStableObjectKey.getValue(current.id)
        assertTrue(DeviceShadowCanonicalAspect.TAG_METADATA in resolved.resolvedAspects)
        assertTrue(DeviceShadowCanonicalAspect.REPLAY_GAIN in resolved.resolvedAspects)
        assertTrue(DeviceShadowCanonicalAspect.VIDEO_COVER in resolved.resolvedAspects)
        assertTrue(DeviceShadowCanonicalAspect.MUSIC_VIDEO in resolved.resolvedAspects)
        assertFalse(DeviceShadowCanonicalAspect.EXTERNAL_LYRICS in resolved.resolvedAspects)
        assertFalse(DeviceShadowCanonicalAspect.EMBEDDED_LYRICS_PROBE in resolved.resolvedAspects)
        assertEquals(DeviceShadowProbeIssueKind.PROBE_FAILED, result.issues.single().kind)
        assertEquals("lyrics-read-failed", result.issues.single().detail)
    }

    @Test
    fun preObservationChangeRejectsBeforeOpeningAudio() {
        var probeCalls = 0
        val result = execute(
            draftRow = row.copy(dateModifiedMs = 3_000L, generationModified = 13L),
            postRow = row,
            audioProbe = DeviceShadowAudioProbeApi { _, _ ->
                probeCalls += 1
                ScannedSong(current)
            },
        )

        assertEquals(0, probeCalls)
        assertTrue(result.resolvedObjectsByStableObjectKey.isEmpty())
        assertEquals(
            DeviceShadowProbeIssueKind.PRE_OBSERVATION_CHANGED,
            result.issues.single().kind,
        )
    }

    @Test
    fun playbackLeaseIsResampledImmediatelyBeforeProbe() {
        var probeCalls = 0
        val result = execute(
            draftRow = row,
            postRow = row,
            playback = LibraryPlaybackIoSnapshot(
                currentStableObjectKey = current.id,
                currentMediaUri = current.mediaUri,
                hasActivePlaybackInstance = true,
            ),
            audioProbe = DeviceShadowAudioProbeApi { _, _ ->
                probeCalls += 1
                ScannedSong(current)
            },
        )

        assertEquals(0, probeCalls)
        assertEquals(setOf(current.id), result.playbackDeferredKeys)
        assertTrue(result.resolvedObjectsByStableObjectKey.isEmpty())
    }

    @Test
    fun postObservationChangeDiscardsProbeResult() {
        var probeCalls = 0
        val result = execute(
            draftRow = row,
            postRow = row.copy(dateModifiedMs = 4_000L, generationModified = 14L),
            audioProbe = DeviceShadowAudioProbeApi { _, cached ->
                probeCalls += 1
                ScannedSong(cached!!.copy(title = "transient result"))
            },
        )

        assertEquals(1, probeCalls)
        assertTrue(result.resolvedObjectsByStableObjectKey.isEmpty())
        assertEquals(
            DeviceShadowProbeIssueKind.POST_OBSERVATION_CHANGED,
            result.issues.single().kind,
        )
    }

    @Test
    fun incompleteLyricsInventoryCannotBecomeAResolvedHeavyProbe() {
        val result = DeviceShadowObjectProbeExecutor.execute(
            probePlan = plan,
            draftApi = DeviceShadowProbeDraftQueryApi { _, _ -> draftObservation(row) },
            revisionApi = { row },
            audioProbeApi = DeviceShadowAudioProbeApi { _, _ -> ScannedSong(current) },
            lyricsInventory = completeLyricsInventory().copy(
                status = DiscoveryPartitionStatus(
                    partitionKey = DiscoveryPartitions.MEDIASTORE_LYRICS_SIDECARS,
                    completeness = DiscoveryCompleteness.PARTIAL,
                    detail = "provider failure",
                ),
            ),
            currentSongs = listOf(current),
            currentSourceIdentity = source,
            currentActivationEpoch = 7L,
            folderCasingPlan = DeviceDeltaFolderCasingPlan.Empty,
            playbackSnapshotProvider = { LibraryPlaybackIoSnapshot.Idle },
        )

        assertTrue(result.resolvedObjectsByStableObjectKey.isEmpty())
        assertEquals(DeviceShadowProbeIssueKind.DRAFT_UNAVAILABLE, result.issues.single().kind)
    }

    @Test
    fun sidecarOnlyRefreshProducesExternalPayloadWithoutOpeningAudio() {
        val lyricsKey = requireNotNull(mediaStoreSongLyricsKey(current))
        val inventory = sidecarInventory(
            lyricsKey = lyricsKey,
            modifiedMs = 4_000L,
        )
        var audioProbeCalls = 0
        var externalProbeCalls = 0
        val sidecarPlan = DeviceAutoProbePlan(
            objects = listOf(
                DeviceAutoProbeObjectPlan(
                    stableObjectKey = current.id,
                    mediaUri = current.mediaUri,
                    reasons = setOf(DeviceAutoProbeReason.EXTERNAL_LYRICS_CHANGED),
                    work = setOf(DeviceAutoProbeWork.EXTERNAL_LYRICS),
                    disposition = DeviceAutoProbeDisposition.READY,
                    observationStamp = null,
                    objectRef = null,
                ),
            ),
            heavyProbeParallelism = 1,
        )

        val result = DeviceShadowObjectProbeExecutor.execute(
            probePlan = sidecarPlan,
            draftApi = DeviceShadowProbeDraftQueryApi { _, _ -> error("must not query audio draft") },
            revisionApi = { error("must not query audio revision") },
            audioProbeApi = DeviceShadowAudioProbeApi { _, _ ->
                audioProbeCalls += 1
                ScannedSong(current)
            },
            lyricsInventory = inventory,
            externalLyricsProbeApi = DeviceShadowExternalLyricsProbeApi { lrcUris, ttmlUris ->
                externalProbeCalls += 1
                assertEquals(1, lrcUris.size)
                assertTrue(ttmlUris.isEmpty())
                LyricsProbeResult.Complete(
                    LyricsSlots(
                        externalLrc = listOf(
                            LyricLine(0, "sidecar"),
                        ).toLyricsDocumentCompat(origin = LyricsOrigin.EXTERNAL),
                    ),
                )
            },
            lyricsInventoryRecheck = { inventory },
            currentSongs = listOf(current),
            currentSourceIdentity = source,
            currentActivationEpoch = 7L,
            folderCasingPlan = DeviceDeltaFolderCasingPlan.Empty,
            playbackSnapshotProvider = { LibraryPlaybackIoSnapshot.Idle },
        )

        assertEquals(0, audioProbeCalls)
        assertEquals(1, externalProbeCalls)
        assertTrue(result.issues.isEmpty())
        assertTrue(result.resolvedObjectsByStableObjectKey.isEmpty())
        val updated = result.resolvedSongsByStableObjectKey.getValue(current.id)
        assertEquals(inventory.signatureFor(lyricsKey), updated.externalLyricsSignature)
        val payload = result.resolvedLyricsByStableObjectKey.getValue(current.id)
        assertEquals(null, payload.slots.embedded)
        assertEquals("sidecar", payload.slots.externalLrc?.lines?.single()?.parts?.single()?.text)
    }

    @Test
    fun sidecarRecheckChangeRejectsPayload() {
        val lyricsKey = requireNotNull(mediaStoreSongLyricsKey(current))
        val before = sidecarInventory(lyricsKey, modifiedMs = 4_000L)
        val after = sidecarInventory(lyricsKey, modifiedMs = 5_000L)
        val sidecarPlan = DeviceAutoProbePlan(
            objects = listOf(
                DeviceAutoProbeObjectPlan(
                    stableObjectKey = current.id,
                    mediaUri = current.mediaUri,
                    reasons = setOf(DeviceAutoProbeReason.EXTERNAL_LYRICS_CHANGED),
                    work = setOf(DeviceAutoProbeWork.EXTERNAL_LYRICS),
                    disposition = DeviceAutoProbeDisposition.READY,
                    observationStamp = null,
                    objectRef = null,
                ),
            ),
            heavyProbeParallelism = 1,
        )

        val result = DeviceShadowObjectProbeExecutor.execute(
            probePlan = sidecarPlan,
            draftApi = DeviceShadowProbeDraftQueryApi { _, _ -> error("unused") },
            revisionApi = { error("unused") },
            audioProbeApi = DeviceShadowAudioProbeApi { _, _ -> error("unused") },
            lyricsInventory = before,
            externalLyricsProbeApi = DeviceShadowExternalLyricsProbeApi { _, _ ->
                LyricsProbeResult.Complete(LyricsSlots())
            },
            lyricsInventoryRecheck = { after },
            currentSongs = listOf(current),
            currentSourceIdentity = source,
            currentActivationEpoch = 7L,
            folderCasingPlan = DeviceDeltaFolderCasingPlan.Empty,
            playbackSnapshotProvider = { LibraryPlaybackIoSnapshot.Idle },
        )

        assertTrue(result.resolvedSongsByStableObjectKey.isEmpty())
        assertTrue(result.resolvedLyricsByStableObjectKey.isEmpty())
        assertEquals(DeviceShadowProbeIssueKind.POST_OBSERVATION_CHANGED, result.issues.single().kind)
    }

    private fun execute(
        draftRow: DeviceDeltaRow,
        postRow: DeviceDeltaRow?,
        playback: LibraryPlaybackIoSnapshot = LibraryPlaybackIoSnapshot.Idle,
        audioProbe: DeviceShadowAudioProbeApi,
    ): DeviceShadowProbeExecutionResult =
        DeviceShadowObjectProbeExecutor.execute(
            probePlan = plan,
            draftApi = DeviceShadowProbeDraftQueryApi { _, _ -> draftObservation(draftRow) },
            revisionApi = { postRow },
            audioProbeApi = audioProbe,
            lyricsInventory = completeLyricsInventory(),
            currentSongs = listOf(current),
            currentSourceIdentity = source,
            currentActivationEpoch = 7L,
            folderCasingPlan = DeviceDeltaFolderCasingPlan.Empty,
            playbackSnapshotProvider = { playback },
        )

    private fun draftObservation(observedRow: DeviceDeltaRow) =
        DeviceShadowProbeDraftObservation(
            row = observedRow,
            draft = TrackDraft(
                mediaStoreId = observedRow.mediaStoreId,
                title = current.title,
                artist = current.artist,
                album = current.album,
                albumId = 1L,
                durationSec = current.durationSec,
                mimeType = observedRow.mimeType,
                displayName = observedRow.displayName,
                sizeBytes = observedRow.sizeBytes,
                bitrateBpsFromStore = current.metadata.bitrateKbps * 1000,
                mediaUri = observedRow.mediaUri,
                coverColorArgb = current.coverColorArgb,
                year = current.year,
                folderPath = observedRow.relativePath.trim('/'),
                filePath = "Music/track.flac",
                externalLyricsSignature = current.externalLyricsSignature,
                dateAddedMs = current.dateAddedMs,
                dateModifiedMs = observedRow.dateModifiedMs,
            ),
        )

    private fun completeLyricsInventory() = MediaStoreLyricsSidecarInventoryResult(
        refsByLyricsKey = emptyMap(),
        status = DiscoveryPartitionStatus(
            partitionKey = DiscoveryPartitions.MEDIASTORE_LYRICS_SIDECARS,
            completeness = DiscoveryCompleteness.COMPLETE,
        ),
    )

    private fun sidecarInventory(
        lyricsKey: String,
        modifiedMs: Long,
    ) = MediaStoreLyricsSidecarInventoryResult(
        refsByLyricsKey = mapOf(
            lyricsKey to listOf(
                ExternalLyricsRef(
                    uri = "content://media/external/file/99",
                    sizeBytes = 64L,
                    dateModifiedMs = modifiedMs,
                    extension = "lrc",
                ),
            ),
        ),
        status = DiscoveryPartitionStatus(
            partitionKey = DiscoveryPartitions.MEDIASTORE_LYRICS_SIDECARS,
            completeness = DiscoveryCompleteness.COMPLETE,
        ),
    )
}
