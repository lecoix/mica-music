package com.mica.music.data.library

import com.mica.music.data.Song
import com.mica.music.data.scanner.DeviceAudioDeltaCandidate
import com.mica.music.data.scanner.DeviceDeltaCandidatePlan
import com.mica.music.data.scanner.DeviceDeltaChannel
import com.mica.music.data.scanner.DeviceDeltaRow
import com.mica.music.data.scanner.DeviceSidecarDeltaCandidate
import com.mica.music.data.scanner.LibraryEligibility
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAutoProbePlannerTest {

    private val source = SourceIdentityKey.device()

    @Test
    fun existingAudioRevisionProducesSingleReadyHeavyProbeAtConservativeParallelism() {
        val current = song("ms_1")
        val plan = DeviceAutoProbePlanner.plan(
            candidates = candidatePlan(
                audio = listOf(audioCandidate(current, generation = 8L)),
            ),
            currentSongs = listOf(current),
            retryItems = emptyList(),
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = 100L,
            playback = LibraryPlaybackIoSnapshot.Idle,
        )

        assertEquals(1, plan.ready.size)
        assertTrue(plan.deferred.isEmpty())
        assertEquals(1, plan.heavyProbeParallelism)
        assertEquals(
            setOf(DeviceAutoProbeReason.MEDIASTORE_REVISION_CHANGED),
            plan.ready.single().reasons,
        )
        assertEquals(
            setOf(DeviceAutoProbeWork.AUDIO_METADATA),
            plan.ready.single().work,
        )
        val stamp = requireNotNull(plan.ready.single().observationStamp)
        assertEquals(source, stamp.sourceIdentity)
        assertEquals(3L, stamp.activationEpoch)
        assertEquals(current.id, stamp.stableObjectKey)
        assertEquals(8L, stamp.providerGeneration)
        assertTrue(stamp.fingerprint.orEmpty().contains("AUDIO"))
    }

    @Test
    fun sidecarOnlyChangeStaysLightweightEvenForCurrentPlaybackObject() {
        val current = song("ms_2")
        val sidecar = DeviceSidecarDeltaCandidate(
            lyricsKey = "music/song",
            rows = listOf(sidecarRow()),
            affectedSongIds = setOf(current.id),
            affectedAudioStableObjectKeys = emptySet(),
        )

        val plan = DeviceAutoProbePlanner.plan(
            candidates = candidatePlan(sidecars = listOf(sidecar)),
            currentSongs = listOf(current),
            retryItems = emptyList(),
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = 100L,
            playback = LibraryPlaybackIoSnapshot(
                currentStableObjectKey = current.id,
                currentMediaUri = current.mediaUri,
                hasActivePlaybackInstance = true,
            ),
        )

        assertEquals(1, plan.ready.size)
        assertTrue(plan.deferred.isEmpty())
        assertFalse(plan.ready.single().requiresHeavyProbe)
        assertEquals(
            setOf(DeviceAutoProbeWork.EXTERNAL_LYRICS),
            plan.ready.single().work,
        )
        assertEquals(null, plan.ready.single().observationStamp)
    }

    @Test
    fun currentPlaybackAudioCandidateDefersHeavyProbeWhilePausedOrBufferingLeaseExists() {
        val current = song("ms_3")
        val plan = DeviceAutoProbePlanner.plan(
            candidates = candidatePlan(
                audio = listOf(audioCandidate(current, generation = 9L)),
            ),
            currentSongs = listOf(current),
            retryItems = emptyList(),
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = 100L,
            playback = LibraryPlaybackIoSnapshot(
                currentStableObjectKey = current.id,
                currentMediaUri = current.mediaUri,
                hasActivePlaybackInstance = true,
            ),
        )

        assertTrue(plan.ready.isEmpty())
        assertEquals(1, plan.deferred.size)
        assertEquals(
            DeviceAutoProbeDisposition.DEFER_UNTIL_PLAYBACK_RELEASE,
            plan.deferred.single().disposition,
        )
    }

    @Test
    fun serializedSourceDefersEveryHeavyProbeDuringActivePlayback() {
        val first = song("ms_4")
        val second = song("ms_5")
        val plan = DeviceAutoProbePlanner.plan(
            candidates = candidatePlan(
                audio = listOf(
                    audioCandidate(first, generation = 10L),
                    audioCandidate(second, generation = 10L),
                ),
            ),
            currentSongs = listOf(first, second),
            retryItems = emptyList(),
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = 100L,
            playback = LibraryPlaybackIoSnapshot(
                currentStableObjectKey = first.id,
                currentMediaUri = first.mediaUri,
                hasActivePlaybackInstance = true,
                sourceSerializesHeavyIo = true,
            ),
        )

        assertTrue(plan.ready.isEmpty())
        assertEquals(setOf(first.id, second.id), plan.deferred.mapTo(linkedSetOf()) { it.stableObjectKey })
    }

    @Test
    fun dueRetryLedgerItemCanCreateProbeWithoutNewMediaStoreCandidate() {
        val current = song("ms_6")
        val plan = DeviceAutoProbePlanner.plan(
            candidates = candidatePlan(),
            currentSongs = listOf(current),
            retryItems = listOf(
                retry(
                    stableObjectKey = current.id,
                    nextRetryAtMs = 99L,
                    activationEpoch = 3L,
                ),
            ),
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = 100L,
            playback = LibraryPlaybackIoSnapshot.Idle,
        )

        assertTrue(plan.ready.isEmpty())
        assertEquals(1, plan.requeryRequired.size)
        val retryWork = plan.requeryRequired.single()
        assertEquals(
            setOf(DeviceAutoProbeReason.RETRY_LEDGER),
            retryWork.reasons,
        )
        assertEquals(
            setOf(DeviceAutoProbeWork.AUDIO_METADATA),
            retryWork.work,
        )
        val stamp = requireNotNull(retryWork.observationStamp)
        assertEquals("rev", stamp.fingerprint)
        assertEquals(3L, stamp.activationEpoch)
        assertEquals(null, stamp.providerGeneration)
        assertEquals(null, retryWork.objectRef)
        assertEquals(
            DeviceAutoProbeDisposition.REQUERY_OBJECT_REVISION,
            retryWork.disposition,
        )
    }

    @Test
    fun reobservedDueRetryBecomesReadyWithFreshObjectRevision() {
        val current = song("ms_61")
        val freshRow = audioCandidate(current, generation = 12L).primaryRow
        val plan = DeviceAutoProbePlanner.plan(
            candidates = candidatePlan(),
            currentSongs = listOf(current),
            retryItems = listOf(
                retry(
                    stableObjectKey = current.id,
                    nextRetryAtMs = 99L,
                    activationEpoch = 3L,
                ),
            ),
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = 100L,
            playback = LibraryPlaybackIoSnapshot.Idle,
            retryObservationRowsByStableObjectKey = mapOf(current.id to freshRow),
        )

        assertEquals(1, plan.ready.size)
        assertTrue(plan.requeryRequired.isEmpty())
        val retryWork = plan.ready.single()
        assertEquals(freshRow.mediaUri, retryWork.mediaUri)
        assertEquals(DeviceDeltaChannel.AUDIO, retryWork.objectRef?.channel)
        assertEquals(12L, retryWork.observationStamp?.providerGeneration)
        assertTrue(retryWork.observationStamp?.fingerprint.orEmpty().contains("AUDIO"))
    }

    @Test
    fun futureWrongSourceAndStaleActivationRetriesDoNotBecomeAutoProbeWork() {
        val current = song("ms_7")
        val plan = DeviceAutoProbePlanner.plan(
            candidates = candidatePlan(),
            currentSongs = listOf(current),
            retryItems = listOf(
                retry(current.id, nextRetryAtMs = 101L, activationEpoch = 3L),
                retry(current.id, nextRetryAtMs = 99L, activationEpoch = 2L),
                retry(
                    current.id,
                    nextRetryAtMs = 99L,
                    activationEpoch = 3L,
                    sourceIdentity = SourceIdentityKey.folder("content://tree/other"),
                ),
            ),
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = 100L,
            playback = LibraryPlaybackIoSnapshot.Idle,
        )

        assertTrue(plan.isNoOp)
    }

    @Test
    fun filteredOrTrashedCandidateNeverCreatesHeavyProbe() {
        val current = song("ms_8")
        val filtered = audioCandidate(
            current = current,
            generation = 11L,
            eligibility = LibraryEligibility.FILTERED_OUT,
        )

        val plan = DeviceAutoProbePlanner.plan(
            candidates = candidatePlan(audio = listOf(filtered)),
            currentSongs = listOf(current),
            retryItems = emptyList(),
            sourceIdentity = source,
            activationEpoch = 3L,
            nowMs = 100L,
            playback = LibraryPlaybackIoSnapshot.Idle,
        )

        assertTrue(plan.isNoOp)
    }

    private fun song(id: String): Song = SongFixtures.song(id).copy(
        mediaUri = "content://media/external/audio/media/${id.removePrefix("ms_")}",
        fileName = "song.flac",
        folderPath = "Music",
        filePath = "Music/song.flac",
        sizeBytes = 100L,
        dateModifiedMs = 1_000L,
    )

    private fun audioCandidate(
        current: Song,
        generation: Long,
        eligibility: LibraryEligibility = LibraryEligibility.ELIGIBLE,
    ): DeviceAudioDeltaCandidate = DeviceAudioDeltaCandidate(
        canonicalStableObjectKey = current.id,
        observedStableObjectKeys = setOf(current.id),
        primaryRow = DeviceDeltaRow(
            channel = DeviceDeltaChannel.AUDIO,
            volumeName = "external_primary",
            mediaStoreId = current.id.removePrefix("ms_").toLong(),
            mediaUri = current.mediaUri,
            displayName = current.fileName,
            mimeType = "audio/flac",
            relativePath = "Music/",
            sizeBytes = current.sizeBytes,
            dateModifiedMs = current.dateModifiedMs + generation,
            generationAdded = generation,
            generationModified = generation,
            eligibility = eligibility,
        ),
        duplicateKey = "music/song.flac\u0001${current.sizeBytes}",
        lyricsKey = "music/song",
        existingSongId = current.id,
        sidecarChanged = false,
    )

    private fun sidecarRow(): DeviceDeltaRow = DeviceDeltaRow(
        channel = DeviceDeltaChannel.LYRICS_SIDECAR,
        volumeName = "external_primary",
        mediaStoreId = 100L,
        mediaUri = "content://media/external_primary/file/100",
        displayName = "song.lrc",
        mimeType = "text/plain",
        relativePath = "Music/",
        sizeBytes = 20L,
        dateModifiedMs = 2_000L,
        generationAdded = 1L,
        generationModified = 2L,
        eligibility = LibraryEligibility.ELIGIBLE,
    )

    private fun candidatePlan(
        audio: List<DeviceAudioDeltaCandidate> = emptyList(),
        sidecars: List<DeviceSidecarDeltaCandidate> = emptyList(),
    ) = DeviceDeltaCandidatePlan(
        audioCandidates = audio,
        sidecarCandidates = sidecars,
        contradictions = emptyList(),
    )

    private fun retry(
        stableObjectKey: String,
        nextRetryAtMs: Long,
        activationEpoch: Long?,
        sourceIdentity: SourceIdentityKey = source,
    ) = LibraryRetryItem(
        sourceIdentity = sourceIdentity,
        retryKey = "$stableObjectKey@retry",
        activationEpoch = activationEpoch,
        stableObjectKey = stableObjectKey,
        observedFingerprint = "rev",
        retryKind = LibraryRetryKind.OBJECT_PROBE,
        failureKind = "probe",
        attemptCount = 1,
        nextRetryAtMs = nextRetryAtMs,
    )
}
