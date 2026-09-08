package com.mica.music.data.scanner

import com.mica.music.data.Song
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDeltaFolderCasingPlannerTest {

    private val sameDirectory = MediaStoreDirectoryIdentity(device = 1L, inode = 100L)
    private val otherDirectory = MediaStoreDirectoryIdentity(device = 1L, inode = 200L)

    @Test
    fun samePhysicalDirectoryUsesFullScannerMajorityCasing() {
        val current = listOf(
            song("ms_1", "QQMusic/Song", "a.flac"),
            song("ms_2", "QQMusic/Song", "b.flac"),
        )
        val candidate = candidate(
            stableObjectKey = "ms_3",
            existingSongId = null,
            folderPath = "qqmusic/song",
            displayName = "c.flac",
        )

        val plan = DeviceDeltaFolderCasingPlanner.plan(
            candidates = plan(candidate),
            currentSongs = current,
            identityResolver = DeviceFolderIdentityResolver { sameDirectory },
        )

        val decision = plan.decisions.single()
        assertEquals("QQMusic/Song", decision.effectiveFolderPath)
        assertTrue(decision.reconciled)
        assertTrue(decision.identityVerified)
        assertEquals(0, plan.unresolvedCaseCollisions)
    }

    @Test
    fun distinctPhysicalDirectoriesWithSameCaseFoldAreNeverMerged() {
        val current = listOf(song("ms_1", "QQMusic/Song", "a.flac"))
        val candidate = candidate(
            stableObjectKey = "ms_2",
            existingSongId = null,
            folderPath = "qqmusic/song",
            displayName = "b.flac",
        )

        val plan = DeviceDeltaFolderCasingPlanner.plan(
            candidates = plan(candidate),
            currentSongs = current,
            identityResolver = DeviceFolderIdentityResolver { probe ->
                if (probe.mediaUri.endsWith("/2")) otherDirectory else sameDirectory
            },
        )

        val decision = plan.decisions.single()
        assertEquals("qqmusic/song", decision.effectiveFolderPath)
        assertFalse(decision.reconciled)
        assertTrue(decision.identityVerified)
        assertEquals(1, plan.distinctPhysicalCaseCollisions)
    }

    @Test
    fun unresolvedPhysicalIdentityKeepsObservedCasingConservatively() {
        val current = listOf(song("ms_1", "QQMusic/Song", "a.flac"))
        val candidate = candidate(
            stableObjectKey = "ms_2",
            existingSongId = null,
            folderPath = "qqmusic/song",
            displayName = "b.flac",
        )

        val plan = DeviceDeltaFolderCasingPlanner.plan(
            candidates = plan(candidate),
            currentSongs = current,
            identityResolver = DeviceFolderIdentityResolver { null },
        )

        val decision = plan.decisions.single()
        assertEquals("qqmusic/song", decision.effectiveFolderPath)
        assertFalse(decision.reconciled)
        assertFalse(decision.identityVerified)
        assertEquals(1, plan.unresolvedCaseCollisions)
    }

    @Test
    fun existingObjectOldCasingIsReplacedNotDoubleCounted() {
        val current = listOf(song("ms_1", "QQMusic/Song", "a.flac"))
        val candidate = candidate(
            stableObjectKey = "ms_1",
            existingSongId = "ms_1",
            folderPath = "qqmusic/song",
            displayName = "a.flac",
        )

        val plan = DeviceDeltaFolderCasingPlanner.plan(
            candidates = plan(candidate),
            currentSongs = current,
            identityResolver = DeviceFolderIdentityResolver { sameDirectory },
        )

        val decision = plan.decisions.single()
        // An isolated Full scan would now see only the provider's new spelling for this directory.
        assertEquals("qqmusic/song", decision.effectiveFolderPath)
        assertFalse(decision.reconciled)
        assertFalse(decision.identityVerified)
    }

    @Test
    fun existingObjectJoinsUnchangedFolderPopulationBeforeChoosingCasing() {
        val current = listOf(
            song("ms_1", "QQMusic/Song", "a.flac"),
            song("ms_2", "QQMusic/Song", "b.flac"),
            song("ms_3", "QQMusic/Song", "c.flac"),
        )
        val candidate = candidate(
            stableObjectKey = "ms_1",
            existingSongId = "ms_1",
            folderPath = "qqmusic/song",
            displayName = "a.flac",
        )

        val plan = DeviceDeltaFolderCasingPlanner.plan(
            candidates = plan(candidate),
            currentSongs = current,
            identityResolver = DeviceFolderIdentityResolver { sameDirectory },
        )

        assertEquals("QQMusic/Song", plan.effectiveFolderPath("ms_1"))
        assertTrue(plan.decisions.single().reconciled)
    }

    private fun song(
        id: String,
        folderPath: String,
        fileName: String,
    ): Song = SongFixtures.song(id).copy(
        mediaUri = "content://media/external_primary/audio/${id.removePrefix("ms_")}",
        folderPath = folderPath,
        fileName = fileName,
        filePath = "$folderPath/$fileName",
        sizeBytes = 100L,
    )

    private fun candidate(
        stableObjectKey: String,
        existingSongId: String?,
        folderPath: String,
        displayName: String,
    ): DeviceAudioDeltaCandidate {
        val id = stableObjectKey.removePrefix("ms_").toLong()
        val row = DeviceDeltaRow(
            channel = DeviceDeltaChannel.AUDIO,
            volumeName = "external_primary",
            mediaStoreId = id,
            mediaUri = "content://media/external_primary/audio/$id",
            displayName = displayName,
            mimeType = "audio/flac",
            relativePath = "$folderPath/",
            sizeBytes = 100L,
            dateModifiedMs = 2_000L,
            generationAdded = 10L,
            generationModified = 12L,
            eligibility = LibraryEligibility.ELIGIBLE,
        )
        return DeviceAudioDeltaCandidate(
            canonicalStableObjectKey = stableObjectKey,
            observedStableObjectKeys = setOf(stableObjectKey),
            primaryRow = row,
            duplicateKey = mediaStoreDeltaRowDuplicateKey(row),
            lyricsKey = mediaStoreDeltaRowLyricsKey(row),
            existingSongId = existingSongId,
            sidecarChanged = false,
        )
    }

    private fun plan(candidate: DeviceAudioDeltaCandidate) = DeviceDeltaCandidatePlan(
        audioCandidates = listOf(candidate),
        sidecarCandidates = emptyList(),
        contradictions = emptyList(),
    )
}
