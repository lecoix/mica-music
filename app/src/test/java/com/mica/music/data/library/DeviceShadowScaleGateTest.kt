package com.mica.music.data.library

import com.mica.music.data.Song
import com.mica.music.data.scanner.DeviceAudioDeltaCandidate
import com.mica.music.data.scanner.DeviceDeltaCandidatePlan
import com.mica.music.data.scanner.DeviceDeltaChannel
import com.mica.music.data.scanner.DeviceDeltaFolderCasingPlan
import com.mica.music.data.scanner.DeviceDeltaRow
import com.mica.music.data.scanner.DeviceLyricsSidecarDiff
import com.mica.music.data.scanner.DeviceShadowCanonicalCatalog
import com.mica.music.data.scanner.DeviceShadowCanonicalContext
import com.mica.music.data.scanner.LibraryEligibility
import com.mica.music.data.scanner.mediaStoreDeltaRowDuplicateKey
import com.mica.music.data.scanner.mediaStoreDeltaRowLyricsKey
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceShadowScaleGateTest {

    @Test(timeout = 8_000L)
    fun tenThousandCatalogWithThreeDirtyObjectsKeepsProbeAndProjectionWorkBounded() {
        val songs = List(10_000) { index ->
            SongFixtures.song("ms_${index + 1}").copy(
                mediaUri = "content://media/external_primary/audio/${index + 1}",
                fileName = "track-${index + 1}.flac",
                folderPath = "Music/Album-${index / 20}",
                filePath = "Music/Album-${index / 20}/track-${index + 1}.flac",
                sizeBytes = 1_000L + index,
                dateModifiedMs = 1_000L,
            )
        }
        val dirtyIndexes = listOf(99, 4_999, 9_999)
        val candidates = DeviceDeltaCandidatePlan(
            audioCandidates = dirtyIndexes.map { index ->
                val song = songs[index]
                candidate(song, generation = 20L + index)
            },
            sidecarCandidates = emptyList(),
            contradictions = emptyList(),
        )
        val playbackSong = songs[dirtyIndexes[1]]

        val probePlan = DeviceAutoProbePlanner.plan(
            candidates = candidates,
            currentSongs = songs,
            retryItems = emptyList(),
            sourceIdentity = SourceIdentityKey.device(),
            activationEpoch = 7L,
            nowMs = 100_000L,
            playback = LibraryPlaybackIoSnapshot(
                currentStableObjectKey = playbackSong.id,
                currentMediaUri = playbackSong.mediaUri,
                hasActivePlaybackInstance = true,
            ),
        )

        assertEquals(2, probePlan.ready.size)
        assertEquals(1, probePlan.deferred.size)
        assertEquals(playbackSong.id, probePlan.deferred.single().stableObjectKey)
        assertEquals(1, probePlan.heavyProbeParallelism)
        assertEquals(
            dirtyIndexes.mapTo(linkedSetOf()) { songs[it].id },
            probePlan.objects.mapTo(linkedSetOf()) { it.stableObjectKey },
        )

        val context = DeviceShadowCanonicalContext(
            sourceIdentityStorageKey = SourceIdentityKey.device().storageKey(),
            activationEpoch = 7L,
            configFingerprint = "cfg-10k",
            providerIdentityDomain = "mediastore:external_primary:v1",
        )
        val baseline = DeviceShadowCanonicalCatalog.snapshot(songs, context)
        val projection = DeviceShadowCanonicalProjector.project(
            baseline = baseline,
            candidates = candidates,
            lyricsDiff = DeviceLyricsSidecarDiff(
                changes = emptyList(),
                unverifiableSongIds = emptySet(),
            ),
            membershipPlan = AutoSyncMembershipPlan.Apply(emptyList()),
            folderCasingPlan = DeviceDeltaFolderCasingPlan.Empty,
        )

        assertEquals(10_000, projection.snapshot.songsByStableObjectKey.size)
        assertEquals(
            dirtyIndexes.mapTo(linkedSetOf()) { songs[it].id },
            projection.unresolvedAspectsByStableObjectKey.keys,
        )
        assertTrue(
            projection.snapshot.songsByStableObjectKey.values.none { canonical ->
                // Canonical shadow rows intentionally have no lyrics payload field; signatures only.
                canonical.externalLyricsSignature.length > 1_000_000
            },
        )
    }

    @Test(timeout = 8_000L)
    fun thousandNewObjectsStayOneBoundedDeltaWorksetAgainstTenThousandCatalog() {
        val existing = List(10_000) { index ->
            SongFixtures.song("ms_${index + 1}").copy(
                mediaUri = "content://media/external_primary/audio/${index + 1}",
                fileName = "existing-${index + 1}.flac",
                folderPath = "Music/Existing",
                filePath = "Music/Existing/existing-${index + 1}.flac",
                sizeBytes = 1_000L + index,
                dateModifiedMs = 1_000L,
            )
        }
        val added = List(1_000) { offset ->
            val id = 10_001 + offset
            SongFixtures.song("ms_$id").copy(
                mediaUri = "content://media/external_primary/audio/$id",
                fileName = "added-$id.flac",
                folderPath = "Music/Batch",
                filePath = "Music/Batch/added-$id.flac",
                sizeBytes = 20_000L + offset,
                dateModifiedMs = 3_000L,
            )
        }
        val candidates = DeviceDeltaCandidatePlan(
            audioCandidates = added.map { song ->
                val id = song.id.removePrefix("ms_").toLong()
                val row = DeviceDeltaRow(
                    channel = DeviceDeltaChannel.AUDIO,
                    volumeName = "external_primary",
                    mediaStoreId = id,
                    mediaUri = song.mediaUri,
                    displayName = song.fileName,
                    mimeType = "audio/flac",
                    relativePath = "Music/Batch/",
                    sizeBytes = song.sizeBytes,
                    dateModifiedMs = song.dateModifiedMs,
                    generationAdded = 30L,
                    generationModified = 30L,
                    eligibility = LibraryEligibility.ELIGIBLE,
                )
                DeviceAudioDeltaCandidate(
                    canonicalStableObjectKey = song.id,
                    observedStableObjectKeys = setOf(row.stableObjectKey),
                    primaryRow = row,
                    duplicateKey = mediaStoreDeltaRowDuplicateKey(row),
                    lyricsKey = mediaStoreDeltaRowLyricsKey(row),
                    existingSongId = null,
                    sidecarChanged = false,
                )
            },
            sidecarCandidates = emptyList(),
            contradictions = emptyList(),
        )

        val probePlan = DeviceAutoProbePlanner.plan(
            candidates = candidates,
            currentSongs = existing,
            retryItems = emptyList(),
            sourceIdentity = SourceIdentityKey.device(),
            activationEpoch = 7L,
            nowMs = 100_000L,
            playback = LibraryPlaybackIoSnapshot.Idle,
        )

        assertEquals(1_000, probePlan.objects.size)
        assertEquals(1_000, probePlan.ready.size)
        assertTrue(probePlan.deferred.isEmpty())
        assertEquals(1, probePlan.heavyProbeParallelism)

        val context = DeviceShadowCanonicalContext(
            sourceIdentityStorageKey = SourceIdentityKey.device().storageKey(),
            activationEpoch = 7L,
            configFingerprint = "cfg-1000-copy",
            providerIdentityDomain = "mediastore:external_primary:v1",
        )
        val baseline = DeviceShadowCanonicalCatalog.snapshot(existing, context)
        val resolvedAdded = DeviceShadowCanonicalCatalog.snapshot(added, context)
            .songsByStableObjectKey
            .mapValues { (_, canonical) ->
                DeviceShadowResolvedCanonicalObject(
                    song = canonical,
                    resolvedAspects =
                        com.mica.music.data.scanner.DeviceShadowCanonicalAspect.entries.toSet(),
                )
            }
        val projection = DeviceShadowCanonicalProjector.project(
            baseline = baseline,
            candidates = candidates,
            lyricsDiff = DeviceLyricsSidecarDiff(
                changes = emptyList(),
                unverifiableSongIds = emptySet(),
            ),
            membershipPlan = AutoSyncMembershipPlan.Apply(emptyList()),
            folderCasingPlan = DeviceDeltaFolderCasingPlan.Empty,
            resolvedObjectsByStableObjectKey = resolvedAdded,
        )

        assertEquals(11_000, projection.snapshot.songsByStableObjectKey.size)
        assertTrue(projection.unresolvedAspectsByStableObjectKey.isEmpty())
        assertTrue(projection.quarantinedMembershipKeys.isEmpty())
        assertTrue(projection.fullyResolved)
    }

    private fun candidate(
        song: Song,
        generation: Long,
    ): DeviceAudioDeltaCandidate {
        val row = DeviceDeltaRow(
            channel = DeviceDeltaChannel.AUDIO,
            volumeName = "external_primary",
            mediaStoreId = song.id.removePrefix("ms_").toLong(),
            mediaUri = song.mediaUri,
            displayName = song.fileName,
            mimeType = "audio/flac",
            relativePath = "${song.folderPath}/",
            sizeBytes = song.sizeBytes,
            dateModifiedMs = song.dateModifiedMs + generation,
            generationAdded = generation,
            generationModified = generation,
            eligibility = LibraryEligibility.ELIGIBLE,
        )
        return DeviceAudioDeltaCandidate(
            canonicalStableObjectKey = song.id,
            observedStableObjectKeys = setOf(row.stableObjectKey),
            primaryRow = row,
            duplicateKey = mediaStoreDeltaRowDuplicateKey(row),
            lyricsKey = mediaStoreDeltaRowLyricsKey(row),
            existingSongId = song.id,
            sidecarChanged = false,
        )
    }
}
