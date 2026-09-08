package com.mica.music.data.library

import com.mica.music.data.scanner.DeviceDeltaChannel
import com.mica.music.data.scanner.DeviceDeltaRow
import com.mica.music.data.scanner.DeviceObjectRevisionQueryApi
import com.mica.music.data.scanner.LibraryEligibility
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRetryObservationResolverTest {
    private val source = SourceIdentityKey.device()

    @Test
    fun songMediaUriOwnsVolumeAndChannelIdentity() {
        val audio = SongFixtures.song("ms_42").copy(
            mediaUri = "content://media/0123-4567/audio/media/42",
        )
        val file = SongFixtures.song("ms_77").copy(
            mediaUri = "content://media/external/file/77",
        )

        val audioRef = requireNotNull(DeviceRetryObservationResolver.deviceObjectRefFromSong(audio))
        val fileRef = requireNotNull(DeviceRetryObservationResolver.deviceObjectRefFromSong(file))

        assertEquals(DeviceDeltaChannel.AUDIO, audioRef.channel)
        assertEquals("0123-4567", audioRef.volumeName)
        assertEquals(42L, audioRef.mediaStoreId)
        assertEquals(DeviceDeltaChannel.FILES_FALLBACK, fileRef.channel)
        assertEquals("external", fileRef.volumeName)
        assertEquals(77L, fileRef.mediaStoreId)
    }

    @Test
    fun dueRetryRequeriesExactCurrentObject() {
        val song = SongFixtures.song("ms_42").copy(
            mediaUri = "content://media/external_primary/audio/media/42",
        )
        val retry = LibraryRetryItem(
            sourceIdentity = source,
            retryKey = "retry",
            activationEpoch = 7L,
            stableObjectKey = song.id,
            observedFingerprint = "old",
            retryKind = LibraryRetryKind.OBJECT_PROBE,
            failureKind = "PROBE_FAILED",
            attemptCount = 1,
            nextRetryAtMs = 100L,
        )
        var queriedVolume = ""
        val result = DeviceRetryObservationResolver.resolve(
            currentSongs = listOf(song),
            retryItems = listOf(retry),
            nowMs = 100L,
            queryApi = DeviceObjectRevisionQueryApi { ref ->
                queriedVolume = ref.volumeName
                DeviceDeltaRow(
                    channel = ref.channel,
                    volumeName = ref.volumeName,
                    mediaStoreId = ref.mediaStoreId,
                    mediaUri = song.mediaUri,
                    displayName = song.fileName,
                    mimeType = "audio/flac",
                    relativePath = song.folderPath,
                    sizeBytes = song.sizeBytes,
                    dateModifiedMs = song.dateModifiedMs + 1L,
                    generationAdded = 1L,
                    generationModified = 2L,
                    eligibility = LibraryEligibility.ELIGIBLE,
                )
            },
        )

        assertEquals("external_primary", queriedVolume)
        assertTrue(result.missingStableObjectKeys.isEmpty())
        assertTrue(result.unavailableStableObjectKeys.isEmpty())
        assertEquals(2L, result.observedRowsByStableObjectKey.getValue(song.id).generationModified)
    }

    @Test
    fun futureRetryIsNotQueried() {
        val song = SongFixtures.song("ms_42").copy(
            mediaUri = "content://media/external_primary/audio/media/42",
        )
        val retry = LibraryRetryItem(
            sourceIdentity = source,
            retryKey = "retry",
            activationEpoch = 7L,
            stableObjectKey = song.id,
            observedFingerprint = "old",
            retryKind = LibraryRetryKind.OBJECT_PROBE,
            failureKind = "PROBE_FAILED",
            attemptCount = 1,
            nextRetryAtMs = 101L,
        )
        var queries = 0

        val result = DeviceRetryObservationResolver.resolve(
            currentSongs = listOf(song),
            retryItems = listOf(retry),
            nowMs = 100L,
            queryApi = DeviceObjectRevisionQueryApi {
                queries += 1
                null
            },
        )

        assertEquals(0, queries)
        assertTrue(result.observedRowsByStableObjectKey.isEmpty())
    }
}
