package com.mica.music.data.library

import com.mica.music.data.LyricsDocument
import com.mica.music.data.ReplayGainTags
import com.mica.music.data.scanner.DeviceAudioDeltaCandidate
import com.mica.music.data.scanner.DeviceDeltaCandidatePlan
import com.mica.music.data.scanner.DeviceDeltaChannel
import com.mica.music.data.scanner.DeviceDeltaRow
import com.mica.music.data.scanner.DeviceLyricsSidecarDiff
import com.mica.music.data.scanner.DeviceShadowCanonicalAspect
import com.mica.music.data.scanner.DeviceShadowCanonicalCatalog
import com.mica.music.data.scanner.DeviceShadowCanonicalContext
import com.mica.music.data.scanner.DeviceShadowCanonicalCoverageResult
import com.mica.music.data.scanner.LibraryEligibility
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceShadowCanonicalCoverageTrackerTest {

    private val context = DeviceShadowCanonicalContext(
        sourceIdentityStorageKey = SourceIdentityKey.device().storageKey(),
        activationEpoch = 7L,
        configFingerprint = "cfg",
        providerIdentityDomain = "mediastore:external_primary:v1",
    )

    @Test
    fun canonicalCatalogIgnoresRuntimeAndPresentationOnlyFields() {
        val base = song("ms_1")
        val runtimeOnly = base.copy(
            playCount = 12,
            totalListenSeconds = 345L,
            lastPlayedAtMs = 999L,
            coverColorArgb = 0x12345678,
            lyricsDocument = LyricsDocument(),
            lyricsLoaded = !base.lyricsLoaded,
        )

        val diff = DeviceShadowCanonicalCatalog.diff(
            DeviceShadowCanonicalCatalog.snapshot(listOf(base), context),
            DeviceShadowCanonicalCatalog.snapshot(listOf(runtimeOnly), context),
        )

        assertTrue(diff.changes.isEmpty())
    }

    @Test
    fun canonicalCatalogClassifiesMembershipAndScannerFactsSeparately() {
        val base = song("ms_2")
        val changed = base.copy(
            dateAddedMs = base.dateAddedMs + 1_000L,
            dateModifiedMs = base.dateModifiedMs + 2_000L,
            title = "new title",
            replayGain = ReplayGainTags(trackGainDb = -4.5f),
            externalLyricsSignature = "lrc:r2",
        )

        val change = DeviceShadowCanonicalCatalog.diff(
            DeviceShadowCanonicalCatalog.snapshot(listOf(base), context),
            DeviceShadowCanonicalCatalog.snapshot(listOf(changed), context),
        ).changes.single()

        assertEquals(
            setOf(
                DeviceShadowCanonicalAspect.MEMBERSHIP,
                DeviceShadowCanonicalAspect.FILE_FINGERPRINT,
                DeviceShadowCanonicalAspect.TAG_METADATA,
                DeviceShadowCanonicalAspect.EXTERNAL_LYRICS,
                DeviceShadowCanonicalAspect.REPLAY_GAIN,
            ),
            change.aspects,
        )
    }

    @Test
    fun uncoveredFullChangeIsReportedInsteadOfSilentlyPassing() {
        val tracker = DeviceShadowCanonicalCoverageTracker()
        val base = song("ms_3")
        val baseline = tracker.acceptFullSnapshot(
            DeviceShadowCanonicalCatalog.snapshot(listOf(base), context),
        )
        assertTrue(baseline is DeviceShadowCanonicalCoverageResult.BaselineEstablished)

        val compared = tracker.acceptFullSnapshot(
            DeviceShadowCanonicalCatalog.snapshot(
                listOf(base.copy(title = "changed without shadow event")),
                context,
            ),
        ) as DeviceShadowCanonicalCoverageResult.Compared

        assertFalse(compared.fullyCovered)
        assertEquals(
            setOf(DeviceShadowCanonicalAspect.TAG_METADATA),
            compared.uncoveredAspectsByStableObjectKey.getValue(base.id),
        )
    }

    @Test
    fun audioDeltaCoversScannerOwnedFullChangesButNotDateAddedMutation() {
        val tracker = DeviceShadowCanonicalCoverageTracker()
        val base = song("ms_4")
        tracker.acceptFullSnapshot(DeviceShadowCanonicalCatalog.snapshot(listOf(base), context))

        tracker.recordDelta(
            candidates = DeviceDeltaCandidatePlan(
                audioCandidates = listOf(
                    DeviceAudioDeltaCandidate(
                        canonicalStableObjectKey = base.id,
                        observedStableObjectKeys = setOf(base.id),
                        primaryRow = row(id = 4L),
                        duplicateKey = "Music/a.flac\u0001100",
                        lyricsKey = null,
                        existingSongId = base.id,
                        sidecarChanged = false,
                    ),
                ),
                sidecarCandidates = emptyList(),
                contradictions = emptyList(),
            ),
            lyricsDiff = DeviceLyricsSidecarDiff(
                changes = emptyList(),
                unverifiableSongIds = emptySet(),
            ),
            membershipPlan = AutoSyncMembershipPlan.Apply(emptyList()),
        )

        val scannerOwned = tracker.acceptFullSnapshot(
            DeviceShadowCanonicalCatalog.snapshot(
                listOf(
                    base.copy(
                        title = "updated",
                        dateModifiedMs = base.dateModifiedMs + 10L,
                        replayGain = ReplayGainTags(trackGainDb = -2f),
                    ),
                ),
                context,
            ),
        ) as DeviceShadowCanonicalCoverageResult.Compared
        assertTrue(scannerOwned.fullyCovered)

        tracker.recordDelta(
            candidates = DeviceDeltaCandidatePlan(
                audioCandidates = listOf(
                    DeviceAudioDeltaCandidate(
                        canonicalStableObjectKey = base.id,
                        observedStableObjectKeys = setOf(base.id),
                        primaryRow = row(id = 4L),
                        duplicateKey = "Music/a.flac\u0001100",
                        lyricsKey = null,
                        existingSongId = base.id,
                        sidecarChanged = false,
                    ),
                ),
                sidecarCandidates = emptyList(),
                contradictions = emptyList(),
            ),
            lyricsDiff = DeviceLyricsSidecarDiff(emptyList(), emptySet()),
            membershipPlan = AutoSyncMembershipPlan.Apply(emptyList()),
        )

        val badDateAdded = tracker.acceptFullSnapshot(
            DeviceShadowCanonicalCatalog.snapshot(
                listOf(
                    base.copy(
                        title = "updated",
                        dateModifiedMs = base.dateModifiedMs + 10L,
                        replayGain = ReplayGainTags(trackGainDb = -2f),
                        dateAddedMs = base.dateAddedMs + 1L,
                    ),
                ),
                context,
            ),
        ) as DeviceShadowCanonicalCoverageResult.Compared

        assertFalse(badDateAdded.fullyCovered)
        assertEquals(
            setOf(DeviceShadowCanonicalAspect.MEMBERSHIP),
            badDateAdded.uncoveredAspectsByStableObjectKey.getValue(base.id),
        )
    }

    @Test
    fun providerIdentityDomainChangeResetsEvenWhenActivationIsUnchanged() {
        val tracker = DeviceShadowCanonicalCoverageTracker()
        val base = song("ms_6")
        tracker.acceptFullSnapshot(DeviceShadowCanonicalCatalog.snapshot(listOf(base), context))

        val reset = tracker.acceptFullSnapshot(
            DeviceShadowCanonicalCatalog.snapshot(
                listOf(base.copy(title = "id may now refer to another row")),
                context.copy(providerIdentityDomain = "mediastore:external_primary:v2"),
            ),
        )

        assertTrue(reset is DeviceShadowCanonicalCoverageResult.ContextReset)
    }

    @Test
    fun contextChangeResetsCoverageInsteadOfComparingDifferentAuthorities() {
        val tracker = DeviceShadowCanonicalCoverageTracker()
        val base = song("ms_5")
        tracker.acceptFullSnapshot(DeviceShadowCanonicalCatalog.snapshot(listOf(base), context))

        val reset = tracker.acceptFullSnapshot(
            DeviceShadowCanonicalCatalog.snapshot(
                listOf(base.copy(title = "different authority")),
                context.copy(activationEpoch = 8L),
            ),
        )

        assertTrue(reset is DeviceShadowCanonicalCoverageResult.ContextReset)
    }

    private fun song(id: String) = SongFixtures.song(id).copy(
        mediaUri = "content://media/external/audio/media/${id.removePrefix("ms_")}",
        fileName = "a.flac",
        folderPath = "Music",
        filePath = "Music/a.flac",
        sizeBytes = 100L,
        dateAddedMs = 1_000L,
        dateModifiedMs = 2_000L,
        externalLyricsSignature = "lrc:r1",
    )

    private fun row(id: Long) = DeviceDeltaRow(
        channel = DeviceDeltaChannel.AUDIO,
        volumeName = "external_primary",
        mediaStoreId = id,
        mediaUri = "content://media/external_primary/audio/$id",
        displayName = "a.flac",
        mimeType = "audio/flac",
        relativePath = "Music/",
        sizeBytes = 100L,
        dateModifiedMs = 2_010L,
        generationAdded = 1L,
        generationModified = 2L,
        eligibility = LibraryEligibility.ELIGIBLE,
    )
}
