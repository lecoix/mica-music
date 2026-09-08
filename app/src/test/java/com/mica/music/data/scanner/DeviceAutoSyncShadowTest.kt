package com.mica.music.data.scanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceAutoSyncShadowTest {

    @Test
    fun baselineMissingCannotAdvanceThroughOrdinaryAccept() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val state10 = snapshot(volume("external_primary", "v1", 10L))
        val generationApi = sequenceGenerationApi(state10, state10, state10)
        var queryCalls = 0
        val shadow = AndroidDeviceAutoSyncShadow(
            context = context,
            generationApi = generationApi,
            queryApiFactory = {
                DeviceMediaStoreDeltaQueryApi { _, _ ->
                    queryCalls++
                    emptyList()
                }
            },
            lyricsInventoryLoader = ::completeLyricsInventory,
            presenceInventoryLoader = ::completePresenceInventory,
        )

        val first = shadow.observe("cfg")
        assertTrue(first is DeviceAutoSyncShadowObservation.BaselineCandidate)

        shadow.accept(first, "cfg")
        val afterAccept = shadow.observe("cfg")

        assertTrue(afterAccept is DeviceAutoSyncShadowObservation.BaselineCandidate)
        assertEquals(0, queryCalls)
    }

    @Test
    fun fullScanAnchorSeedsExactCapturedGeneration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val state10 = snapshot(volume("external_primary", "v1", 10L))
        val state12 = snapshot(volume("external_primary", "v1", 12L))
        val generationApi = sequenceGenerationApi(state10, state12, state12)
        val windows = mutableListOf<DeviceDeltaWindow>()
        val shadow = AndroidDeviceAutoSyncShadow(
            context = context,
            generationApi = generationApi,
            queryApiFactory = {
                DeviceMediaStoreDeltaQueryApi { window, _ ->
                    windows += window
                    emptyList()
                }
            },
            lyricsInventoryLoader = ::completeLyricsInventory,
            presenceInventoryLoader = ::completePresenceInventory,
        )

        val anchor = shadow.captureFullScanAnchor("cfg")
        assertEquals(
            DeviceFullScanShadowAnchor.Available(state10),
            anchor,
        )
        shadow.acceptFullScanAnchor(anchor, "cfg")

        val delta = shadow.observe("cfg") as DeviceAutoSyncShadowObservation.DeltaCandidate

        assertFalse(delta.followUpRequired)
        assertTrue(
            windows.all {
                it.fromGenerationExclusive == 10L && it.toGenerationInclusive == 12L
            },
        )
    }

    @Test
    fun persistedAnchorRestoreSeedsDeltaAfterProcessRecreationWithoutMovingLiveCursorBackward() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val state10 = snapshot(volume("external_primary", "v1", 10L))
        val state12 = snapshot(volume("external_primary", "v1", 12L))
        val generationApi = sequenceGenerationApi(state12, state12)
        val windows = mutableListOf<DeviceDeltaWindow>()
        val shadow = AndroidDeviceAutoSyncShadow(
            context = context,
            generationApi = generationApi,
            queryApiFactory = {
                DeviceMediaStoreDeltaQueryApi { window, _ ->
                    windows += window
                    emptyList()
                }
            },
            lyricsInventoryLoader = ::completeLyricsInventory,
            presenceInventoryLoader = ::completePresenceInventory,
        )

        assertTrue(shadow.restorePersistedAnchor(state10, "cfg"))
        assertFalse(shadow.restorePersistedAnchor(state12, "cfg"))

        val delta = shadow.observe("cfg") as DeviceAutoSyncShadowObservation.DeltaCandidate

        assertFalse(delta.followUpRequired)
        assertTrue(
            windows.all {
                it.fromGenerationExclusive == 10L && it.toGenerationInclusive == 12L
            },
        )
    }

    @Test
    fun unchangedGenerationSkipsDeltaSidecarAndPresenceQueries() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val state10 = snapshot(volume("external_primary", "v1", 10L))
        val generationApi = sequenceGenerationApi(state10, state10)
        var queryFactoryCalls = 0
        var lyricsInventoryCalls = 0
        var presenceInventoryCalls = 0
        val shadow = AndroidDeviceAutoSyncShadow(
            context = context,
            generationApi = generationApi,
            queryApiFactory = {
                queryFactoryCalls += 1
                DeviceMediaStoreDeltaQueryApi { _, _ -> emptyList() }
            },
            lyricsInventoryLoader = {
                lyricsInventoryCalls += 1
                completeLyricsInventory()
            },
            presenceInventoryLoader = {
                presenceInventoryCalls += 1
                completePresenceInventory()
            },
        )

        val anchor = shadow.captureFullScanAnchor("cfg")
        shadow.acceptFullScanAnchor(anchor, "cfg")

        val noChange = shadow.observe("cfg")

        assertEquals(
            DeviceAutoSyncShadowObservation.NoChange(state10),
            noChange,
        )
        assertEquals(0, queryFactoryCalls)
        assertEquals(0, lyricsInventoryCalls)
        assertEquals(0, presenceInventoryCalls)
    }

    @Test
    fun postQueryNewerGenerationSchedulesFollowupAndOnlyAdvancesToQueriedUpperBound() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val state10 = snapshot(volume("external_primary", "v1", 10L))
        val state12 = snapshot(volume("external_primary", "v1", 12L))
        val state14 = snapshot(volume("external_primary", "v1", 14L))
        val generationApi = sequenceGenerationApi(
            state10,
            state12, state14,
            state14, state14,
        )
        val windows = mutableListOf<DeviceDeltaWindow>()
        val shadow = AndroidDeviceAutoSyncShadow(
            context = context,
            generationApi = generationApi,
            queryApiFactory = {
                DeviceMediaStoreDeltaQueryApi { window, _ ->
                    windows += window
                    emptyList()
                }
            },
            lyricsInventoryLoader = ::completeLyricsInventory,
        )

        val baseline = shadow.captureFullScanAnchor("cfg")
        shadow.acceptFullScanAnchor(baseline, "cfg")

        val firstDelta = shadow.observe("cfg") as DeviceAutoSyncShadowObservation.DeltaCandidate
        assertTrue(firstDelta.followUpRequired)
        assertTrue(windows.all { it.fromGenerationExclusive == 10L && it.toGenerationInclusive == 12L })
        shadow.accept(firstDelta, "cfg")

        windows.clear()
        val followUp = shadow.observe("cfg") as DeviceAutoSyncShadowObservation.DeltaCandidate
        assertFalse(followUp.followUpRequired)
        assertTrue(windows.all { it.fromGenerationExclusive == 12L && it.toGenerationInclusive == 14L })
    }

    @Test
    fun partialChannelFailureNeverAdvancesCursor() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val state10 = snapshot(volume("external_primary", "v1", 10L))
        val state12 = snapshot(volume("external_primary", "v1", 12L))
        val generationApi = sequenceGenerationApi(state10, state12, state12)
        val observedWindows = mutableListOf<DeviceDeltaWindow>()
        val shadow = AndroidDeviceAutoSyncShadow(
            context = context,
            generationApi = generationApi,
            queryApiFactory = {
                DeviceMediaStoreDeltaQueryApi { window, channel ->
                    observedWindows += window
                    if (channel == DeviceDeltaChannel.FILES_FALLBACK) {
                        error("files unavailable")
                    }
                    emptyList()
                }
            },
            lyricsInventoryLoader = ::completeLyricsInventory,
        )

        val baseline = shadow.captureFullScanAnchor("cfg")
        shadow.acceptFullScanAnchor(baseline, "cfg")
        val failed = shadow.observe("cfg")
        assertTrue(failed is DeviceAutoSyncShadowObservation.ReconcileRequired)
        shadow.accept(failed, "cfg")

        observedWindows.clear()
        val retry = shadow.observe("cfg")
        assertTrue(retry is DeviceAutoSyncShadowObservation.ReconcileRequired)
        assertTrue(
            observedWindows.all {
                it.fromGenerationExclusive == 10L && it.toGenerationInclusive == 12L
            },
        )
    }

    @Test
    fun crossVolumeIdentityConflictNeverAdvancesCursor() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val state1 = snapshot(
            volume("1234-5678", "sd-v1", 1L),
            volume("external_primary", "v1", 1L),
        )
        val state2 = snapshot(
            volume("1234-5678", "sd-v1", 2L),
            volume("external_primary", "v1", 2L),
        )
        val generationApi = sequenceGenerationApi(state1, state2, state2)
        val shadow = AndroidDeviceAutoSyncShadow(
            context = context,
            generationApi = generationApi,
            queryApiFactory = {
                DeviceMediaStoreDeltaQueryApi { window, channel ->
                    if (channel == DeviceDeltaChannel.AUDIO) {
                        listOf(row(window.volumeName, channel, 42L))
                    } else {
                        emptyList()
                    }
                }
            },
            lyricsInventoryLoader = ::completeLyricsInventory,
        )

        val baseline = shadow.captureFullScanAnchor("cfg")
        shadow.acceptFullScanAnchor(baseline, "cfg")
        val conflict = shadow.observe("cfg")

        assertTrue(conflict is DeviceAutoSyncShadowObservation.ReconcileRequired)
        assertTrue((conflict as DeviceAutoSyncShadowObservation.ReconcileRequired).detail.contains("identityConflicts"))
        shadow.accept(conflict, "cfg")

        val retry = shadow.observe("cfg")
        assertTrue(retry is DeviceAutoSyncShadowObservation.ReconcileRequired)
    }

    @Test
    fun providerVersionChangeDuringQueryRequestsReconcileWithoutAdvance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val state10 = snapshot(volume("external_primary", "v1", 10L))
        val state12 = snapshot(volume("external_primary", "v1", 12L))
        val reset = snapshot(volume("external_primary", "v2", 1L))
        val generationApi = sequenceGenerationApi(state10, state12, reset, state12)
        val shadow = AndroidDeviceAutoSyncShadow(
            context = context,
            generationApi = generationApi,
            queryApiFactory = { DeviceMediaStoreDeltaQueryApi { _, _ -> emptyList() } },
            lyricsInventoryLoader = ::completeLyricsInventory,
        )

        val baseline = shadow.captureFullScanAnchor("cfg")
        shadow.acceptFullScanAnchor(baseline, "cfg")
        val changed = shadow.observe("cfg") as DeviceAutoSyncShadowObservation.ReconcileRequired

        assertTrue(changed.followUpRequired)
        assertTrue(changed.detail.contains("provider version changed"))
        shadow.accept(changed, "cfg")

        val next = shadow.observe("cfg")
        assertTrue(next is DeviceAutoSyncShadowObservation.DeltaCandidate)
        val delta = next as DeviceAutoSyncShadowObservation.DeltaCandidate
        assertEquals(10L, delta.batch.windows.single().fromGenerationExclusive)
        assertEquals(12L, delta.batch.windows.single().toGenerationInclusive)
    }

    private fun completePresenceInventory(): PresenceInventory = PresenceInventory.fromEntries(
        entries = emptyList(),
        discoveryReport = DiscoveryReport.of(
            DiscoveryPartitionStatus(
                partitionKey = DiscoveryPartitions.MEDIASTORE_AUDIO,
                completeness = DiscoveryCompleteness.COMPLETE,
            ),
            DiscoveryPartitionStatus(
                partitionKey = DiscoveryPartitions.MEDIASTORE_FILES_FALLBACK,
                completeness = DiscoveryCompleteness.COMPLETE,
            ),
        ),
    )
    private fun completeLyricsInventory(): MediaStoreLyricsSidecarInventoryResult =
        MediaStoreLyricsSidecarInventoryResult(
            refsByLyricsKey = emptyMap(),
            status = DiscoveryPartitionStatus(
                partitionKey = DiscoveryPartitions.MEDIASTORE_LYRICS_SIDECARS,
                completeness = DiscoveryCompleteness.COMPLETE,
            ),
        )
    private fun sequenceGenerationApi(
        vararg snapshots: DeviceGenerationSnapshot,
    ): DeviceMediaStoreGenerationApi {
        var index = 0
        return DeviceMediaStoreGenerationApi {
            snapshots[index.coerceAtMost(snapshots.lastIndex)].also { index++ }
        }
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
        mimeType = "audio/flac",
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
