package com.mica.music.data.library

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.mica.music.data.CURRENT_LYRICS_PARSER_VERSION
import com.mica.music.data.LibraryScanner
import com.mica.music.data.LibraryStore
import com.mica.music.data.LyricsSlots
import com.mica.music.data.LyricsScanBatch
import com.mica.music.data.PlayStats
import com.mica.music.data.ScannedSongLyrics
import com.mica.music.data.ScanEnvironment
import com.mica.music.data.ScanSource
import com.mica.music.data.Song
import com.mica.music.data.SongSortField
import com.mica.music.data.SortDirection
import com.mica.music.data.StartupBrowseTarget
import com.mica.music.data.local.CachedLibrary
import com.mica.music.data.local.LibrarySyncResult
import com.mica.music.data.preferences.LibraryBrowseSettings
import com.mica.music.data.preferences.LibraryScanSettings
import com.mica.music.data.scanner.AutoSyncVisibleDelta
import com.mica.music.data.scanner.DeviceAutoSyncShadow
import com.mica.music.data.scanner.DeviceAutoSyncShadowObservation
import com.mica.music.data.scanner.DeviceAutoSyncObservationScope
import com.mica.music.data.scanner.DeviceDeltaChannel
import com.mica.music.data.scanner.DeviceDeltaChannelStatus
import com.mica.music.data.scanner.DeviceDeltaRow
import com.mica.music.data.scanner.DeviceDeltaWindow
import com.mica.music.data.scanner.DeviceFullScanShadowAnchor
import com.mica.music.data.scanner.DeviceGenerationSnapshot
import com.mica.music.data.scanner.DeviceMediaStoreChannelCapability
import com.mica.music.data.scanner.DeviceMediaStoreDeltaBatch
import com.mica.music.data.scanner.DeviceMediaStorePresenceCapabilityProfile
import com.mica.music.data.scanner.DeviceVolumeGeneration
import com.mica.music.data.scanner.DiscoveryCompleteness
import com.mica.music.data.scanner.SafIndependentMissingVerificationResult
import com.mica.music.data.scanner.SafMissingVerificationBudget
import com.mica.music.data.scanner.SafTreeMetadataSnapshot
import com.mica.music.data.scanner.SafTreeMetadataObservationStats
import com.mica.music.data.scanner.SafTreeMetadataEntry
import com.mica.music.data.scanner.DiscoveryReport
import com.mica.music.data.scanner.DiscoveryPartitions
import com.mica.music.data.scanner.DiscoveryPartitionStatus
import com.mica.music.data.scanner.LibraryEligibility
import com.mica.music.data.scanner.NoopDeviceAutoSyncShadow
import com.mica.music.data.scanner.MediaStoreLyricsSidecarInventoryResult
import com.mica.music.data.scanner.PresenceInventory
import com.mica.music.data.scanner.ScanResult
import com.mica.music.data.scanner.ScanProbeStats
import com.mica.music.testutil.SongFixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibraryOperationExecutorTest {

    @Test
    fun concurrentScansExecuteSequentiallyAndLatestResultWins() = runTest {
        val scanner = ControlledScanner()
        val harness = scanHarness(scanner)

        val oldScan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        val newScan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()

        assertEquals(1, scanner.deviceRequests.size)
        assertFalse(scanner.deviceRequests.single().forceRefreshLyrics)
        assertFalse(scanner.deviceRequests.single().forceRefreshArtwork)
        scanner.deviceRequests.single().result.complete(
            ScanResult(listOf(SongFixtures.song("old")), 10),
        )
        oldScan.await()
        runCurrent()
        scanner.deviceRequests[1].result.complete(
            ScanResult(listOf(SongFixtures.song("new")), 20),
        )
        newScan.await()

        assertEquals(listOf("new"), harness.backing.songs.map { it.id })
        assertEquals(20, harness.backing.totalSizeMb)
        harness.backing.release()
    }

    @Test
    fun deviceFullAnchorIsDurableBeforeInMemoryCursorAdopt() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val commitGate = CompletableDeferred<Unit>()
        store.commitGate = commitGate
        val anchor = DeviceFullScanShadowAnchor.Available(deviceGenerationSnapshot(10L))
        val shadow = ControlledDeviceAutoSyncShadow(
            observation = DeviceAutoSyncShadowObservation.Disabled,
            fullScanAnchor = anchor,
        )
        val harness = scanHarness(
            scanner = scanner,
            store = store,
            deviceAutoSyncShadow = shadow,
        )
        activateDeviceSource(harness.backing)

        val scan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests.single().result.complete(
            ScanResult(listOf(SongFixtures.song("device-anchor")), 7),
        )
        runCurrent()

        assertTrue(store.commitStarted.isCompleted)
        assertTrue(store.syncCheckpoints.isEmpty())
        assertTrue(shadow.acceptedFullAnchors.isEmpty())

        commitGate.complete(Unit)
        scan.await()
        runCurrent()

        assertEquals(listOf(anchor), shadow.acceptedFullAnchors.map { it.first })
        val checkpoint = store.syncCheckpoints.single()
        assertEquals(
            DeviceGenerationCheckpointCodec.partitionKey("external_primary"),
            checkpoint.partitionKey,
        )
        assertEquals("v1", checkpoint.providerVersion)
        assertEquals(10L, checkpoint.generation)
        assertEquals(harness.backing.configFingerprint, checkpoint.configFingerprint)

        harness.backing.release()
        runCurrent()
    }

    @Test
    fun releasePreventsLateScanStatePublication() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val harness = scanHarness(scanner, store)

        val scan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        harness.backing.release()
        scanner.deviceRequests.single().result.complete(
            ScanResult(listOf(SongFixtures.song("late")), 99),
        )
        scan.await()

        assertTrue(harness.backing.songs.isEmpty())
        assertFalse(harness.backing.hasScanned)
        assertFalse(harness.backing.isScanning)
        assertNull(harness.backing.scanProgressLabel)
        assertNull(harness.backing.lastScanAtMs)
        assertTrue(store.syncedSongs.isEmpty())
    }

    @Test
    fun lyricsParserUpgradeForcesProbeWithoutMutatingCachedLyrics() = runTest {
        val cached = SongFixtures.song("cached")
        val scanner = ControlledScanner()
        val environment = FakeScanEnvironment(parserVersion = 0)
        val harness = scanHarness(
            scanner = scanner,
            store = FakeLibraryStore(
                CachedLibrary(listOf(cached), 100, ScanSource.DEVICE, 1),
            ),
            environment = environment,
        )

        val scan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        assertEquals(0, harness.backing.lyricsDataVersion)
        assertTrue(scanner.deviceRequests.single().forceRefreshLyrics)
        assertEquals(cached.lyricsDocument, scanner.deviceRequests.single().cachedSongs.single().lyricsDocument)
        scanner.deviceRequests.single().result.complete(ScanResult(listOf(cached), 1))
        scan.await()

        assertEquals(CURRENT_LYRICS_PARSER_VERSION, environment.parserVersion)
        assertEquals(CURRENT_LYRICS_PARSER_VERSION, harness.backing.lyricsDataVersion)
        harness.backing.release()
    }

    @Test
    fun lyricsReadFailurePersistsRetryAndBlocksParserVersionAdvanceUntilCleanScan() = runTest {
        val scanner = ControlledScanner()
        val environment = FakeScanEnvironment(parserVersion = 0)
        val harness = scanHarness(scanner, environment = environment)
        val song = SongFixtures.song("retry")

        val failedScan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests[0].onLyricsBatch?.invoke(LyricsScanBatch(emptyList(), 1))
        assertTrue(environment.retryRequired)
        scanner.deviceRequests[0].result.complete(
            ScanResult(
                songs = listOf(song),
                totalSizeMb = 1,
                probeStats = ScanProbeStats(lyricsReadFailed = 1),
            ),
        )
        failedScan.await()

        assertTrue(environment.retryRequired)
        assertEquals(0, environment.parserVersion)

        val cleanScan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        assertTrue(scanner.deviceRequests[1].forceRefreshLyrics)
        scanner.deviceRequests[1].result.complete(ScanResult(listOf(song), 1))
        cleanScan.await()

        assertFalse(environment.retryRequired)
        assertEquals(CURRENT_LYRICS_PARSER_VERSION, environment.parserVersion)
        harness.backing.release()
    }

    @Test
    fun targetedSongRefreshUsesOneOffProbeTargetWithoutGlobalLyricsRefresh() = runTest {
        val scanner = ControlledScanner()
        val environment = FakeScanEnvironment(parserVersion = 0, retryRequired = true)
        val harness = scanHarness(scanner, environment = environment)
        harness.backing.lastScanAtMs = 777L
        harness.backing.lastScanSource = ScanSource.DEVICE
        val target = SongFixtures.song("target").copy(
            title = "Old title",
            mediaUri = "content://media/external/audio/media/42",
            fileName = "track.flac",
            folderPath = "QQmusic/song",
            filePath = "QQmusic/song/track.flac",
            sizeBytes = 100L,
            dateAddedMs = 10L,
            dateModifiedMs = 20L,
        )
        val other = SongFixtures.song("other")
        harness.backing.replaceSongs(listOf(target, other))
        val refreshedTarget = target.copy(
            title = "New title",
            mediaUri = "content://media/external/audio/media/42",
            fileName = "TRACK.flac",
            folderPath = "qqmusic/song",
            filePath = "qqmusic/song/TRACK.flac",
            sizeBytes = 120L,
            dateAddedMs = 999L,
            dateModifiedMs = 30L,
        )

        val refresh = async { harness.orchestrator.refreshSongMetadata(target.id) }
        runCurrent()

        assertEquals(setOf(target.id), scanner.deviceRequests.single().forceRefreshSongIds)
        assertFalse(scanner.deviceRequests.single().forceRefreshLyrics)
        val scannerChangedOther = other.copy(title = "Scanner should not refresh this")
        val transientExtra = SongFixtures.song("transient-extra")
        scanner.deviceRequests.single().result.complete(
            ScanResult(listOf(refreshedTarget, scannerChangedOther, transientExtra), 2),
        )
        refresh.await()

        assertEquals(setOf(target.id, other.id), harness.backing.songs.map { it.id }.toSet())
        val published = harness.backing.songs.single { it.id == target.id }
        assertEquals("New title", published.title)
        assertEquals(120L, published.sizeBytes)
        assertEquals(30L, published.dateModifiedMs)
        assertEquals(target.mediaUri, published.mediaUri)
        assertEquals(target.fileName, published.fileName)
        assertEquals(target.folderPath, published.folderPath)
        assertEquals(target.filePath, published.filePath)
        assertEquals(target.dateAddedMs, published.dateAddedMs)
        assertEquals(other.title, harness.backing.songs.single { it.id == other.id }.title)
        advanceUntilIdle()
        assertEquals(777L, harness.backing.lastScanAtMs)
        assertEquals(ScanSource.DEVICE, harness.backing.lastScanSource)
        assertEquals(0, environment.parserVersion)
        assertTrue(environment.retryRequired)
        assertTrue(environment.prunedSongIds.isEmpty())
        harness.backing.release()
    }

    @Test
    fun targetedSongRefreshKeepsPreviousRowWhenTargetTemporarilyDisappearsFromScanner() = runTest {
        val scanner = ControlledScanner()
        val harness = scanHarness(scanner)
        val target = SongFixtures.song("target-missing").copy(
            title = "Old title",
            folderPath = "QQmusic/song",
            filePath = "QQmusic/song/track.flac",
        )
        val other = SongFixtures.song("other-kept")
        harness.backing.replaceSongs(listOf(target, other))

        val refresh = async { harness.orchestrator.refreshSongMetadata(target.id) }
        runCurrent()
        scanner.deviceRequests.single().result.complete(ScanResult(listOf(other), 1))
        refresh.await()

        assertEquals(setOf(target.id, other.id), harness.backing.songs.map { it.id }.toSet())
        assertEquals("Old title", harness.backing.songs.single { it.id == target.id }.title)
        assertEquals("QQmusic/song", harness.backing.songs.single { it.id == target.id }.folderPath)
        harness.backing.release()
    }

    @Test
    fun fullDiscoveryScanAllowsStorageLocationToChange() = runTest {
        val scanner = ControlledScanner()
        val harness = scanHarness(scanner)
        val previous = SongFixtures.song("moved").copy(
            folderPath = "Music/A",
            filePath = "Music/A/track.flac",
        )
        harness.backing.replaceSongs(listOf(previous))
        val moved = previous.copy(
            folderPath = "Music/B",
            filePath = "Music/B/track.flac",
            dateModifiedMs = previous.dateModifiedMs + 1L,
        )

        val scan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests.single().result.complete(ScanResult(listOf(moved), 1))
        scan.await()

        val published = harness.backing.songs.single()
        assertEquals("Music/B", published.folderPath)
        assertEquals("Music/B/track.flac", published.filePath)
        harness.backing.release()
    }

    @Test
    fun canceledScanKeepsAlreadyCommittedTrustedLyricsWithoutPublishingCatalog() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val environment = FakeScanEnvironment()
        val harness = scanHarness(scanner, store, environment)
        val song = SongFixtures.song("staged")
        activateDeviceSource(harness.backing)

        val scan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests.single().onLyricsBatch?.invoke(
            LyricsScanBatch(listOf(
                ScannedSongLyrics(
                    song.id,
                    song.lyricsCacheRevision,
                    LyricsSlots(embedded = song.lyricsDocument),
                ),
            ), 0),
        )
        assertEquals(1, store.appliedLyrics.size)

        scan.cancelAndJoin()

        assertEquals(1, store.appliedLyrics.size)
        assertTrue(store.syncedSongs.isEmpty())
        assertTrue(environment.prunedSongIds.isEmpty())
        harness.backing.release()
    }

    @Test
    fun activeAutoSyncTokenIgnoresPendingFolderSelection() = runTest {
        val harness = scanHarness(ControlledScanner())
        val activeTree = Uri.parse("content://provider/tree/active-auto")
        val pendingTree = Uri.parse("content://provider/tree/pending-auto")
        activateFolderSource(harness.backing, activeTree, "Active")
        val active = requireNotNull(harness.backing.sourceState.active)
        harness.backing.pendingLibraryFolderUri = pendingTree.toString()
        harness.backing.pendingLibraryFolderLabel = "Pending"

        val token = requireNotNull(
            harness.backing.beginActiveAutoSyncOperationToken(
                requestSequence = 188L,
                dirtySequenceAtStart = 44L,
                cause = LibraryOperationCause.SAF_PERIODIC_VERIFY,
            ),
        )

        assertEquals(SourceIdentityKey.folder(activeTree.toString()), token.sourceIdentity)
        assertEquals(active.activationEpoch, token.activationEpoch)
        assertEquals(LibraryOperationMode.AUTO_SYNC, token.mode)
        assertEquals(pendingTree.toString(), harness.backing.pendingLibraryFolderUri)
        assertNull(harness.backing.sourceState.pendingTransition)

        clearFolderPrefs(harness.backing)
        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun pendingActivationCanBeAbandonedWithoutCreatingActiveSource() = runTest {
        val scanner = ControlledScanner()
        val harness = scanHarness(scanner)
        val token = harness.backing.beginOperationToken(
            source = ScanSource.DEVICE,
            requestSequence = 1L,
            dirtySequenceAtStart = 0L,
            mode = LibraryOperationMode.FULL,
            cause = LibraryOperationCause.USER_RESCAN,
        )!!

        assertNull(harness.backing.sourceState.active)
        assertEquals(
            SourceIdentityKey.device(),
            harness.backing.sourceState.pendingTransition?.sourceIdentity,
        )

        harness.backing.abandonPendingTransition(token)

        assertNull(harness.backing.sourceState.active)
        assertNull(harness.backing.sourceState.pendingTransition)

        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun canceledFirstActivationDiscardsOperationScopedLyricsStaging() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val harness = scanHarness(scanner, store)
        val song = SongFixtures.song("first-activation-staged")

        val scan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests.single().onLyricsBatch?.invoke(
            LyricsScanBatch(
                completed = listOf(
                    ScannedSongLyrics(
                        song.id,
                        song.lyricsCacheRevision,
                        LyricsSlots(embedded = song.lyricsDocument),
                    ),
                ),
                readFailedCount = 0,
            ),
        )

        assertTrue(store.appliedLyrics.isEmpty())
        assertEquals(1, store.stagedLyrics.values.sumOf { it.size })

        scan.cancelAndJoin()
        runCurrent()

        assertTrue(store.appliedLyrics.isEmpty())
        assertTrue(store.stagedLyrics.isEmpty())
        assertNull(harness.backing.sourceState.active)
        assertNull(harness.backing.sourceState.pendingTransition)

        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun clearLibraryDoesNotAllowAnInFlightLyricsBatchToRepopulateTheClearedStore() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val harness = scanHarness(scanner, store)
        val releaseLyricsBatch = CompletableDeferred<Unit>()
        store.lyricsBatchGate = releaseLyricsBatch
        val song = SongFixtures.song("clear-lyrics-race")

        val scan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        val applyBatch = async {
            scanner.deviceRequests.single().onLyricsBatch?.invoke(
                LyricsScanBatch(
                    completed = listOf(
                        ScannedSongLyrics(
                            song.id,
                            song.lyricsCacheRevision,
                            LyricsSlots(embedded = song.lyricsDocument),
                        ),
                    ),
                    readFailedCount = 0,
                ),
            )
        }
        runCurrent()
        assertTrue(store.lyricsBatchStarted.isCompleted)

        val generationBeforeClear = harness.backing.scanGeneration
        harness.backing.folder.clearLibrary()
        runCurrent()
        assertTrue(harness.backing.scanGeneration > generationBeforeClear)

        releaseLyricsBatch.complete(Unit)
        applyBatch.await()
        runCurrent()

        assertTrue(store.appliedLyrics.isEmpty())
        assertTrue(store.clearCompleted)
        scan.cancelAndJoin()
        harness.backing.release()
    }

    @Test
    fun successfulScanSchedulesArtworkMaintenanceAfterTheNewLibraryIsCommitted() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val environment = FakeScanEnvironment()
        val harness = scanHarness(scanner, store, environment)
        val scanned = listOf(SongFixtures.song("one"), SongFixtures.song("two"))

        val scan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        assertTrue(environment.prunedSongIds.isEmpty())

        scanner.deviceRequests.single().result.complete(ScanResult(scanned, 2))
        scan.await()
        runCurrent()

        assertEquals(scanned.map(Song::id), store.syncedSongs.map(Song::id))
        assertEquals(scanned.map(Song::id), environment.prunedSongIds)
        assertTrue(environment.prefetchedVideoCoverRefs.isEmpty())
        harness.backing.release()
    }

    @Test
    fun generationChangeDuringBackgroundArtworkMaintenanceDoesNotDiscardPublishedSnapshot() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val environment = FakeScanEnvironment()
        val harness = scanHarness(scanner, store, environment)
        environment.duringPrune = {
            harness.backing.scanGeneration++
        }

        val scan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests.single().result.complete(
            ScanResult(listOf(SongFixtures.song("invalidated")), 1),
        )
        scan.await()
        runCurrent()

        assertTrue(environment.prunedSongIds.isNotEmpty())
        assertEquals(listOf("invalidated"), harness.backing.songs.map(Song::id))
        assertTrue(harness.backing.hasScanned)
        harness.backing.release()
    }

    @Test
    fun artworkMaintenanceReadsCatalogOnlyAfterScanLockIsReleased() = runTest {
        val scanner = ControlledScanner()
        val environment = FakeScanEnvironment()
        val harness = scanHarness(scanner, environment = environment)
        val oldSnapshot = SongFixtures.song("old-art")
        val newSnapshot = SongFixtures.song("new-art")
        harness.backing.replaceSongs(listOf(oldSnapshot))

        val releaseScan = CompletableDeferred<Unit>()
        val scanLock = async {
            harness.backing.operationExecutionMutex.withLock {
                releaseScan.await()
            }
        }
        runCurrent()

        harness.backing.launchAlbumArtCacheMaintenance()
        runCurrent()
        harness.backing.replaceSongs(listOf(newSnapshot))
        releaseScan.complete(Unit)
        scanLock.await()
        runCurrent()

        assertEquals(listOf("new-art"), environment.prunedSongIds)
        harness.backing.release()
    }

    @Test
    fun scanFailureDoesNotPublishMetadataOrHasScanned() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val harness = scanHarness(scanner, store)
        val kept = SongFixtures.song("kept")

        val first = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests[0].result.complete(ScanResult(listOf(kept), 5))
        first.await()
        assertEquals(5, harness.backing.totalSizeMb)
        assertEquals(1_234L, harness.backing.lastScanAtMs)

        val failed = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests[1].result.completeExceptionally(IllegalStateException("boom"))
        failed.await()

        assertEquals(listOf("kept"), harness.backing.songs.map { it.id })
        assertEquals(5, harness.backing.totalSizeMb)
        assertEquals(1_234L, harness.backing.lastScanAtMs)
        assertTrue(harness.backing.hasScanned)
        assertEquals("boom", harness.backing.lastScanError)
        assertEquals(listOf("kept"), store.syncedSongs.map { it.id })
        harness.backing.release()
    }

    @Test
    fun clearLibraryInvalidatesInFlightScanPublication() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val harness = scanHarness(scanner, store)

        val scan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        harness.backing.folder.clearLibrary()
        runCurrent()
        scanner.deviceRequests.single().result.complete(
            ScanResult(listOf(SongFixtures.song("late")), 99),
        )
        scan.await()
        runCurrent()

        assertTrue(harness.backing.songs.isEmpty())
        assertFalse(harness.backing.hasScanned)
        assertNull(harness.backing.lastScanAtMs)
        assertTrue(store.syncedSongs.isEmpty())
        harness.backing.release()
    }

    @Test
    fun clearDuringTargetedProbePreventsLatePartialResultFromRepopulatingStore() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val harness = scanHarness(scanner, store)
        val target = SongFixtures.song("artwork-target")
        harness.backing.replaceSongs(listOf(target, SongFixtures.song("unrelated")))
        val refresh = async { harness.orchestrator.refreshSongMetadata(target.id) }
        runCurrent()
        assertEquals(setOf(target.id), scanner.deviceRequests.single().forceRefreshSongIds)
        harness.backing.folder.clearLibrary()
        runCurrent()
        scanner.deviceRequests.single().result.complete(ScanResult(listOf(target), 1))
        refresh.await()
        runCurrent()
        assertTrue(harness.backing.songs.isEmpty())
        assertTrue(store.syncedSongs.isEmpty())
        assertFalse(harness.backing.hasScanned)
        harness.backing.release()
    }

    @Test
    fun catalogDerivedStoreWriteWaitsForScanExecutionLock() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val harness = scanHarness(scanner, store)
        val releaseScanLock = CompletableDeferred<Unit>()
        val heldScanLock = async {
            harness.backing.publicationMutex.withLock {
                releaseScanLock.await()
            }
        }
        runCurrent()

        val sourceRevision = harness.backing.catalogRevision
        val derived = async {
            harness.backing.storeWriteIfCurrentCatalog(
                expectedCatalogRevision = sourceRevision,
                isCurrent = { true },
            ) {
                store.updatePresentation(emptyList(), SongSortField.TITLE, SortDirection.ASC, null)
            }
        }
        runCurrent()
        assertFalse(derived.isCompleted)

        releaseScanLock.complete(Unit)
        heldScanLock.await()
        assertTrue(derived.await())
        harness.backing.release()
    }

    @Test
    fun generationDerivedStoreWriteWaitsForScanExecutionLock() = runTest {
        val harness = scanHarness(ControlledScanner(), FakeLibraryStore())
        val releaseScanLock = CompletableDeferred<Unit>()
        val heldScanLock = async {
            harness.backing.publicationMutex.withLock {
                releaseScanLock.await()
            }
        }
        runCurrent()

        var writeRan = false
        val derived = async {
            harness.backing.storeWriteIfCurrentGeneration(harness.backing.scanGeneration) {
                writeRan = true
            }
        }
        runCurrent()
        assertFalse(derived.isCompleted)
        assertFalse(writeRan)

        releaseScanLock.complete(Unit)
        heldScanLock.await()
        assertTrue(derived.await())
        assertTrue(writeRan)
        harness.backing.release()
    }

    @Test
    fun generationDerivedStoreWriteIsDroppedWhenGenerationChangesWhileWaiting() = runTest {
        val harness = scanHarness(ControlledScanner(), FakeLibraryStore())
        val releaseScanLock = CompletableDeferred<Unit>()
        val heldScanLock = async {
            harness.backing.publicationMutex.withLock {
                releaseScanLock.await()
            }
        }
        runCurrent()

        val expectedGeneration = harness.backing.scanGeneration
        var writeRan = false
        val derived = async {
            harness.backing.storeWriteIfCurrentGeneration(expectedGeneration) {
                writeRan = true
            }
        }
        runCurrent()
        harness.backing.scanGeneration++
        releaseScanLock.complete(Unit)
        heldScanLock.await()

        assertFalse(derived.await())
        assertFalse(writeRan)
        harness.backing.release()
    }

    @Test
    fun staleCacheHydrateIsDiscardedAfterNewerScanPublishes() = runTest {
        val scanner = ControlledScanner()
        val deferredCache = CompletableDeferred<CachedLibrary?>()
        var loadCachedCalls = 0
        val store = FakeLibraryStore(
            cachedLoader = {
                // First call is cache hydrate (blocked); later scan bootstrap returns empty quickly.
                if (loadCachedCalls++ == 0) deferredCache.await() else null
            },
        )
        val harness = scanHarness(scanner, store)

        val cacheLoad = async { harness.backing.cacheLoader.loadCachedLibrary(StartupBrowseTarget.NONE) }
        runCurrent()

        val scan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests.single().result.complete(
            ScanResult(listOf(SongFixtures.song("fresh")), 3),
        )
        scan.await()
        runCurrent()
        assertEquals(listOf("fresh"), harness.backing.songs.map { it.id })

        deferredCache.complete(
            CachedLibrary(
                songs = listOf(SongFixtures.song("stale")),
                lastScanAtMs = 10L,
                lastScanSource = ScanSource.DEVICE,
                totalSizeMb = 1,
            ),
        )
        assertNull(cacheLoad.await())
        assertEquals(listOf("fresh"), harness.backing.songs.map { it.id })
        assertEquals(3, harness.backing.totalSizeMb)
        harness.backing.release()
    }

    @Test
    fun folderScanEnqueuesUniqueVideoCoverPosterPrefetchAfterPublish() = runTest {
        val scanner = ControlledScanner()
        val environment = FakeScanEnvironment()
        val harness = scanHarness(scanner, environment = environment)
        harness.backing.libraryFolderUri = "content://tree/music"
        val scanned = listOf(
            SongFixtures.song("a").copy(videoCoverUri = "content://video/Album.mp4"),
            SongFixtures.song("b").copy(videoCoverUri = "content://video/Album.mp4"),
            SongFixtures.song("c").copy(videoCoverUri = null),
        )

        val scan = async { harness.orchestrator.scanLibraryFolder() }
        runCurrent()
        scanner.folderRequests.single().result.complete(ScanResult(scanned, 3))
        scan.await()

        assertEquals(
            listOf("content://video/Album.mp4", "content://video/Album.mp4"),
            environment.prefetchedVideoCoverRefs.map { it.uri },
        )
        harness.backing.release()
    }

    @Test
    fun cancellationAfterFinalValidationCompletesRoomAndMemoryPublication() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val commitGate = CompletableDeferred<Unit>()
        store.commitGate = commitGate
        val harness = scanHarness(scanner, store)
        val published = SongFixtures.song("cancel-shield")

        val scan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests.single().result.complete(ScanResult(listOf(published), 1))
        runCurrent()
        assertTrue(store.commitStarted.isCompleted)

        scan.cancel()
        runCurrent()
        assertTrue(store.syncedSongs.isEmpty())
        assertTrue(harness.backing.songs.isEmpty())

        commitGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(published.id), store.syncedSongs.map(Song::id))
        assertEquals(listOf(published.id), harness.backing.songs.map(Song::id))
        assertTrue(harness.backing.hasScanned)
        assertTrue(scan.isCancelled)

        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun releaseAfterFullStoreCommitCannotSplitRoomAndMemoryPublication() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val harness = scanHarness(scanner, store)
        val published = SongFixtures.song("release-shield-full")
        store.afterScanSnapshotStoreCommit = {
            harness.backing.release()
        }

        val scan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests.single().result.complete(ScanResult(listOf(published), 1))
        advanceUntilIdle()

        assertEquals(listOf(published.id), store.syncedSongs.map(Song::id))
        assertEquals(listOf(published.id), harness.backing.songs.map(Song::id))
        assertTrue(harness.backing.hasScanned)
        assertTrue(harness.backing.released)
        assertTrue(scan.isCompleted)
    }

    @Test
    fun cancellationBeforeFinalValidationDoesNotCommitSnapshot() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val harness = scanHarness(scanner, store)

        val scan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        assertEquals(1, scanner.deviceRequests.size)

        scan.cancelAndJoin()
        scanner.deviceRequests.single().result.complete(
            ScanResult(listOf(SongFixtures.song("too-late")), 1),
        )
        advanceUntilIdle()

        assertFalse(store.commitStarted.isCompleted)
        assertTrue(store.syncedSongs.isEmpty())
        assertTrue(harness.backing.songs.isEmpty())

        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun clearRequestedDuringFinalCommitRunsAfterAtomicPublicationAndWins() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val commitGate = CompletableDeferred<Unit>()
        store.commitGate = commitGate
        val harness = scanHarness(scanner, store)
        val published = SongFixtures.song("commit-before-clear")

        harness.orchestrator.launchScanDeviceWide()
        runCurrent()
        scanner.deviceRequests.single().result.complete(ScanResult(listOf(published), 1))
        runCurrent()
        assertTrue(store.commitStarted.isCompleted)

        harness.backing.folder.clearLibrary()
        runCurrent()
        assertFalse(store.clearCompleted)

        commitGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(store.clearCompleted)
        assertTrue(store.syncedSongs.isEmpty())
        assertTrue(harness.backing.songs.isEmpty())
        assertFalse(harness.backing.hasScanned)
        assertNull(harness.backing.lastScanAtMs)

        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun stalePreparedCustomOrderCannotOverwriteNewUserOrder() = runTest {
        val scanner = ControlledScanner()
        val harness = scanHarness(scanner)
        val context = harness.backing.context
        val a = SongFixtures.song("custom-a")
        val b = SongFixtures.song("custom-b")
        val c = SongFixtures.song("custom-c")

        LibraryBrowseSettings.setCustomSongOrderIds(context, listOf(a.id, b.id))
        try {
            val prepared = harness.backing.catalog.prepareLibrarySongs(
                raw = listOf(a, b, c),
                field = SongSortField.CUSTOM,
                direction = SortDirection.ASC,
                diagnosticTag = "LibraryTest",
                diagnosticReason = "purePrepare",
            )

            // Preparation itself must not persist its proposed appended order.
            assertEquals(
                listOf(a.id, b.id),
                LibraryBrowseSettings.customSongOrderIds(context),
            )

            LibraryBrowseSettings.setCustomSongOrderIds(context, listOf(b.id, a.id))
            harness.backing.presentationRevision++

            harness.backing.catalog.persistPreparedCustomOrderIfCurrent(prepared)

            assertEquals(
                listOf(b.id, a.id),
                LibraryBrowseSettings.customSongOrderIds(context),
            )
        } finally {
            LibraryBrowseSettings.setCustomSongOrderIds(context, emptyList())
            harness.backing.release()
            advanceUntilIdle()
        }
    }

    @Test
    fun folderAutoArtworkHydrateUsesDirectArtworkScannerWhileTagRefreshUsesMetadataScanner() = runTest {
        val scanner = ControlledScanner()
        val harness = scanHarness(scanner)
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic%2Ftest")
        val song = SongFixtures.song("artwork-direct").copy(
            mediaUri = "content://com.android.externalstorage.documents/document/primary%3AMusic%2Ftest%2Fsong.mp3",
            fileName = "song.mp3",
            folderPath = "",
            filePath = "song.mp3",
        )
        try {
            activateFolderSource(harness.backing, tree, "Music/test")
            harness.backing.replaceSongs(listOf(song))

            val artwork = async {
                harness.orchestrator.executeScheduled(
                    ScheduledLibraryOperation(
                        request = LibraryOperationRequest.TargetedRefresh(
                            songIds = setOf(song.id),
                            cause = LibraryOperationCause.AUTO_ARTWORK_HYDRATE,
                        ),
                        requestSequence = 4001L,
                        dirtySequenceAtStart = 8001L,
                    ),
                )
            }
            runCurrent()
            assertEquals(1, scanner.folderArtworkRequests.size)
            assertTrue(scanner.folderTargetedRequests.isEmpty())
            assertTrue(scanner.folderRequests.isEmpty())
            scanner.folderArtworkRequests.single().result.complete(ScanResult(listOf(song), 1))
            artwork.await()

            val tagRefresh = async {
                harness.orchestrator.executeScheduled(
                    ScheduledLibraryOperation(
                        request = LibraryOperationRequest.TargetedRefresh(
                            songIds = setOf(song.id),
                            cause = LibraryOperationCause.TAG_EDITOR_RETURN,
                        ),
                        requestSequence = 4002L,
                        dirtySequenceAtStart = 8002L,
                    ),
                )
            }
            runCurrent()
            assertEquals(1, scanner.folderArtworkRequests.size)
            assertEquals(1, scanner.folderTargetedRequests.size)
            assertTrue(scanner.folderRequests.isEmpty())
            scanner.folderTargetedRequests.single().result.complete(ScanResult(listOf(song), 1))
            tagRefresh.await()
        } finally {
            clearFolderPrefs(harness.backing)
            harness.backing.release()
            advanceUntilIdle()
        }
    }

    @Test
    fun schedulerMergesTargetedRefreshIdsWithoutCancellingRunningFullScan() = runTest {
        val scanner = ControlledScanner()
        val harness = scanHarness(scanner)
        val a = SongFixtures.song("target-a")
        val b = SongFixtures.song("target-b")
        harness.backing.replaceSongs(listOf(a, b))

        harness.orchestrator.launchScanDeviceWide()
        runCurrent()
        assertEquals(1, scanner.deviceRequests.size)

        harness.orchestrator.launchRefreshSongMetadata(a.id)
        harness.orchestrator.launchRefreshSongMetadata(b.id)
        runCurrent()

        // TARGETED work queues behind the running FULL instead of cancelling it.
        assertEquals(1, scanner.deviceRequests.size)

        scanner.deviceRequests[0].result.complete(ScanResult(listOf(a, b), 2))
        advanceUntilIdle()

        assertEquals(2, scanner.deviceRequests.size)
        assertEquals(
            setOf(a.id, b.id),
            scanner.deviceRequests[1].forceRefreshSongIds,
        )

        scanner.deviceRequests[1].result.complete(ScanResult(listOf(a, b), 2))
        advanceUntilIdle()

        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun fullScanHonorsPersistentUserExclusion() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val harness = scanHarness(scanner, store)
        val excluded = SongFixtures.song("excluded-by-user")
        store.userExclusions += LibraryUserExclusion(
            sourceIdentity = SourceIdentityKey.device(),
            stableObjectKey = excluded.id,
            exclusionRevision = 1L,
            createdAtMs = 1L,
        )

        val scan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests.single().result.complete(
            ScanResult(listOf(excluded), totalSizeMb = 1),
        )
        scan.await()

        assertTrue(harness.backing.songs.isEmpty())
        assertTrue(store.syncedSongs.isEmpty())
        assertEquals(
            listOf(excluded.id),
            store.userExclusions.map(LibraryUserExclusion::stableObjectKey),
        )

        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun checkpointOnlyAutoStateWriteAcceptsCurrentAutoTokenWithNoVisibleDelta() = runTest {
        val store = FakeLibraryStore()
        val harness = scanHarness(ControlledScanner(), store)
        activateDeviceSource(harness.backing)
        val token = requireNotNull(
            harness.backing.beginOperationToken(
                source = ScanSource.DEVICE,
                requestSequence = 41L,
                dirtySequenceAtStart = 7L,
                mode = LibraryOperationMode.AUTO_SYNC,
                cause = LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
            ),
        )
        val checkpoint = LibrarySyncCheckpoint(
            sourceIdentity = token.sourceIdentity,
            partitionKey = "mediastore:audio",
            providerVersion = "v1",
            generation = 10L,
            configFingerprint = token.configFingerprint,
            lastSuccessfulAutoSyncAtMs = 123L,
        )
        val mutation = LibraryAutoSyncStateMutation(
            sourceIdentity = token.sourceIdentity,
            checkpoints = listOf(checkpoint),
        )

        assertTrue(
            harness.backing.commitAutoSyncCheckpointOnlyIfCurrent(
                token = token,
                visibleDelta = AutoSyncVisibleDelta(),
                mutation = mutation,
            ),
        )
        assertEquals(listOf(mutation), store.autoSyncStateMutations)
        harness.backing.release()
    }

    @Test
    fun productionAutoSyncTokenBecomesStaleWhenGateClosesMidPass() = runTest {
        var enabled = true
        val store = FakeLibraryStore()
        val harness = scanHarness(
            scanner = ControlledScanner(),
            store = store,
            autoSyncEnabled = { enabled },
        )
        activateDeviceSource(harness.backing)
        val token = requireNotNull(
            harness.backing.beginActiveAutoSyncOperationToken(
                requestSequence = 410L,
                dirtySequenceAtStart = 7L,
                cause = LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
            ),
        )
        assertTrue(token.autoSyncGateEnforced)
        assertTrue(harness.backing.isCurrentOperationToken(token))

        enabled = false

        assertFalse(harness.backing.isCurrentOperationToken(token))
        assertFalse(
            harness.backing.commitAutoSyncCheckpointOnlyIfCurrent(
                token = token,
                visibleDelta = AutoSyncVisibleDelta(),
                mutation = LibraryAutoSyncStateMutation(token.sourceIdentity),
            ),
        )
        assertTrue(store.autoSyncStateMutations.isEmpty())
        harness.backing.release()
    }

    @Test
    fun diagnosticsAutoSyncTokenCanBypassProductionGate() = runTest {
        var enabled = true
        val harness = scanHarness(
            scanner = ControlledScanner(),
            autoSyncEnabled = { enabled },
        )
        activateDeviceSource(harness.backing)
        val token = requireNotNull(
            harness.backing.beginActiveAutoSyncOperationToken(
                requestSequence = 409L,
                dirtySequenceAtStart = 6L,
                cause = LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
                enforceAutoSyncGate = false,
            ),
        )
        assertFalse(token.autoSyncGateEnforced)

        enabled = false

        assertTrue(harness.backing.isCurrentOperationToken(token))
        harness.backing.release()
    }

    @Test
    fun cancellationAfterCheckpointFinalValidationCompletesDurableMutation() = runTest {
        val store = FakeLibraryStore()
        val gate = CompletableDeferred<Unit>()
        store.autoSyncStateMutationGate = gate
        val harness = scanHarness(ControlledScanner(), store)
        activateDeviceSource(harness.backing)
        val token = requireNotNull(
            harness.backing.beginOperationToken(
                source = ScanSource.DEVICE,
                requestSequence = 411L,
                dirtySequenceAtStart = 7L,
                mode = LibraryOperationMode.AUTO_SYNC,
                cause = LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
            ),
        )
        val mutation = LibraryAutoSyncStateMutation(
            sourceIdentity = token.sourceIdentity,
            checkpoints = listOf(
                LibrarySyncCheckpoint(
                    sourceIdentity = token.sourceIdentity,
                    partitionKey = "mediastore:audio",
                    providerVersion = "v1",
                    generation = 10L,
                    configFingerprint = token.configFingerprint,
                    lastSuccessfulAutoSyncAtMs = 123L,
                ),
            ),
        )

        val write = async {
            harness.backing.commitAutoSyncCheckpointOnlyIfCurrent(
                token = token,
                visibleDelta = AutoSyncVisibleDelta(),
                mutation = mutation,
            )
        }
        runCurrent()
        assertTrue(store.autoSyncStateMutationStarted.isCompleted)
        assertTrue(store.autoSyncStateMutations.isEmpty())

        write.cancel()
        runCurrent()
        assertTrue(store.autoSyncStateMutations.isEmpty())

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(mutation), store.autoSyncStateMutations)
        assertTrue(write.isCancelled)
        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun newOperationWaitsBehindCheckpointFinalCommit() = runTest {
        val store = FakeLibraryStore()
        val gate = CompletableDeferred<Unit>()
        store.autoSyncStateMutationGate = gate
        val harness = scanHarness(ControlledScanner(), store)
        activateDeviceSource(harness.backing)
        val token = requireNotNull(
            harness.backing.beginOperationToken(
                source = ScanSource.DEVICE,
                requestSequence = 412L,
                dirtySequenceAtStart = 8L,
                mode = LibraryOperationMode.AUTO_SYNC,
                cause = LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
            ),
        )
        val mutation = LibraryAutoSyncStateMutation(token.sourceIdentity)

        val write = async {
            harness.backing.commitAutoSyncCheckpointOnlyIfCurrent(
                token = token,
                visibleDelta = AutoSyncVisibleDelta(),
                mutation = mutation,
            )
        }
        runCurrent()
        assertTrue(store.autoSyncStateMutationStarted.isCompleted)

        val nextToken = async {
            harness.backing.beginOperationToken(
                source = ScanSource.DEVICE,
                requestSequence = 413L,
                dirtySequenceAtStart = 9L,
                mode = LibraryOperationMode.AUTO_SYNC,
                cause = LibraryOperationCause.MEDIASTORE_FILES_DIRTY,
            )
        }
        runCurrent()
        assertFalse(nextToken.isCompleted)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(mutation), store.autoSyncStateMutations)
        assertTrue(write.await())
        assertEquals(413L, requireNotNull(nextToken.await()).requestSequence)
        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun checkpointOnlyAutoStateWriteRejectsVisibleDeltaBeforeStoreMutation() = runTest {
        val store = FakeLibraryStore()
        val harness = scanHarness(ControlledScanner(), store)
        activateDeviceSource(harness.backing)
        val token = requireNotNull(
            harness.backing.beginOperationToken(
                source = ScanSource.DEVICE,
                requestSequence = 42L,
                dirtySequenceAtStart = 8L,
                mode = LibraryOperationMode.AUTO_SYNC,
                cause = LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
            ),
        )
        val mutation = LibraryAutoSyncStateMutation(token.sourceIdentity)

        val failure = runCatching {
            harness.backing.commitAutoSyncCheckpointOnlyIfCurrent(
                token = token,
                visibleDelta = AutoSyncVisibleDelta(updatedIds = setOf("changed")),
                mutation = mutation,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(store.autoSyncStateMutations.isEmpty())
        harness.backing.release()
    }

    @Test
    fun checkpointOnlyAutoStateWriteRejectsNonAutoToken() = runTest {
        val store = FakeLibraryStore()
        val harness = scanHarness(ControlledScanner(), store)
        activateDeviceSource(harness.backing)
        val token = requireNotNull(
            harness.backing.beginOperationToken(
                source = ScanSource.DEVICE,
                requestSequence = 43L,
                dirtySequenceAtStart = 0L,
                mode = LibraryOperationMode.FULL,
                cause = LibraryOperationCause.USER_RESCAN,
            ),
        )

        val failure = runCatching {
            harness.backing.commitAutoSyncCheckpointOnlyIfCurrent(
                token = token,
                visibleDelta = AutoSyncVisibleDelta(),
                mutation = LibraryAutoSyncStateMutation(token.sourceIdentity),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(store.autoSyncStateMutations.isEmpty())
        harness.backing.release()
    }

    @Test
    fun checkpointOnlyAutoStateWriteDropsStaleAutoToken() = runTest {
        val store = FakeLibraryStore()
        val harness = scanHarness(ControlledScanner(), store)
        activateDeviceSource(harness.backing)
        val stale = requireNotNull(
            harness.backing.beginOperationToken(
                source = ScanSource.DEVICE,
                requestSequence = 44L,
                dirtySequenceAtStart = 1L,
                mode = LibraryOperationMode.AUTO_SYNC,
                cause = LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
            ),
        )
        requireNotNull(
            harness.backing.beginOperationToken(
                source = ScanSource.DEVICE,
                requestSequence = 45L,
                dirtySequenceAtStart = 2L,
                mode = LibraryOperationMode.AUTO_SYNC,
                cause = LibraryOperationCause.MEDIASTORE_FILES_DIRTY,
            ),
        )

        assertFalse(
            harness.backing.commitAutoSyncCheckpointOnlyIfCurrent(
                token = stale,
                visibleDelta = AutoSyncVisibleDelta(),
                mutation = LibraryAutoSyncStateMutation(stale.sourceIdentity),
            ),
        )
        assertTrue(store.autoSyncStateMutations.isEmpty())
        harness.backing.release()
    }

    @Test
    fun s2ShadowAutoOperationHasNoScannerStoreOrCatalogSideEffects() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val harness = scanHarness(scanner, store)
        activateDeviceSource(harness.backing)
        val existing = SongFixtures.song("shadow-existing")
        harness.backing.replaceSongs(listOf(existing))
        harness.backing.lastScanAtMs = 321L
        harness.backing.lastScanSource = ScanSource.DEVICE
        val catalogRevisionBefore = harness.backing.catalogRevision
        val queueRevisionBefore = harness.backing.queueMetadataRevision
        val libraryChangeRevisionBefore = harness.backing.libraryChangeRevision

        harness.orchestrator.executeAutoSyncShadowForDiagnostics(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
                ),
                requestSequence = 90L,
                dirtySequenceAtStart = 12L,
            ),
        )

        assertTrue(scanner.deviceRequests.isEmpty())
        assertTrue(scanner.folderRequests.isEmpty())
        assertTrue(store.syncedSongs.isEmpty())
        assertTrue(store.autoSyncStateMutations.isEmpty())
        assertEquals(0, store.autoSyncSnapshotCommitCount)
        assertEquals(listOf(existing.id), harness.backing.songs.map(Song::id))
        assertEquals(catalogRevisionBefore, harness.backing.catalogRevision)
        assertEquals(queueRevisionBefore, harness.backing.queueMetadataRevision)
        assertEquals(libraryChangeRevisionBefore, harness.backing.libraryChangeRevision)
        assertEquals(321L, harness.backing.lastScanAtMs)
        assertFalse(harness.backing.isScanning)
        assertFalse(harness.backing.isUserVisibleScanning)
        harness.backing.release()
    }

    @Test
    fun productionAutoSyncGateBlocksDeviceButManualDeviceScanStillPublishes() = runTest {
        val scanner = ControlledScanner()
        val shadow = ControlledDeviceAutoSyncShadow(
            observation = DeviceAutoSyncShadowObservation.Disabled,
        )
        val harness = scanHarness(
            scanner = scanner,
            deviceAutoSyncShadow = shadow,
            autoSyncEnabled = { false },
        )
        activateDeviceSource(harness.backing)
        val generationBefore = harness.backing.scanGeneration

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
                ),
                requestSequence = 188L,
                dirtySequenceAtStart = 53L,
            ),
        )

        assertEquals(0, shadow.observeCalls)
        assertEquals(generationBefore, harness.backing.scanGeneration)
        assertTrue(scanner.deviceRequests.isEmpty())

        val manual = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        assertEquals(1, scanner.deviceRequests.size)
        scanner.deviceRequests.single().result.complete(
            ScanResult(listOf(SongFixtures.song("manual-device")), 1),
        )
        manual.await()

        assertEquals(listOf("manual-device"), harness.backing.songs.map(Song::id))
        assertEquals(ScanSource.DEVICE, harness.backing.lastScanSource)
        harness.backing.release()
    }

    @Test
    fun productionAutoSyncGateBlocksFolderButManualFolderScanStillPublishes() = runTest {
        val tree = Uri.parse("content://library-auto-sync-gate/tree")
        val scanner = ControlledScanner()
        val harness = scanHarness(
            scanner = scanner,
            autoSyncEnabled = { source -> source != ScanSource.FOLDER },
        )
        try {
            activateFolderSource(harness.backing, tree, "Gate")
            val generationBefore = harness.backing.scanGeneration

            harness.orchestrator.executeScheduled(
                ScheduledLibraryOperation(
                    request = LibraryOperationRequest.AutoSync(
                        LibraryOperationCause.SAF_PERIODIC_VERIFY,
                    ),
                    requestSequence = 189L,
                    dirtySequenceAtStart = 54L,
                ),
            )

            assertEquals(generationBefore, harness.backing.scanGeneration)
            assertTrue(scanner.folderMetadataRequests.isEmpty())
            assertTrue(scanner.folderRequests.isEmpty())

            val manual = async { harness.orchestrator.scanLibraryFolder() }
            runCurrent()
            assertEquals(1, scanner.folderRequests.size)
            scanner.folderRequests.single().result.complete(
                ScanResult(listOf(SongFixtures.song("manual-folder")), 1),
            )
            manual.await()

            assertEquals(listOf("manual-folder"), harness.backing.songs.map(Song::id))
            assertEquals(ScanSource.FOLDER, harness.backing.lastScanSource)
        } finally {
            clearFolderPrefs(harness.backing)
            harness.backing.release()
        }
    }

    @Test
    fun scheduledDeviceAutoBaselineCandidateDoesNotCreateAuthorityWithoutFullAnchor() = runTest {
        val store = FakeLibraryStore()
        val shadow = ControlledDeviceAutoSyncShadow(
            observation = DeviceAutoSyncShadowObservation.BaselineCandidate(
                snapshot = deviceGenerationSnapshot(10L),
                reason = com.mica.music.data.scanner.DeviceGenerationReconcileReason.BASELINE_MISSING,
            ),
        )
        val harness = scanHarness(
            scanner = ControlledScanner(),
            store = store,
            deviceAutoSyncShadow = shadow,
        )
        activateDeviceSource(harness.backing)

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
                ),
                requestSequence = 189L,
                dirtySequenceAtStart = 54L,
            ),
        )

        assertEquals(1, shadow.observeCalls)
        assertEquals(0, store.autoSyncSnapshotCommitCount)
        assertTrue(store.autoSyncStateMutations.isEmpty())
        harness.backing.release()
    }

    @Test
    fun scheduledDeviceAutoPublishesValidatedObjectAndCheckpointTogether() = runTest {
        val existing = SongFixtures.song("ms_194").copy(
            title = "Before",
            mediaUri = "content://media/external_primary/audio/media/194",
            fileName = "track194.flac",
            folderPath = "Music",
            filePath = "Music/track194.flac",
            sizeBytes = 194L,
            dateModifiedMs = 2_000L,
        )
        val row = DeviceDeltaRow(
            channel = DeviceDeltaChannel.AUDIO,
            volumeName = "external_primary",
            mediaStoreId = 194L,
            mediaUri = existing.mediaUri,
            displayName = existing.fileName,
            mimeType = "audio/flac",
            relativePath = "Music/",
            sizeBytes = existing.sizeBytes,
            dateModifiedMs = 3_000L,
            generationAdded = 12L,
            generationModified = 12L,
            eligibility = LibraryEligibility.ELIGIBLE,
        )
        val observation = DeviceAutoSyncShadowObservation.DeltaCandidate(
            batch = emptyDeviceDeltaBatch(10L, 12L).copy(rows = listOf(row)),
            lyricsSidecarInventory = completeLyricsInventory(),
            presenceInventory = completePresenceInventoryFor(existing),
            advanceTo = deviceGenerationSnapshot(12L),
            followUpRequired = false,
        )
        val shadow = ControlledDeviceAutoSyncShadow(observation = observation)
        val updated = existing.copy(
            title = "After",
            dateModifiedMs = row.dateModifiedMs,
        )
        val runtime = DeviceShadowProbeRuntime { request ->
            assertEquals(existing.id, request.probePlan.ready.single().stableObjectKey)
            DeviceShadowProbeExecutionResult(
                resolvedObjectsByStableObjectKey = emptyMap(),
                resolvedSongsByStableObjectKey = mapOf(existing.id to updated),
                issues = emptyList(),
            )
        }
        val store = FakeLibraryStore()
        val harness = scanHarness(
            scanner = ControlledScanner(),
            store = store,
            deviceAutoSyncShadow = shadow,
            deviceShadowProbeRuntime = runtime,
        )
        activateDeviceSource(harness.backing)
        harness.backing.lastScanAtMs = 555L
        harness.backing.lastScanSource = ScanSource.DEVICE
        harness.backing.replaceSongs(listOf(existing))

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
                ),
                requestSequence = 190L,
                dirtySequenceAtStart = 55L,
            ),
        )

        assertEquals("After", harness.backing.songs.single().title)
        assertEquals(1, store.autoSyncSnapshotCommitCount)
        val mutation = store.autoSyncStateMutations.single()
        assertEquals(12L, mutation.checkpoints.single().generation)
        assertEquals(1, shadow.accepted.size)
        assertEquals(1L, harness.backing.libraryChangeRevision)
        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun s4SafDiagnosticsUsesActiveTreeMetadataOnlyAndHasNoPublicationSideEffects() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val harness = scanHarness(scanner, store)
        val activeTree = Uri.parse("content://provider/tree/active")
        val pendingTree = Uri.parse("content://provider/tree/pending")
        activateFolderSource(harness.backing, activeTree, "Active")

        val existing = SongFixtures.song("doc-active").copy(
            mediaUri = "content://provider/document/audio-1",
            fileName = "song.flac",
            folderPath = "Album",
            filePath = "Album/song.flac",
            sizeBytes = 1_234L,
            dateModifiedMs = 5_678L,
            externalLyricsSignature = "lyrics:v1",
        )
        harness.backing.replaceSongs(listOf(existing))
        harness.backing.pendingLibraryFolderUri = pendingTree.toString()
        harness.backing.pendingLibraryFolderLabel = "Pending"
        scanner.folderMetadataSnapshot = SafTreeMetadataSnapshot(
            entries = listOf(
                SafTreeMetadataEntry(
                    stableObjectKey = existing.id,
                    mediaUri = existing.mediaUri,
                    fileName = existing.fileName,
                    folderPath = existing.folderPath,
                    filePath = existing.filePath,
                    mimeType = existing.metadata.playbackMimeType,
                    sizeBytes = existing.sizeBytes,
                    lastModifiedMs = existing.dateModifiedMs,
                    externalLyricsSignature = existing.externalLyricsSignature,
                ),
            ),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    completeness = DiscoveryCompleteness.COMPLETE,
                ),
            ),
        )
        val catalogRevisionBefore = harness.backing.catalogRevision
        val queueRevisionBefore = harness.backing.queueMetadataRevision
        val libraryChangeRevisionBefore = harness.backing.libraryChangeRevision

        harness.orchestrator.executeAutoSyncShadowForDiagnostics(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.SAF_PERIODIC_VERIFY,
                ),
                requestSequence = 190L,
                dirtySequenceAtStart = 55L,
            ),
        )

        assertEquals(listOf(activeTree), scanner.folderMetadataRequests)
        assertTrue(scanner.folderRequests.isEmpty())
        assertTrue(scanner.deviceRequests.isEmpty())
        assertTrue(store.syncedSongs.isEmpty())
        assertTrue(store.autoSyncStateMutations.isEmpty())
        assertEquals(0, store.autoSyncSnapshotCommitCount)
        assertEquals(listOf(existing.id), harness.backing.songs.map(Song::id))
        assertEquals(catalogRevisionBefore, harness.backing.catalogRevision)
        assertEquals(queueRevisionBefore, harness.backing.queueMetadataRevision)
        assertEquals(libraryChangeRevisionBefore, harness.backing.libraryChangeRevision)
        assertEquals(pendingTree.toString(), harness.backing.pendingLibraryFolderUri)

        clearFolderPrefs(harness.backing)
        harness.backing.release()
    }

    @Test
    fun s4SafShadowExecutesChangedOnlyProbeAndPostValidatesWithoutPublishing() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val tree = Uri.parse("content://provider/tree/active")
        val existing = SongFixtures.song("doc-probe").copy(
            mediaUri = "content://provider/document/audio-probe",
            fileName = "song.flac",
            folderPath = "Album",
            filePath = "Album/song.flac",
            sizeBytes = 1_234L,
            dateModifiedMs = 5_678L,
        )
        val observed = SafTreeMetadataEntry(
            stableObjectKey = existing.id,
            mediaUri = existing.mediaUri,
            fileName = existing.fileName,
            folderPath = existing.folderPath,
            filePath = existing.filePath,
            mimeType = existing.metadata.playbackMimeType,
            sizeBytes = existing.sizeBytes + 1L,
            lastModifiedMs = existing.dateModifiedMs + 1L,
            externalLyricsSignature = existing.externalLyricsSignature,
        )
        scanner.folderMetadataSnapshot = SafTreeMetadataSnapshot(
            entries = listOf(observed),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    completeness = DiscoveryCompleteness.COMPLETE,
                ),
            ),
        )
        var runtimeCalls = 0
        var readyKeys = emptyList<String>()
        val runtime = SafShadowProbeRuntime { request ->
            runtimeCalls += 1
            readyKeys = request.probePlan.ready.map(SafAutoProbeObjectPlan::stableObjectKey)
            SafShadowProbeExecutionResult(
                provisionalSongsByStableObjectKey = mapOf(
                    existing.id to existing.copy(
                        title = "shadow-updated",
                        sizeBytes = observed.sizeBytes,
                        dateModifiedMs = observed.lastModifiedMs,
                    ),
                ),
                attemptedCount = 1,
            )
        }
        val harness = scanHarness(
            scanner = scanner,
            store = store,
            safShadowProbeRuntime = runtime,
        )
        activateFolderSource(harness.backing, tree, "Active")
        harness.backing.replaceSongs(listOf(existing))
        val catalogRevisionBefore = harness.backing.catalogRevision

        harness.orchestrator.executeAutoSyncShadowForDiagnostics(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.SAF_PERIODIC_VERIFY,
                ),
                requestSequence = 193L,
                dirtySequenceAtStart = 58L,
            ),
        )

        assertEquals(1, runtimeCalls)
        assertEquals(listOf(existing.id), readyKeys)
        assertEquals(listOf(tree, tree), scanner.folderMetadataRequests)
        assertTrue(scanner.folderRequests.isEmpty())
        assertTrue(store.syncedSongs.isEmpty())
        assertEquals(0, store.autoSyncSnapshotCommitCount)
        assertEquals(catalogRevisionBefore, harness.backing.catalogRevision)
        assertEquals(existing.title, harness.backing.songs.single().title)

        clearFolderPrefs(harness.backing)
        harness.backing.release()
    }

    @Test
    fun s4ScheduledSafAutoPublishesValidatedObjectAndCheckpointTogether() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val tree = Uri.parse("content://provider/tree/production")
        val existing = SongFixtures.song("doc-production").copy(
            mediaUri = "content://provider/document/audio-production",
            fileName = "song.flac",
            folderPath = "Album",
            filePath = "Album/song.flac",
            sizeBytes = 1_234L,
            dateModifiedMs = 5_678L,
            title = "Before",
        )
        val observed = SafTreeMetadataEntry(
            stableObjectKey = existing.id,
            mediaUri = existing.mediaUri,
            fileName = existing.fileName,
            folderPath = existing.folderPath,
            filePath = existing.filePath,
            mimeType = existing.metadata.playbackMimeType,
            sizeBytes = existing.sizeBytes + 1L,
            lastModifiedMs = existing.dateModifiedMs + 1L,
            externalLyricsSignature = existing.externalLyricsSignature,
        )
        scanner.folderMetadataSnapshot = SafTreeMetadataSnapshot(
            entries = listOf(observed),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    completeness = DiscoveryCompleteness.COMPLETE,
                ),
            ),
        )
        val runtime = SafShadowProbeRuntime {
            SafShadowProbeExecutionResult(
                provisionalSongsByStableObjectKey = mapOf(
                    existing.id to existing.copy(
                        title = "After",
                        sizeBytes = observed.sizeBytes,
                        dateModifiedMs = observed.lastModifiedMs,
                    ),
                ),
                attemptedCount = 1,
            )
        }
        val harness = scanHarness(
            scanner = scanner,
            store = store,
            safShadowProbeRuntime = runtime,
        )
        activateFolderSource(harness.backing, tree, "Production")
        harness.backing.replaceSongs(listOf(existing))
        harness.backing.lastScanAtMs = 500L
        harness.backing.lastScanSource = ScanSource.FOLDER

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.SAF_PERIODIC_VERIFY,
                ),
                requestSequence = 194L,
                dirtySequenceAtStart = 59L,
            ),
        )

        assertEquals(1, store.autoSyncSnapshotCommitCount)
        assertEquals("After", harness.backing.songs.single().title)
        assertEquals(1L, harness.backing.libraryChangeRevision)
        val mutation = store.autoSyncStateMutations.single()
        assertEquals(DiscoveryPartitions.SAF_TREE, mutation.checkpoints.single().partitionKey)
        assertEquals(1L, harness.backing.lastLibraryChangeSet?.libraryRevision)

        clearFolderPrefs(harness.backing)
        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun s4SafMassDeletionQuarantinePersistsDebtThenIndependentRetryPublishesRemoval() = runTest {
        var nowMs = 1_000L
        val environment = FakeScanEnvironment(
            nowMsProvider = { nowMs },
            elapsedMsProvider = { nowMs },
        )
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val tree = Uri.parse("content://provider/tree/mass-delete-production")
        val current = List(100) { index ->
            SongFixtures.song("saf-mass-${index + 1}").copy(
                mediaUri = "content://provider/document/saf-mass-${index + 1}",
                fileName = "song-${index + 1}.flac",
                folderPath = "Album",
                filePath = "Album/song-${index + 1}.flac",
                sizeBytes = 10_000L + index,
                dateModifiedMs = 20_000L + index,
            )
        }
        val kept = current.last()
        val removedIds = current.dropLast(1).mapTo(linkedSetOf(), Song::id)
        val keptEntry = SafTreeMetadataEntry(
            stableObjectKey = kept.id,
            mediaUri = kept.mediaUri,
            fileName = kept.fileName,
            folderPath = kept.folderPath,
            filePath = kept.filePath,
            mimeType = kept.metadata.playbackMimeType,
            sizeBytes = kept.sizeBytes,
            lastModifiedMs = kept.dateModifiedMs,
            externalLyricsSignature = kept.externalLyricsSignature,
        )
        scanner.folderMetadataSnapshot = SafTreeMetadataSnapshot(
            entries = listOf(keptEntry),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    completeness = DiscoveryCompleteness.COMPLETE,
                ),
            ),
        )
        val harness = scanHarness(
            scanner = scanner,
            store = store,
            environment = environment,
        )
        activateFolderSource(harness.backing, tree, "Mass delete")
        harness.backing.replaceSongs(current)
        harness.backing.lastScanAtMs = 500L
        harness.backing.lastScanSource = ScanSource.FOLDER

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.SAF_TREE_DIRTY,
                ),
                requestSequence = 1941L,
                dirtySequenceAtStart = 591L,
            ),
        )

        assertEquals(100, harness.backing.songs.size)
        assertTrue(scanner.folderMissingVerificationRequests.isEmpty())
        val firstRetry = store.retryItems.single {
            it.retryKey == LibraryRetryKey.safMassDeletionVerify()
        }
        assertEquals(LibraryRetryKind.DISCOVERY_PARTITION, firstRetry.retryKind)
        assertEquals(1, firstRetry.attemptCount)
        assertEquals(31_000L, firstRetry.nextRetryAtMs)
        assertEquals(0, store.autoSyncSnapshotCommitCount)

        scanner.folderMissingVerificationResult = SafIndependentMissingVerificationResult(
            verifiedMissingStableObjectKeys = removedIds,
            presentStableObjectKeys = emptySet(),
            indeterminateStableObjectKeys = emptySet(),
            providerQueryCount = removedIds.size,
            wallTimeMs = 123L,
        )
        nowMs = firstRetry.nextRetryAtMs
        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.SAF_RETRY_DUE,
                ),
                requestSequence = 1942L,
                dirtySequenceAtStart = 592L,
            ),
        )

        assertEquals(1, scanner.folderMissingVerificationRequests.size)
        assertEquals(removedIds, scanner.folderMissingVerificationRequests.single().toSet())
        assertEquals(listOf(kept.id), harness.backing.songs.map(Song::id))
        assertEquals(1, store.autoSyncSnapshotCommitCount)
        assertTrue(store.retryItems.none { it.retryKey == LibraryRetryKey.safMassDeletionVerify() })
        assertEquals(removedIds, harness.backing.lastLibraryChangeSet?.membershipChanges
            ?.mapTo(linkedSetOf(), MembershipChange::stableObjectKey))
        assertEquals(removedIds, harness.backing.lastLibraryChangeSet?.membershipChanges
            ?.filter { it.reason == MembershipRemovalReason.CONFIRMED_MISSING }
            ?.mapTo(linkedSetOf(), MembershipChange::stableObjectKey))

        clearFolderPrefs(harness.backing)
        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun s4SafMassDeletionPartialRetryPreservesDurableDebtContinuity() = runTest {
        var nowMs = 1_000L
        val environment = FakeScanEnvironment(
            nowMsProvider = { nowMs },
            elapsedMsProvider = { nowMs },
        )
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val tree = Uri.parse("content://provider/tree/mass-delete-partial")
        val current = List(100) { index ->
            SongFixtures.song("saf-partial-${index + 1}").copy(
                mediaUri = "content://provider/document/saf-partial-${index + 1}",
                fileName = "song-${index + 1}.flac",
                folderPath = "Album",
                filePath = "Album/song-${index + 1}.flac",
                sizeBytes = 10_000L + index,
                dateModifiedMs = 20_000L + index,
            )
        }
        val kept = current.last()
        val keptEntry = SafTreeMetadataEntry(
            stableObjectKey = kept.id, mediaUri = kept.mediaUri, fileName = kept.fileName,
            folderPath = kept.folderPath, filePath = kept.filePath,
            mimeType = kept.metadata.playbackMimeType, sizeBytes = kept.sizeBytes,
            lastModifiedMs = kept.dateModifiedMs,
            externalLyricsSignature = kept.externalLyricsSignature,
        )
        scanner.folderMetadataSnapshot = SafTreeMetadataSnapshot(
            entries = listOf(keptEntry),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    completeness = DiscoveryCompleteness.COMPLETE,
                ),
            ),
        )
        val harness = scanHarness(scanner, store, environment)
        activateFolderSource(harness.backing, tree, "Mass delete partial")
        harness.backing.replaceSongs(current)
        harness.backing.lastScanAtMs = 500L
        harness.backing.lastScanSource = ScanSource.FOLDER

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                LibraryOperationRequest.AutoSync(LibraryOperationCause.SAF_TREE_DIRTY),
                requestSequence = 19421L,
                dirtySequenceAtStart = 5921L,
            ),
        )
        val firstRetry = store.retryItems.single {
            it.retryKey == LibraryRetryKey.safMassDeletionVerify()
        }.copy(attemptCount = 4, continuationCursor = 64, nextRetryAtMs = 31_000L)
        store.retryItems.removeAll { it.retryKey == LibraryRetryKey.safMassDeletionVerify() }
        store.retryItems += firstRetry

        scanner.folderMetadataSnapshot = SafTreeMetadataSnapshot(
            entries = listOf(keptEntry),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    completeness = DiscoveryCompleteness.PARTIAL,
                    detail = "provider-temporarily-incomplete",
                ),
            ),
        )
        nowMs = firstRetry.nextRetryAtMs
        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                LibraryOperationRequest.AutoSync(LibraryOperationCause.SAF_RETRY_DUE),
                requestSequence = 19422L,
                dirtySequenceAtStart = 5922L,
            ),
        )

        val preserved = store.retryItems.single {
            it.retryKey == LibraryRetryKey.safMassDeletionVerify()
        }
        assertEquals(firstRetry, preserved)
        assertEquals(100, harness.backing.songs.size)
        assertTrue(scanner.folderMissingVerificationRequests.isEmpty())

        clearFolderPrefs(harness.backing)
        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun s4SafMassDeletionConfirmationContinuesInBoundedBatchesWithoutAttemptInflation() = runTest {
        var nowMs = 1_000L
        val environment = FakeScanEnvironment(
            nowMsProvider = { nowMs },
            elapsedMsProvider = { nowMs },
        )
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val tree = Uri.parse("content://provider/tree/mass-delete-batched")
        val current = List(100) { index ->
            SongFixtures.song("saf-batched-${index + 1}").copy(
                mediaUri = "content://provider/document/saf-batched-${index + 1}",
                fileName = "song-${index + 1}.flac",
                folderPath = "Album",
                filePath = "Album/song-${index + 1}.flac",
                sizeBytes = 50_000L + index,
                dateModifiedMs = 60_000L + index,
            )
        }
        val kept = current.last()
        val removedSongs = current.dropLast(1).sortedBy(Song::id)
        val keptEntry = SafTreeMetadataEntry(
            stableObjectKey = kept.id, mediaUri = kept.mediaUri, fileName = kept.fileName,
            folderPath = kept.folderPath, filePath = kept.filePath,
            mimeType = kept.metadata.playbackMimeType, sizeBytes = kept.sizeBytes,
            lastModifiedMs = kept.dateModifiedMs,
            externalLyricsSignature = kept.externalLyricsSignature,
        )
        scanner.folderMetadataSnapshot = SafTreeMetadataSnapshot(
            entries = listOf(keptEntry),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    completeness = DiscoveryCompleteness.COMPLETE,
                ),
            ),
        )
        val harness = scanHarness(scanner, store, environment)
        activateFolderSource(harness.backing, tree, "Mass delete batched")
        harness.backing.replaceSongs(current)
        harness.backing.lastScanAtMs = 500L
        harness.backing.lastScanSource = ScanSource.FOLDER

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                LibraryOperationRequest.AutoSync(LibraryOperationCause.SAF_TREE_DIRTY),
                requestSequence = 19431L,
                dirtySequenceAtStart = 5931L,
            ),
        )
        val firstRetry = store.retryItems.single { it.retryKey == LibraryRetryKey.safMassDeletionVerify() }
        assertEquals(1, firstRetry.attemptCount)
        assertEquals(0, firstRetry.continuationCursor)

        val firstBatch = removedSongs.take(SafMissingVerificationBudget.DEFAULT_MAX_OBJECTS)
            .mapTo(linkedSetOf(), Song::id)
        scanner.folderMissingVerificationResult = SafIndependentMissingVerificationResult(
            verifiedMissingStableObjectKeys = firstBatch,
            presentStableObjectKeys = emptySet(),
            indeterminateStableObjectKeys = emptySet(),
            providerQueryCount = firstBatch.size,
            wallTimeMs = 200L,
            nextCursor = firstBatch.size,
            hasMore = true,
            budgetExhausted = true,
        )
        nowMs = firstRetry.nextRetryAtMs
        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                LibraryOperationRequest.AutoSync(LibraryOperationCause.SAF_RETRY_DUE),
                requestSequence = 19432L,
                dirtySequenceAtStart = 5932L,
            ),
        )

        assertEquals(100, harness.backing.songs.size)
        val continued = store.retryItems.single { it.retryKey == LibraryRetryKey.safMassDeletionVerify() }
        assertEquals(1, continued.attemptCount)
        assertEquals(firstBatch.size, continued.continuationCursor)
        assertEquals(firstRetry.observedFingerprint, continued.observedFingerprint)
        assertEquals(
            SafMassDeletionConfirmationPlanner.encodeConfirmedMissingKeys(firstBatch),
            continued.confirmedMissingKeysPayload,
        )
        assertEquals(listOf(0), scanner.folderMissingVerificationCursors)

        val secondBatch = removedSongs.drop(firstBatch.size).mapTo(linkedSetOf(), Song::id)
        scanner.folderMissingVerificationResult = SafIndependentMissingVerificationResult(
            verifiedMissingStableObjectKeys = secondBatch,
            presentStableObjectKeys = emptySet(),
            indeterminateStableObjectKeys = emptySet(),
            providerQueryCount = secondBatch.size,
            wallTimeMs = 120L,
            nextCursor = removedSongs.size,
            hasMore = false,
        )
        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                LibraryOperationRequest.AutoSync(LibraryOperationCause.SAF_BUDGET_CONTINUATION),
                requestSequence = 19433L,
                dirtySequenceAtStart = 5933L,
            ),
        )

        assertEquals(listOf(0, firstBatch.size), scanner.folderMissingVerificationCursors)
        assertEquals(listOf(kept.id), harness.backing.songs.map(Song::id))
        assertTrue(store.retryItems.none { it.retryKey == LibraryRetryKey.safMassDeletionVerify() })
        assertEquals(1, store.autoSyncSnapshotCommitCount)

        clearFolderPrefs(harness.backing)
        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun s4SafMassDeletionConfirmationProgressIsDroppedOnceQuarantineClearsAndNotReusedLater() = runTest {
        var nowMs = 1_000L
        val environment = FakeScanEnvironment(
            nowMsProvider = { nowMs },
            elapsedMsProvider = { nowMs },
        )
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val tree = Uri.parse("content://provider/tree/mass-delete-stale-proof")
        val current = List(100) { index ->
            SongFixtures.song("saf-stale-proof-${index + 1}").copy(
                mediaUri = "content://provider/document/saf-stale-proof-${index + 1}",
                fileName = "song-${index + 1}.flac",
                folderPath = "Album",
                filePath = "Album/song-${index + 1}.flac",
                sizeBytes = 50_000L + index,
                dateModifiedMs = 60_000L + index,
            )
        }
        fun entry(song: Song) = SafTreeMetadataEntry(
            stableObjectKey = song.id, mediaUri = song.mediaUri, fileName = song.fileName,
            folderPath = song.folderPath, filePath = song.filePath,
            mimeType = song.metadata.playbackMimeType, sizeBytes = song.sizeBytes,
            lastModifiedMs = song.dateModifiedMs,
            externalLyricsSignature = song.externalLyricsSignature,
        )
        val complete = DiscoveryReport.of(
            DiscoveryPartitionStatus(
                partitionKey = DiscoveryPartitions.SAF_TREE,
                completeness = DiscoveryCompleteness.COMPLETE,
            ),
        )
        val kept = current.last()
        val removedSongs = current.dropLast(1).sortedBy(Song::id)
        val onlyKeptSnapshot =
            SafTreeMetadataSnapshot(entries = listOf(entry(kept)), discoveryReport = complete)
        val allPresentSnapshot =
            SafTreeMetadataSnapshot(entries = current.map(::entry), discoveryReport = complete)
        scanner.folderMetadataSnapshot = onlyKeptSnapshot
        val harness = scanHarness(scanner, store, environment)
        activateFolderSource(harness.backing, tree, "Mass delete stale proof")
        harness.backing.replaceSongs(current)
        harness.backing.lastScanAtMs = 500L
        harness.backing.lastScanSource = ScanSource.FOLDER

        // Round 1: 99 of 100 disappear -> quarantine + durable debt.
        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                LibraryOperationRequest.AutoSync(LibraryOperationCause.SAF_TREE_DIRTY),
                requestSequence = 29431L,
                dirtySequenceAtStart = 6931L,
            ),
        )
        val firstRetry = store.retryItems.single { it.retryKey == LibraryRetryKey.safMassDeletionVerify() }
        assertEquals(0, firstRetry.continuationCursor)

        // Round 1 continues: first 64 independently confirmed missing, cursor persisted.
        val firstBatch = removedSongs.take(SafMissingVerificationBudget.DEFAULT_MAX_OBJECTS)
            .mapTo(linkedSetOf(), Song::id)
        scanner.folderMissingVerificationResult = SafIndependentMissingVerificationResult(
            verifiedMissingStableObjectKeys = firstBatch,
            presentStableObjectKeys = emptySet(),
            indeterminateStableObjectKeys = emptySet(),
            providerQueryCount = firstBatch.size,
            wallTimeMs = 200L,
            nextCursor = firstBatch.size,
            hasMore = true,
            budgetExhausted = true,
        )
        nowMs = firstRetry.nextRetryAtMs
        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                LibraryOperationRequest.AutoSync(LibraryOperationCause.SAF_RETRY_DUE),
                requestSequence = 29432L,
                dirtySequenceAtStart = 6932L,
            ),
        )
        val continued = store.retryItems.single { it.retryKey == LibraryRetryKey.safMassDeletionVerify() }
        assertEquals(firstBatch.size, continued.continuationCursor)
        assertEquals(100, harness.backing.songs.size)

        // All 100 are observed present again before the continuation runs. The pass publishes with
        // no quarantine, which is the evidence that the persisted proof is stale: it must be dropped.
        scanner.folderMetadataSnapshot = allPresentSnapshot
        scanner.folderMissingVerificationResult = null
        nowMs += 10L
        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                LibraryOperationRequest.AutoSync(LibraryOperationCause.SAF_BUDGET_CONTINUATION),
                requestSequence = 29433L,
                dirtySequenceAtStart = 6933L,
            ),
        )
        assertEquals(100, harness.backing.songs.size)
        assertEquals(listOf(0), scanner.folderMissingVerificationCursors)
        assertTrue(store.retryItems.none { it.retryKey == LibraryRetryKey.safMassDeletionVerify() })

        // Round 2: the same 99 disappear again. This must start a fresh round: a new 30s debt at
        // cursor 0, no verification on this pass, and nothing removed on the strength of round 1.
        scanner.folderMetadataSnapshot = onlyKeptSnapshot
        scanner.folderMissingVerificationResult = SafIndependentMissingVerificationResult(
            verifiedMissingStableObjectKeys = removedSongs.drop(firstBatch.size).mapTo(linkedSetOf(), Song::id),
            presentStableObjectKeys = emptySet(),
            indeterminateStableObjectKeys = emptySet(),
            providerQueryCount = removedSongs.size - firstBatch.size,
            wallTimeMs = 120L,
            nextCursor = removedSongs.size,
            hasMore = false,
        )
        nowMs += 10_000L
        val roundTwoStartMs = nowMs
        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                LibraryOperationRequest.AutoSync(LibraryOperationCause.SAF_TREE_DIRTY),
                requestSequence = 29434L,
                dirtySequenceAtStart = 6934L,
            ),
        )
        assertEquals(100, harness.backing.songs.size)
        assertEquals(listOf(0), scanner.folderMissingVerificationCursors)
        val freshDebt = store.retryItems.single { it.retryKey == LibraryRetryKey.safMassDeletionVerify() }
        assertEquals(1, freshDebt.attemptCount)
        assertEquals(0, freshDebt.continuationCursor)
        assertEquals(roundTwoStartMs + 30_000L, freshDebt.nextRetryAtMs)

        // When round 2 is due, verification restarts from cursor 0, not from round 1's progress.
        scanner.folderMissingVerificationResult = SafIndependentMissingVerificationResult(
            verifiedMissingStableObjectKeys = firstBatch,
            presentStableObjectKeys = emptySet(),
            indeterminateStableObjectKeys = emptySet(),
            providerQueryCount = firstBatch.size,
            wallTimeMs = 200L,
            nextCursor = firstBatch.size,
            hasMore = true,
            budgetExhausted = true,
        )
        nowMs = freshDebt.nextRetryAtMs
        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                LibraryOperationRequest.AutoSync(LibraryOperationCause.SAF_RETRY_DUE),
                requestSequence = 29435L,
                dirtySequenceAtStart = 6935L,
            ),
        )
        assertEquals(listOf(0, 0), scanner.folderMissingVerificationCursors)
        assertEquals(100, harness.backing.songs.size)

        clearFolderPrefs(harness.backing)
        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun s4SafMassDeletionZeroProgressVerificationBacksOffInsteadOfSpinning() = runTest {
        var nowMs = 1_000L
        val environment = FakeScanEnvironment(
            nowMsProvider = { nowMs },
            elapsedMsProvider = { nowMs },
        )
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val tree = Uri.parse("content://provider/tree/mass-delete-zero-progress")
        val current = List(100) { index ->
            SongFixtures.song("saf-zero-progress-${index + 1}").copy(
                mediaUri = "content://provider/document/saf-zero-progress-${index + 1}",
                fileName = "song-${index + 1}.flac",
                folderPath = "Album",
                filePath = "Album/song-${index + 1}.flac",
                sizeBytes = 50_000L + index,
                dateModifiedMs = 60_000L + index,
            )
        }
        val kept = current.last()
        scanner.folderMetadataSnapshot = SafTreeMetadataSnapshot(
            entries = listOf(
                SafTreeMetadataEntry(
                    stableObjectKey = kept.id, mediaUri = kept.mediaUri, fileName = kept.fileName,
                    folderPath = kept.folderPath, filePath = kept.filePath,
                    mimeType = kept.metadata.playbackMimeType, sizeBytes = kept.sizeBytes,
                    lastModifiedMs = kept.dateModifiedMs,
                    externalLyricsSignature = kept.externalLyricsSignature,
                ),
            ),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    completeness = DiscoveryCompleteness.COMPLETE,
                ),
            ),
        )
        val harness = scanHarness(scanner, store, environment)
        activateFolderSource(harness.backing, tree, "Mass delete zero progress")
        harness.backing.replaceSongs(current)
        harness.backing.lastScanAtMs = 500L
        harness.backing.lastScanSource = ScanSource.FOLDER

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                LibraryOperationRequest.AutoSync(LibraryOperationCause.SAF_TREE_DIRTY),
                requestSequence = 39431L,
                dirtySequenceAtStart = 7931L,
            ),
        )
        val firstRetry = store.retryItems.single { it.retryKey == LibraryRetryKey.safMassDeletionVerify() }

        // Provider is too slow: the first query exhausts the wall budget, nothing gets processed.
        scanner.folderMissingVerificationResult = SafIndependentMissingVerificationResult(
            verifiedMissingStableObjectKeys = emptySet(),
            presentStableObjectKeys = emptySet(),
            indeterminateStableObjectKeys = emptySet(),
            providerQueryCount = 1,
            wallTimeMs = SafMissingVerificationBudget.DEFAULT_MAX_WALL_TIME_MS,
            nextCursor = 0,
            hasMore = true,
            budgetExhausted = true,
        )
        nowMs = firstRetry.nextRetryAtMs
        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                LibraryOperationRequest.AutoSync(LibraryOperationCause.SAF_RETRY_DUE),
                requestSequence = 39432L,
                dirtySequenceAtStart = 7932L,
            ),
        )

        assertEquals(100, harness.backing.songs.size)
        assertEquals(listOf(0), scanner.folderMissingVerificationCursors)
        val backedOff = store.retryItems.single { it.retryKey == LibraryRetryKey.safMassDeletionVerify() }
        assertEquals(2, backedOff.attemptCount)
        assertEquals(0, backedOff.continuationCursor)
        assertTrue(backedOff.nextRetryAtMs > nowMs)
        assertEquals(firstRetry.observedFingerprint, backedOff.observedFingerprint)

        clearFolderPrefs(harness.backing)
        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun s4SafMassDeletionIndependentRetryIndeterminateStaysQuarantinedAndBacksOff() = runTest {
        var nowMs = 2_000L
        val environment = FakeScanEnvironment(
            nowMsProvider = { nowMs },
            elapsedMsProvider = { nowMs },
        )
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val tree = Uri.parse("content://provider/tree/mass-delete-indeterminate")
        val current = List(100) { index ->
            SongFixtures.song("saf-indeterminate-${index + 1}").copy(
                mediaUri = "content://provider/document/saf-indeterminate-${index + 1}",
                fileName = "song-${index + 1}.flac",
                folderPath = "Album",
                filePath = "Album/song-${index + 1}.flac",
                sizeBytes = 30_000L + index,
                dateModifiedMs = 40_000L + index,
            )
        }
        val kept = current.last()
        val removedIds = current.dropLast(1).mapTo(linkedSetOf(), Song::id)
        scanner.folderMetadataSnapshot = SafTreeMetadataSnapshot(
            entries = listOf(
                SafTreeMetadataEntry(
                    stableObjectKey = kept.id, mediaUri = kept.mediaUri, fileName = kept.fileName,
                    folderPath = kept.folderPath, filePath = kept.filePath,
                    mimeType = kept.metadata.playbackMimeType, sizeBytes = kept.sizeBytes,
                    lastModifiedMs = kept.dateModifiedMs,
                    externalLyricsSignature = kept.externalLyricsSignature,
                ),
            ),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    completeness = DiscoveryCompleteness.COMPLETE,
                ),
            ),
        )
        val harness = scanHarness(scanner, store, environment)
        activateFolderSource(harness.backing, tree, "Mass delete indeterminate")
        harness.backing.replaceSongs(current)
        harness.backing.lastScanAtMs = 500L
        harness.backing.lastScanSource = ScanSource.FOLDER

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                LibraryOperationRequest.AutoSync(LibraryOperationCause.SAF_TREE_DIRTY),
                requestSequence = 1943L,
                dirtySequenceAtStart = 593L,
            ),
        )
        val firstRetry = store.retryItems.single { it.retryKey == LibraryRetryKey.safMassDeletionVerify() }
        scanner.folderMissingVerificationResult = SafIndependentMissingVerificationResult(
            verifiedMissingStableObjectKeys = removedIds.drop(1).toSet(),
            presentStableObjectKeys = emptySet(),
            indeterminateStableObjectKeys = setOf(removedIds.first()),
            providerQueryCount = removedIds.size,
            wallTimeMs = 150L,
        )
        nowMs = firstRetry.nextRetryAtMs

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                LibraryOperationRequest.AutoSync(LibraryOperationCause.SAF_RETRY_DUE),
                requestSequence = 1944L,
                dirtySequenceAtStart = 594L,
            ),
        )

        assertEquals(100, harness.backing.songs.size)
        assertEquals(0, store.autoSyncSnapshotCommitCount)
        val secondRetry = store.retryItems.single { it.retryKey == LibraryRetryKey.safMassDeletionVerify() }
        assertEquals(2, secondRetry.attemptCount)
        assertEquals(nowMs + 60_000L, secondRetry.nextRetryAtMs)

        clearFolderPrefs(harness.backing)
        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun s4SafShadowRetryCompensationPlanDoesNotWriteProductionState() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val tree = Uri.parse("content://provider/tree/active")
        val existing = SongFixtures.song("doc-retry-shadow-only").copy(
            mediaUri = "content://provider/document/audio-retry-shadow-only",
            fileName = "song.flac",
            folderPath = "Album",
            filePath = "Album/song.flac",
            sizeBytes = 1_234L,
            dateModifiedMs = 5_678L,
        )
        val observed = SafTreeMetadataEntry(
            stableObjectKey = existing.id,
            mediaUri = existing.mediaUri,
            fileName = existing.fileName,
            folderPath = existing.folderPath,
            filePath = existing.filePath,
            mimeType = existing.metadata.playbackMimeType,
            sizeBytes = existing.sizeBytes + 1L,
            lastModifiedMs = existing.dateModifiedMs + 1L,
            externalLyricsSignature = existing.externalLyricsSignature,
        )
        scanner.folderMetadataSnapshot = SafTreeMetadataSnapshot(
            entries = listOf(observed),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    completeness = DiscoveryCompleteness.COMPLETE,
                ),
            ),
        )
        val runtime = SafShadowProbeRuntime {
            SafShadowProbeExecutionResult(
                issues = listOf(
                    SafShadowProbeIssue(
                        stableObjectKey = existing.id,
                        kind = SafShadowProbeIssueKind.PROBE_FAILED,
                        detail = "expected-shadow-failure",
                    ),
                ),
                attemptedCount = 1,
            )
        }
        val harness = scanHarness(
            scanner = scanner,
            store = store,
            safShadowProbeRuntime = runtime,
        )
        activateFolderSource(harness.backing, tree, "Active")
        harness.backing.replaceSongs(listOf(existing))
        val catalogRevisionBefore = harness.backing.catalogRevision

        harness.orchestrator.executeAutoSyncShadowForDiagnostics(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.SAF_PERIODIC_VERIFY,
                ),
                requestSequence = 1931L,
                dirtySequenceAtStart = 581L,
            ),
        )

        assertTrue(store.autoSyncStateMutations.isEmpty())
        assertEquals(0, store.autoSyncSnapshotCommitCount)
        assertEquals(catalogRevisionBefore, harness.backing.catalogRevision)
        assertEquals(existing.title, harness.backing.songs.single().title)

        clearFolderPrefs(harness.backing)
        harness.backing.release()
    }

    @Test
    fun s4SafShadowDefersCurrentPlaybackObjectDuringChangedOnlyPlanning() = runTest {
        val scanner = ControlledScanner()
        val harness = scanHarness(scanner)
        val tree = Uri.parse("content://provider/tree/active")
        activateFolderSource(harness.backing, tree, "Active")
        val existing = SongFixtures.song("doc-current").copy(
            mediaUri = "content://provider/document/audio-current",
            fileName = "song.flac",
            folderPath = "Album",
            filePath = "Album/song.flac",
            sizeBytes = 1_234L,
            dateModifiedMs = 5_678L,
        )
        harness.backing.replaceSongs(listOf(existing))
        scanner.folderMetadataSnapshot = SafTreeMetadataSnapshot(
            entries = listOf(
                SafTreeMetadataEntry(
                    stableObjectKey = existing.id,
                    mediaUri = existing.mediaUri,
                    fileName = existing.fileName,
                    folderPath = existing.folderPath,
                    filePath = existing.filePath,
                    mimeType = existing.metadata.playbackMimeType,
                    sizeBytes = existing.sizeBytes + 1L,
                    lastModifiedMs = existing.dateModifiedMs + 1L,
                    externalLyricsSignature = existing.externalLyricsSignature,
                ),
            ),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    completeness = DiscoveryCompleteness.COMPLETE,
                ),
            ),
        )
        harness.backing.setPlaybackIoSnapshotProvider {
            LibraryPlaybackIoSnapshot(
                currentStableObjectKey = existing.id,
                currentMediaUri = existing.mediaUri,
                hasActivePlaybackInstance = true,
            )
        }

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.SAF_PERIODIC_VERIFY,
                ),
                requestSequence = 192L,
                dirtySequenceAtStart = 57L,
            ),
        )

        assertTrue(harness.backing.hasPlaybackDeferredAutoWork)
        assertEquals(listOf(tree), scanner.folderMetadataRequests)
        assertTrue(scanner.folderRequests.isEmpty())
        assertEquals(listOf(existing.id), harness.backing.songs.map(Song::id))

        clearFolderPrefs(harness.backing)
        harness.backing.release()
    }

    @Test
    fun s4SafShadowForegroundCatchUpDoesNotProbePureUnknownFingerprint() = runTest {
        val scanner = ControlledScanner()
        val tree = Uri.parse("content://provider/tree/active")
        val existing = SongFixtures.song("doc-unknown").copy(
            mediaUri = "content://provider/document/audio-unknown",
            fileName = "song.flac",
            folderPath = "Album",
            filePath = "Album/song.flac",
            sizeBytes = 1_234L,
            dateModifiedMs = 5_678L,
        )
        scanner.folderMetadataSnapshot = SafTreeMetadataSnapshot(
            entries = listOf(
                SafTreeMetadataEntry(
                    stableObjectKey = existing.id,
                    mediaUri = existing.mediaUri,
                    fileName = existing.fileName,
                    folderPath = existing.folderPath,
                    filePath = existing.filePath,
                    mimeType = existing.metadata.playbackMimeType,
                    sizeBytes = existing.sizeBytes,
                    lastModifiedMs = 0L,
                    externalLyricsSignature = existing.externalLyricsSignature,
                ),
            ),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    completeness = DiscoveryCompleteness.COMPLETE,
                ),
            ),
        )
        var runtimeCalls = 0
        val runtime = SafShadowProbeRuntime {
            runtimeCalls += 1
            SafShadowProbeExecutionResult(attemptedCount = 1)
        }
        val harness = scanHarness(
            scanner = scanner,
            safShadowProbeRuntime = runtime,
        )
        activateFolderSource(harness.backing, tree, "Active")
        harness.backing.replaceSongs(listOf(existing))

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.FOREGROUND_CATCH_UP,
                ),
                requestSequence = 194L,
                dirtySequenceAtStart = 59L,
            ),
        )

        assertEquals(0, runtimeCalls)
        assertEquals(listOf(tree), scanner.folderMetadataRequests)
        assertFalse(harness.backing.hasPlaybackDeferredAutoWork)
        assertEquals(existing.title, harness.backing.songs.single().title)

        clearFolderPrefs(harness.backing)
        harness.backing.release()
    }

    @Test
    fun s4SafShadowPlaybackReleaseCanResumePureUnknownFingerprintVerify() = runTest {
        val scanner = ControlledScanner()
        val tree = Uri.parse("content://provider/tree/active")
        val existing = SongFixtures.song("doc-unknown-release").copy(
            mediaUri = "content://provider/document/audio-unknown-release",
            fileName = "song.flac",
            folderPath = "Album",
            filePath = "Album/song.flac",
            sizeBytes = 1_234L,
            dateModifiedMs = 5_678L,
        )
        scanner.folderMetadataSnapshot = SafTreeMetadataSnapshot(
            entries = listOf(
                SafTreeMetadataEntry(
                    stableObjectKey = existing.id,
                    mediaUri = existing.mediaUri,
                    fileName = existing.fileName,
                    folderPath = existing.folderPath,
                    filePath = existing.filePath,
                    mimeType = existing.metadata.playbackMimeType,
                    sizeBytes = existing.sizeBytes,
                    lastModifiedMs = 0L,
                    externalLyricsSignature = existing.externalLyricsSignature,
                ),
            ),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    completeness = DiscoveryCompleteness.COMPLETE,
                ),
            ),
        )
        var runtimeCalls = 0
        var reasons = emptySet<SafAutoProbeReason>()
        val runtime = SafShadowProbeRuntime { request ->
            runtimeCalls += 1
            reasons = request.probePlan.ready.single().reasons
            SafShadowProbeExecutionResult(attemptedCount = 1)
        }
        val harness = scanHarness(
            scanner = scanner,
            safShadowProbeRuntime = runtime,
        )
        activateFolderSource(harness.backing, tree, "Active")
        harness.backing.replaceSongs(listOf(existing))

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.PLAYBACK_IO_RELEASE,
                ),
                requestSequence = 195L,
                dirtySequenceAtStart = 60L,
            ),
        )

        assertEquals(1, runtimeCalls)
        assertEquals(setOf(SafAutoProbeReason.UNKNOWN_FINGERPRINT_VERIFY), reasons)
        assertEquals(listOf(tree), scanner.folderMetadataRequests)

        clearFolderPrefs(harness.backing)
        harness.backing.release()
    }

    @Test
    fun s4SafShadowBudgetContinuationDoesNotReplayPureUnknownBeforeDebtMutation() = runTest {
        val scanner = ControlledScanner()
        val tree = Uri.parse("content://provider/tree/active")
        val existing = SongFixtures.song("doc-unknown-budget").copy(
            mediaUri = "content://provider/document/audio-unknown-budget",
            fileName = "song.flac",
            folderPath = "Album",
            filePath = "Album/song.flac",
            sizeBytes = 1_234L,
            dateModifiedMs = 5_678L,
        )
        scanner.folderMetadataSnapshot = SafTreeMetadataSnapshot(
            entries = listOf(
                SafTreeMetadataEntry(
                    stableObjectKey = existing.id,
                    mediaUri = existing.mediaUri,
                    fileName = existing.fileName,
                    folderPath = existing.folderPath,
                    filePath = existing.filePath,
                    mimeType = existing.metadata.playbackMimeType,
                    sizeBytes = existing.sizeBytes,
                    lastModifiedMs = 0L,
                    externalLyricsSignature = existing.externalLyricsSignature,
                ),
            ),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    completeness = DiscoveryCompleteness.COMPLETE,
                ),
            ),
        )
        var runtimeCalls = 0
        val runtime = SafShadowProbeRuntime {
            runtimeCalls += 1
            SafShadowProbeExecutionResult(attemptedCount = 1)
        }
        val harness = scanHarness(
            scanner = scanner,
            safShadowProbeRuntime = runtime,
        )
        activateFolderSource(harness.backing, tree, "Active")
        harness.backing.replaceSongs(listOf(existing))

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.SAF_BUDGET_CONTINUATION,
                ),
                requestSequence = 196L,
                dirtySequenceAtStart = 61L,
            ),
        )

        assertEquals(0, runtimeCalls)
        assertEquals(listOf(tree), scanner.folderMetadataRequests)

        clearFolderPrefs(harness.backing)
        harness.backing.release()
    }

    @Test
    fun s4SafShadowDueRetryLedgerForcesProbeOnOtherwiseUnchangedObject() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val tree = Uri.parse("content://provider/tree/active")
        val source = SourceIdentityKey.folder(tree.toString())
        val existing = SongFixtures.song("doc-retry").copy(
            mediaUri = "content://provider/document/audio-retry",
            fileName = "song.flac",
            folderPath = "Album",
            filePath = "Album/song.flac",
            sizeBytes = 1_234L,
            dateModifiedMs = 5_678L,
        )
        scanner.folderMetadataSnapshot = SafTreeMetadataSnapshot(
            entries = listOf(
                SafTreeMetadataEntry(
                    stableObjectKey = existing.id,
                    mediaUri = existing.mediaUri,
                    fileName = existing.fileName,
                    folderPath = existing.folderPath,
                    filePath = existing.filePath,
                    mimeType = existing.metadata.playbackMimeType,
                    sizeBytes = existing.sizeBytes,
                    lastModifiedMs = existing.dateModifiedMs,
                    externalLyricsSignature = existing.externalLyricsSignature,
                ),
            ),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    completeness = DiscoveryCompleteness.COMPLETE,
                ),
            ),
        )
        store.retryItems += LibraryRetryItem(
            sourceIdentity = source,
            retryKey = "saf-probe:" + existing.id,
            activationEpoch = 1L,
            stableObjectKey = existing.id,
            observedFingerprint = "saf-retry:v1",
            retryKind = LibraryRetryKind.OBJECT_PROBE,
            failureKind = "PROBE_FAILED",
            attemptCount = 1,
            nextRetryAtMs = 1_234L,
        )
        var runtimeCalls = 0
        var reasons = emptySet<SafAutoProbeReason>()
        val runtime = SafShadowProbeRuntime { request ->
            runtimeCalls += 1
            reasons = request.probePlan.ready.single().reasons
            SafShadowProbeExecutionResult(attemptedCount = 1)
        }
        val harness = scanHarness(
            scanner = scanner,
            store = store,
            safShadowProbeRuntime = runtime,
        )
        activateFolderSource(harness.backing, tree, "Active")
        harness.backing.replaceSongs(listOf(existing))

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.FOREGROUND_CATCH_UP,
                ),
                requestSequence = 196L,
                dirtySequenceAtStart = 61L,
            ),
        )

        assertEquals(1, runtimeCalls)
        assertEquals(setOf(SafAutoProbeReason.RETRY_LEDGER), reasons)
        assertEquals(listOf(tree), scanner.folderMetadataRequests)

        clearFolderPrefs(harness.backing)
        harness.backing.release()
    }

    @Test
    fun s4SafShadowProviderFailureIsCaughtAndImmediateRetryIsBackedOff() = runTest {
        val scanner = ControlledScanner()
        val tree = Uri.parse("content://provider/tree/active")
        var walkAttempts = 0
        scanner.onObserveFolderMetadata = {
            walkAttempts += 1
            error("provider-down")
        }
        val harness = scanHarness(scanner)
        activateFolderSource(harness.backing, tree, "Active")

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.SAF_PERIODIC_VERIFY,
                ),
                requestSequence = 197L,
                dirtySequenceAtStart = 62L,
            ),
        )
        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.FOREGROUND_CATCH_UP,
                ),
                requestSequence = 198L,
                dirtySequenceAtStart = 63L,
            ),
        )

        assertEquals(1, walkAttempts)
        assertEquals(listOf(tree), scanner.folderMetadataRequests)
        assertTrue(scanner.folderRequests.isEmpty())

        clearFolderPrefs(harness.backing)
        harness.backing.release()
    }

    @Test
    fun safPersistedGrantReacquireRetriesOrdinaryReadabilityBeforeDiscovery() = runTest {
        val scanner = ControlledScanner().apply {
            folderMetadataSnapshot = SafTreeMetadataSnapshot(
                entries = emptyList(),
                discoveryReport = DiscoveryReport.of(
                    DiscoveryPartitionStatus(
                        partitionKey = DiscoveryPartitions.SAF_TREE,
                        completeness = DiscoveryCompleteness.COMPLETE,
                    ),
                ),
            )
        }
        val environment = FakeScanEnvironment(
            persistedTreeReadAccess = true,
            treeProviderAcquirable = true,
        ).apply {
            scriptedTreeReadability += listOf(false, true)
        }
        val tree = Uri.parse("content://provider/tree/reacquire")
        val harness = scanHarness(scanner = scanner, environment = environment)
        activateFolderSource(harness.backing, tree, "Reacquire")
        harness.backing.lastScanError = SAF_PROVIDER_RESELECT_REQUIRED_ERROR

        harness.orchestrator.executeAutoSyncShadowForDiagnostics(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.SAF_PERIODIC_VERIFY,
                ),
                requestSequence = 1971L,
                dirtySequenceAtStart = 621L,
            ),
        )

        assertEquals(2, environment.canReadTreeCalls)
        assertEquals(1, environment.providerAcquireCalls)
        assertEquals(listOf(tree), scanner.folderMetadataRequests)
        assertEquals(LibraryAccessState.AVAILABLE, harness.backing.accessState)
        assertNull(harness.backing.lastScanError)

        clearFolderPrefs(harness.backing)
        harness.backing.release()
        runCurrent()
    }

    @Test
    fun scheduledSafPersistedGrantWithUnavailableProviderPausesAfterThreeFailures() = runTest {
        var nowMs = 1_234L
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val environment = FakeScanEnvironment(
            nowMsProvider = { nowMs },
            treeReadable = false,
            persistedTreeReadAccess = true,
            treeProviderAcquirable = false,
        )
        val tree = Uri.parse("content://provider/tree/provider-unavailable")
        val harness = scanHarness(scanner = scanner, store = store, environment = environment)
        activateFolderSource(harness.backing, tree, "Unavailable")

        suspend fun runAttempt(sequence: Long) {
            harness.orchestrator.executeScheduled(
                ScheduledLibraryOperation(
                    request = LibraryOperationRequest.AutoSync(
                        LibraryOperationCause.SAF_PERIODIC_VERIFY,
                    ),
                    requestSequence = sequence,
                    dirtySequenceAtStart = sequence,
                ),
            )
        }

        runAttempt(1972L)
        assertEquals(LibraryAccessState.AVAILABLE, harness.backing.accessState)
        assertNull(harness.backing.lastScanError)

        nowMs += SafProviderDiscoveryBackoff.DEFAULT_BASE_DELAY_MS
        runAttempt(1973L)
        assertEquals(LibraryAccessState.AVAILABLE, harness.backing.accessState)

        nowMs += SafProviderDiscoveryBackoff.DEFAULT_BASE_DELAY_MS * 2L
        runAttempt(1974L)
        runCurrent()

        assertEquals(3, environment.canReadTreeCalls)
        assertEquals(3, environment.providerAcquireCalls)
        assertTrue(scanner.folderMetadataRequests.isEmpty())
        assertTrue(store.autoSyncStateMutations.isEmpty())
        assertEquals(LibraryAccessState.TEMP_UNAVAILABLE, harness.backing.accessState)
        assertEquals(SAF_PROVIDER_RESELECT_REQUIRED_ERROR, harness.backing.lastScanError)

        // Foreground/user access refresh re-arms the source and clears the in-memory breaker.
        environment.treeReadable = true
        environment.treeProviderAcquirable = true
        scanner.folderMetadataSnapshot = SafTreeMetadataSnapshot(
            entries = emptyList(),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    completeness = DiscoveryCompleteness.COMPLETE,
                ),
            ),
        )
        harness.backing.folder.updatePermission(false)
        runCurrent()
        assertEquals(LibraryAccessState.AVAILABLE, harness.backing.accessState)

        runAttempt(1975L)
        assertEquals(listOf(tree), scanner.folderMetadataRequests)
        assertNull(harness.backing.lastScanError)

        clearFolderPrefs(harness.backing)
        harness.backing.release()
        runCurrent()
    }

    @Test
    fun scheduledSafUnreadableTreeWithoutPersistedGrantKeepsTransientBackoff() = runTest {
        var nowMs = 1_234L
        val scanner = ControlledScanner()
        val environment = FakeScanEnvironment(
            nowMsProvider = { nowMs },
            treeReadable = false,
            persistedTreeReadAccess = false,
        )
        val tree = Uri.parse("content://provider/tree/no-persisted-grant")
        val harness = scanHarness(scanner = scanner, environment = environment)
        activateFolderSource(harness.backing, tree, "No grant")

        repeat(3) { index ->
            harness.orchestrator.executeScheduled(
                ScheduledLibraryOperation(
                    request = LibraryOperationRequest.AutoSync(
                        LibraryOperationCause.SAF_PERIODIC_VERIFY,
                    ),
                    requestSequence = 1980L + index,
                    dirtySequenceAtStart = 640L + index,
                ),
            )
            nowMs += when (index) {
                0 -> SafProviderDiscoveryBackoff.DEFAULT_BASE_DELAY_MS
                1 -> SafProviderDiscoveryBackoff.DEFAULT_BASE_DELAY_MS * 2L
                else -> 0L
            }
        }

        assertEquals(3, environment.canReadTreeCalls)
        assertEquals(0, environment.providerAcquireCalls)
        assertEquals(LibraryAccessState.AVAILABLE, harness.backing.accessState)
        assertNull(harness.backing.lastScanError)
        assertTrue(scanner.folderMetadataRequests.isEmpty())

        clearFolderPrefs(harness.backing)
        harness.backing.release()
        runCurrent()
    }

    @Test
    fun s5ScheduledSafProviderFailureRetriesAtBackoffDeadlineWithoutExternalDirty() = runTest {
        val scanner = ControlledScanner()
        val tree = Uri.parse("content://provider/tree/active")
        var walkAttempts = 0
        scanner.onObserveFolderMetadata = {
            walkAttempts += 1
            error("provider-down")
        }
        val harness = scanHarness(
            scanner = scanner,
            environment = FakeScanEnvironment(
                nowMsProvider = { 1_234L + testScheduler.currentTime },
            ),
        )
        activateFolderSource(harness.backing, tree, "Active")
        harness.backing.isAutoSyncForeground = true

        harness.backing.syncScheduler.markDirty(LibraryOperationCause.SAF_PERIODIC_VERIFY)
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(1, walkAttempts)

        advanceTimeBy(SafProviderDiscoveryBackoff.DEFAULT_BASE_DELAY_MS - 1L)
        runCurrent()
        assertEquals(1, walkAttempts)

        advanceTimeBy(1L)
        runCurrent()

        assertEquals(2, walkAttempts)
        assertEquals(
            AutoSyncWakeReason.RETRY_DUE,
            harness.backing.syncScheduler.lastAutoShadowDiagnostic?.wakeReason,
        )
        assertEquals(
            LibraryOperationCause.SAF_RETRY_DUE,
            harness.backing.syncScheduler.lastAutoShadowDiagnostic
                ?.causeCounts
                ?.keys
                ?.single(),
        )

        clearFolderPrefs(harness.backing)
        harness.backing.release()
        runCurrent()
    }

    @Test
    fun s4SafShadowBusyPartialStartsFailureBreakerAndSuppressesImmediateRetry() = runTest {
        val scanner = ControlledScanner()
        val tree = Uri.parse("content://provider/tree/active")
        scanner.folderMetadataSnapshot = SafTreeMetadataSnapshot(
            entries = emptyList(),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    completeness = DiscoveryCompleteness.PARTIAL,
                    detail = "AUTO SAF provider query lane busy; prior provider query still running",
                ),
            ),
        )
        val harness = scanHarness(scanner)
        activateFolderSource(harness.backing, tree, "Active")

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.SAF_PERIODIC_VERIFY,
                ),
                requestSequence = 1971L,
                dirtySequenceAtStart = 621L,
            ),
        )
        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.FOREGROUND_CATCH_UP,
                ),
                requestSequence = 1972L,
                dirtySequenceAtStart = 622L,
            ),
        )

        assertEquals(listOf(tree), scanner.folderMetadataRequests)
        assertTrue(scanner.folderRequests.isEmpty())

        clearFolderPrefs(harness.backing)
        harness.backing.release()
    }

    @Test
    fun s4SafShadowSlowCompleteCadenceSuppressesImmediateAutoButPlaybackReleaseCanBypass() = runTest {
        val scanner = ControlledScanner()
        val tree = Uri.parse("content://provider/tree/active")
        scanner.folderMetadataSnapshot = SafTreeMetadataSnapshot(
            entries = emptyList(),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    completeness = DiscoveryCompleteness.COMPLETE,
                ),
            ),
            observationStats = SafTreeMetadataObservationStats(
                providerQueryCount = 2,
                directQueryCount = 2,
                wallTimeMs =
                    SafProviderDiscoveryBackoff.SLOW_SUCCESS_THRESHOLD_MS + 1L,
            ),
        )
        val harness = scanHarness(scanner)
        activateFolderSource(harness.backing, tree, "Active")

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.SAF_PERIODIC_VERIFY,
                ),
                requestSequence = 1981L,
                dirtySequenceAtStart = 631L,
            ),
        )
        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.FOREGROUND_CATCH_UP,
                ),
                requestSequence = 1982L,
                dirtySequenceAtStart = 632L,
            ),
        )
        assertEquals(listOf(tree), scanner.folderMetadataRequests)

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.PLAYBACK_IO_RELEASE,
                ),
                requestSequence = 1983L,
                dirtySequenceAtStart = 633L,
            ),
        )
        assertEquals(listOf(tree, tree), scanner.folderMetadataRequests)

        clearFolderPrefs(harness.backing)
        harness.backing.release()
    }

    @Test
    fun safProviderCadenceIgnoresWallClockChanges() = runTest {
        var wall = 1_000_000L
        var elapsed = 1_000L
        val scanner = ControlledScanner()
        val tree = Uri.parse("content://provider/tree/clock-test")
        scanner.folderMetadataSnapshot = SafTreeMetadataSnapshot(
            entries = emptyList(),
            discoveryReport = DiscoveryReport.of(DiscoveryPartitionStatus(
                DiscoveryPartitions.SAF_TREE, DiscoveryCompleteness.COMPLETE,
            )),
            observationStats = SafTreeMetadataObservationStats(
                wallTimeMs = SafProviderDiscoveryBackoff.SLOW_SUCCESS_THRESHOLD_MS + 1,
            ),
        )
        val harness = scanHarness(scanner, environment = FakeScanEnvironment(
            nowMsProvider = { wall }, elapsedMsProvider = { elapsed },
        ))
        activateFolderSource(harness.backing, tree, "Clock")
        suspend fun verify(seq: Long) = harness.orchestrator.executeScheduled(ScheduledLibraryOperation(
            LibraryOperationRequest.AutoSync(LibraryOperationCause.SAF_PERIODIC_VERIFY), seq, seq,
        ))
        verify(1)
        wall += 86_400_000L
        verify(2)
        assertEquals(1, scanner.folderMetadataRequests.size)
        wall = 1L
        elapsed += SafProviderDiscoveryBackoff.SLOW_SUCCESS_CADENCE_MS
        verify(3)
        assertEquals(2, scanner.folderMetadataRequests.size)
        clearFolderPrefs(harness.backing)
        harness.backing.release()
    }

    @Test
    fun s4SafShadowPostWalkFailureDropsProvisionalAndBacksOffNextPass() = runTest {
        val scanner = ControlledScanner()
        val tree = Uri.parse("content://provider/tree/active")
        val existing = SongFixtures.song("doc-post-provider").copy(
            mediaUri = "content://provider/document/audio-post-provider",
            fileName = "song.flac",
            folderPath = "Album",
            filePath = "Album/song.flac",
            sizeBytes = 1_234L,
            dateModifiedMs = 5_678L,
        )
        val observed = SafTreeMetadataEntry(
            stableObjectKey = existing.id,
            mediaUri = existing.mediaUri,
            fileName = existing.fileName,
            folderPath = existing.folderPath,
            filePath = existing.filePath,
            mimeType = existing.metadata.playbackMimeType,
            sizeBytes = existing.sizeBytes + 1L,
            lastModifiedMs = existing.dateModifiedMs + 1L,
            externalLyricsSignature = existing.externalLyricsSignature,
        )
        scanner.folderMetadataSnapshot = SafTreeMetadataSnapshot(
            entries = listOf(observed),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    completeness = DiscoveryCompleteness.COMPLETE,
                ),
            ),
        )
        var walkAttempts = 0
        scanner.onObserveFolderMetadata = {
            walkAttempts += 1
            if (walkAttempts == 2) error("post-provider-down")
        }
        val runtime = SafShadowProbeRuntime {
            SafShadowProbeExecutionResult(
                provisionalSongsByStableObjectKey = mapOf(
                    existing.id to existing.copy(
                        title = "must-not-publish",
                        sizeBytes = observed.sizeBytes,
                        dateModifiedMs = observed.lastModifiedMs,
                    ),
                ),
                attemptedCount = 1,
            )
        }
        val harness = scanHarness(
            scanner = scanner,
            safShadowProbeRuntime = runtime,
        )
        activateFolderSource(harness.backing, tree, "Active")
        harness.backing.replaceSongs(listOf(existing))
        val catalogRevisionBefore = harness.backing.catalogRevision

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.SAF_PERIODIC_VERIFY,
                ),
                requestSequence = 199L,
                dirtySequenceAtStart = 64L,
            ),
        )
        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.FOREGROUND_CATCH_UP,
                ),
                requestSequence = 200L,
                dirtySequenceAtStart = 65L,
            ),
        )

        assertEquals(2, walkAttempts)
        assertEquals(listOf(tree, tree), scanner.folderMetadataRequests)
        assertEquals(catalogRevisionBefore, harness.backing.catalogRevision)
        assertEquals(existing.title, harness.backing.songs.single().title)

        clearFolderPrefs(harness.backing)
        harness.backing.release()
    }

    @Test
    fun s4SafShadowDropsObservationWhenSourceActivationChangesDuringMetadataWalk() = runTest {
        val scanner = ControlledScanner()
        val harness = scanHarness(scanner)
        val tree = Uri.parse("content://provider/tree/active")
        activateFolderSource(harness.backing, tree, "Active")
        val existing = SongFixtures.song("doc-active").copy(
            mediaUri = "content://provider/document/audio-1",
            fileName = "song.flac",
            folderPath = "Album",
            filePath = "Album/song.flac",
            sizeBytes = 1_234L,
            dateModifiedMs = 5_678L,
        )
        harness.backing.replaceSongs(listOf(existing))
        scanner.folderMetadataSnapshot = SafTreeMetadataSnapshot(
            entries = listOf(
                SafTreeMetadataEntry(
                    stableObjectKey = existing.id,
                    mediaUri = existing.mediaUri,
                    fileName = existing.fileName,
                    folderPath = existing.folderPath,
                    filePath = existing.filePath,
                    mimeType = existing.metadata.playbackMimeType,
                    sizeBytes = existing.sizeBytes,
                    lastModifiedMs = existing.dateModifiedMs,
                    externalLyricsSignature = existing.externalLyricsSignature,
                ),
            ),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    completeness = DiscoveryCompleteness.COMPLETE,
                ),
            ),
        )
        scanner.onObserveFolderMetadata = {
            harness.backing.restorePersistedState(
                PersistedLibraryState(
                    intent = LibraryIntentState.ACTIVE,
                    access = LibraryAccessState.AVAILABLE,
                    sourceState = LibrarySourceState(
                        active = SourceActivation(
                            SourceIdentityKey.folder(tree.toString()),
                            activationEpoch = 2L,
                        ),
                    ),
                    configFingerprint = harness.backing.configFingerprint,
                ),
            )
        }
        val catalogRevisionBefore = harness.backing.catalogRevision

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.SAF_PERIODIC_VERIFY,
                ),
                requestSequence = 191L,
                dirtySequenceAtStart = 56L,
            ),
        )

        assertEquals(listOf(tree), scanner.folderMetadataRequests)
        assertEquals(2L, harness.backing.sourceState.active?.activationEpoch)
        assertEquals(catalogRevisionBefore, harness.backing.catalogRevision)
        assertTrue(scanner.folderRequests.isEmpty())
        assertEquals(listOf(existing.id), harness.backing.songs.map(Song::id))

        clearFolderPrefs(harness.backing)
        harness.backing.release()
    }

    @Test
    fun s3DeviceShadowDropsCandidateWhenSourceActivationChangesDuringObservation() = runTest {
        val shadow = ControlledDeviceAutoSyncShadow(
            observation = DeviceAutoSyncShadowObservation.BaselineCandidate(
                snapshot = deviceGenerationSnapshot(10L),
                reason = com.mica.music.data.scanner.DeviceGenerationReconcileReason.BASELINE_MISSING,
            ),
        )
        val harness = scanHarness(
            scanner = ControlledScanner(),
            deviceAutoSyncShadow = shadow,
        )
        activateDeviceSource(harness.backing)
        shadow.onObserve = {
            harness.backing.restorePersistedState(
                PersistedLibraryState(
                    intent = LibraryIntentState.ACTIVE,
                    access = LibraryAccessState.AVAILABLE,
                    sourceState = LibrarySourceState(
                        active = SourceActivation(SourceIdentityKey.device(), activationEpoch = 2L),
                    ),
                    configFingerprint = harness.backing.configFingerprint,
                ),
            )
        }

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
                ),
                requestSequence = 91L,
                dirtySequenceAtStart = 13L,
            ),
        )

        assertEquals(1, shadow.observeCalls)
        assertTrue(shadow.accepted.isEmpty())
        assertEquals(2L, harness.backing.sourceState.active?.activationEpoch)
        harness.backing.release()
    }

    @Test
    fun s3DeviceShadowPendingProviderStateHoldsCursorBeforeProbe() = runTest {
        val row = DeviceDeltaRow(
            channel = DeviceDeltaChannel.AUDIO,
            volumeName = "external_primary",
            mediaStoreId = 41L,
            mediaUri = "content://media/external_primary/audio/41",
            displayName = "copy.wav",
            mimeType = "",
            relativePath = "Music/",
            sizeBytes = 1_040_044L,
            dateModifiedMs = 1_000L,
            generationAdded = 12L,
            generationModified = 12L,
            eligibility = LibraryEligibility.PENDING,
            eligibilityAuthority =
                com.mica.music.data.scanner.DeviceEligibilityAuthority.PROVIDER_PENDING_TRANSIENT,
        )
        val observation = DeviceAutoSyncShadowObservation.DeltaCandidate(
            batch = emptyDeviceDeltaBatch(10L, 12L).copy(rows = listOf(row)),
            lyricsSidecarInventory = completeLyricsInventory(),
            presenceInventory = completePresenceInventory(),
            advanceTo = deviceGenerationSnapshot(12L),
            followUpRequired = false,
        )
        val shadow = ControlledDeviceAutoSyncShadow(observation = observation)
        val runtime = DeviceShadowProbeRuntime {
            error("transient provider classification must hold before heavy probe")
        }
        val harness = scanHarness(
            scanner = ControlledScanner(),
            deviceAutoSyncShadow = shadow,
            deviceShadowProbeRuntime = runtime,
        )
        activateDeviceSource(harness.backing)

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
                ),
                requestSequence = 91L,
                dirtySequenceAtStart = 13L,
            ),
        )

        assertEquals(1, shadow.observeCalls)
        assertTrue(shadow.accepted.isEmpty())
        assertFalse(harness.backing.hasPlaybackDeferredAutoWork)
        harness.backing.release()
        runCurrent()
    }

    @Test
    fun scheduledDeviceAutoCurrentPlaybackHoldsAuthorityUntilLeaseRelease() = runTest {
        val existing = SongFixtures.song("ms_42").copy(
            mediaUri = "content://media/external_primary/audio/42",
            fileName = "track.flac",
            folderPath = "Music",
            filePath = "Music/track.flac",
            sizeBytes = 123L,
            dateModifiedMs = 1_000L,
        )
        val row = DeviceDeltaRow(
            channel = DeviceDeltaChannel.AUDIO,
            volumeName = "external_primary",
            mediaStoreId = 42L,
            mediaUri = existing.mediaUri,
            displayName = existing.fileName,
            mimeType = "audio/flac",
            relativePath = "Music/",
            sizeBytes = existing.sizeBytes,
            dateModifiedMs = 2_000L,
            generationAdded = 12L,
            generationModified = 12L,
            eligibility = LibraryEligibility.ELIGIBLE,
        )
        val observation = DeviceAutoSyncShadowObservation.DeltaCandidate(
            batch = emptyDeviceDeltaBatch(10L, 12L).copy(rows = listOf(row)),
            lyricsSidecarInventory = completeLyricsInventory(),
            presenceInventory = completePresenceInventoryFor(existing),
            advanceTo = deviceGenerationSnapshot(12L),
            followUpRequired = false,
        )
        val shadow = ControlledDeviceAutoSyncShadow(observation = observation)
        val resolved = existing.copy(dateModifiedMs = row.dateModifiedMs)
        val runtime = DeviceShadowProbeRuntime { request ->
            if (request.probePlan.ready.isEmpty()) {
                DeviceShadowProbeExecutionResult(
                    resolvedObjectsByStableObjectKey = emptyMap(),
                    issues = emptyList(),
                )
            } else {
                assertEquals(existing.id, request.probePlan.ready.single().stableObjectKey)
                DeviceShadowProbeExecutionResult(
                    resolvedObjectsByStableObjectKey = emptyMap(),
                    resolvedSongsByStableObjectKey = mapOf(existing.id to resolved),
                    issues = emptyList(),
                )
            }
        }
        val store = FakeLibraryStore()
        val harness = scanHarness(
            scanner = ControlledScanner(),
            store = store,
            deviceAutoSyncShadow = shadow,
            deviceShadowProbeRuntime = runtime,
        )
        activateDeviceSource(harness.backing)
        harness.backing.replaceSongs(listOf(existing))
        harness.backing.lastScanAtMs = 555L
        harness.backing.lastScanSource = ScanSource.DEVICE
        harness.backing.setPlaybackIoSnapshotProvider {
            LibraryPlaybackIoSnapshot(
                currentStableObjectKey = existing.id,
                currentMediaUri = existing.mediaUri,
                hasActivePlaybackInstance = true,
            )
        }

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
                ),
                requestSequence = 92L,
                dirtySequenceAtStart = 14L,
            ),
        )

        assertEquals(1, shadow.observeCalls)
        assertTrue(shadow.accepted.isEmpty())
        assertTrue(harness.backing.hasPlaybackDeferredAutoWork)

        harness.backing.setPlaybackIoSnapshotProvider { LibraryPlaybackIoSnapshot.Idle }
        harness.backing.onPlaybackIoLeaseChanged()
        advanceTimeBy(1_500L)
        runCurrent()

        assertEquals(2, shadow.observeCalls)
        assertEquals(1, shadow.accepted.size)
        assertEquals(1, store.autoSyncSnapshotCommitCount)
        assertFalse(harness.backing.hasPlaybackDeferredAutoWork)
        assertEquals(
            LibraryOperationCause.PLAYBACK_IO_RELEASE,
            harness.backing.syncScheduler.lastAutoShadowDiagnostic?.causeCounts?.keys?.single(),
        )
        harness.backing.release()
        runCurrent()
    }

    @Test
    fun s3DeviceShadowProbeIssueHoldsCursorWithoutMarkingPlaybackDeferred() = runTest {
        val existing = SongFixtures.song("ms_77").copy(
            mediaUri = "content://media/external_primary/audio/77",
            fileName = "track.flac",
            folderPath = "Music",
            filePath = "Music/track.flac",
            sizeBytes = 100L,
            dateModifiedMs = 2_000L,
        )
        val row = DeviceDeltaRow(
            channel = DeviceDeltaChannel.AUDIO,
            volumeName = "external_primary",
            mediaStoreId = 77L,
            mediaUri = existing.mediaUri,
            displayName = existing.fileName,
            mimeType = "audio/flac",
            relativePath = "Music/",
            sizeBytes = existing.sizeBytes,
            dateModifiedMs = 3_000L,
            generationAdded = 12L,
            generationModified = 12L,
            eligibility = LibraryEligibility.ELIGIBLE,
        )
        val observation = DeviceAutoSyncShadowObservation.DeltaCandidate(
            batch = emptyDeviceDeltaBatch(10L, 12L).copy(rows = listOf(row)),
            lyricsSidecarInventory = completeLyricsInventory(),
            presenceInventory = completePresenceInventory(),
            advanceTo = deviceGenerationSnapshot(12L),
            followUpRequired = false,
        )
        val shadow = ControlledDeviceAutoSyncShadow(observation = observation)
        var runtimeCalls = 0
        val runtime = DeviceShadowProbeRuntime { request ->
            runtimeCalls += 1
            assertEquals(existing.id, request.probePlan.ready.single().stableObjectKey)
            DeviceShadowProbeExecutionResult(
                resolvedObjectsByStableObjectKey = emptyMap(),
                issues = listOf(
                    DeviceShadowProbeIssue(
                        stableObjectKey = existing.id,
                        kind = DeviceShadowProbeIssueKind.POST_OBSERVATION_CHANGED,
                        detail = "test-race",
                    ),
                ),
            )
        }
        val harness = scanHarness(
            scanner = ControlledScanner(),
            deviceAutoSyncShadow = shadow,
            deviceShadowProbeRuntime = runtime,
        )
        activateDeviceSource(harness.backing)
        harness.backing.replaceSongs(listOf(existing))

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
                ),
                requestSequence = 94L,
                dirtySequenceAtStart = 16L,
            ),
        )

        assertEquals(1, runtimeCalls)
        assertEquals(1, shadow.observeCalls)
        assertTrue(shadow.accepted.isEmpty())
        assertFalse(harness.backing.hasPlaybackDeferredAutoWork)
        harness.backing.release()
    }

    @Test
    fun scheduledDevicePlaybackDeferredResumesOnLeaseReleaseAndPublishesAuthority() = runTest {
        val existing = SongFixtures.song("ms_88").copy(
            mediaUri = "content://media/external_primary/audio/88",
            fileName = "track88.flac",
            folderPath = "Music",
            filePath = "Music/track88.flac",
            sizeBytes = 188L,
            dateModifiedMs = 2_000L,
        )
        val row = DeviceDeltaRow(
            channel = DeviceDeltaChannel.AUDIO,
            volumeName = "external_primary",
            mediaStoreId = 88L,
            mediaUri = existing.mediaUri,
            displayName = existing.fileName,
            mimeType = "audio/flac",
            relativePath = "Music/",
            sizeBytes = existing.sizeBytes,
            dateModifiedMs = 3_000L,
            generationAdded = 12L,
            generationModified = 12L,
            eligibility = LibraryEligibility.ELIGIBLE,
        )
        val presence = PresenceInventory.fromEntries(
            entries = listOf(
                com.mica.music.data.scanner.PresenceEntry(
                    stableObjectKey = existing.id,
                    partitionKey = com.mica.music.data.scanner.DiscoveryPartitions.MEDIASTORE_AUDIO,
                    eligibility = LibraryEligibility.ELIGIBLE,
                    evidenceRevision = "present-88",
                ),
            ),
            discoveryReport = com.mica.music.data.scanner.DiscoveryReport.of(
                com.mica.music.data.scanner.DiscoveryPartitionStatus(
                    partitionKey = com.mica.music.data.scanner.DiscoveryPartitions.MEDIASTORE_AUDIO,
                    completeness = DiscoveryCompleteness.COMPLETE,
                ),
                com.mica.music.data.scanner.DiscoveryPartitionStatus(
                    partitionKey =
                        com.mica.music.data.scanner.DiscoveryPartitions.MEDIASTORE_FILES_FALLBACK,
                    completeness = DiscoveryCompleteness.COMPLETE,
                ),
            ),
        )
        val observation = DeviceAutoSyncShadowObservation.DeltaCandidate(
            batch = emptyDeviceDeltaBatch(10L, 12L).copy(rows = listOf(row)),
            lyricsSidecarInventory = completeLyricsInventory(),
            presenceInventory = presence,
            advanceTo = deviceGenerationSnapshot(12L),
            followUpRequired = false,
        )
        val shadow = ControlledDeviceAutoSyncShadow(observation = observation)
        var playback = LibraryPlaybackIoSnapshot(
            currentStableObjectKey = existing.id,
            currentMediaUri = existing.mediaUri,
            hasActivePlaybackInstance = true,
        )
        val resolvedSong = existing.copy(
            title = "fresh after playback release",
            dateModifiedMs = 3_000L,
        )
        val canonicalContext = com.mica.music.data.scanner.DeviceShadowCanonicalContext(
            sourceIdentityStorageKey = SourceIdentityKey.device().storageKey(),
            activationEpoch = 1L,
            configFingerprint = "test",
            providerIdentityDomain = "mediastore:external_primary:v1",
        )
        val resolvedCanonical =
            com.mica.music.data.scanner.DeviceShadowCanonicalCatalog.snapshot(
                listOf(resolvedSong),
                canonicalContext,
            ).songsByStableObjectKey.getValue(existing.id)
        var runtimeCalls = 0
        val runtime = DeviceShadowProbeRuntime { request ->
            runtimeCalls += 1
            if (request.probePlan.ready.isEmpty()) {
                DeviceShadowProbeExecutionResult(
                    resolvedObjectsByStableObjectKey = emptyMap(),
                    issues = emptyList(),
                )
            } else {
                assertEquals(existing.id, request.probePlan.ready.single().stableObjectKey)
                DeviceShadowProbeExecutionResult(
                    resolvedObjectsByStableObjectKey = mapOf(
                        existing.id to DeviceShadowResolvedCanonicalObject(
                            song = resolvedCanonical,
                            resolvedAspects =
                                com.mica.music.data.scanner.DeviceShadowCanonicalAspect.entries.toSet(),
                        ),
                    ),
                    resolvedSongsByStableObjectKey = mapOf(existing.id to resolvedSong),
                    issues = emptyList(),
                )
            }
        }
        val store = FakeLibraryStore()
        val harness = scanHarness(
            scanner = ControlledScanner(),
            store = store,
            deviceAutoSyncShadow = shadow,
            deviceShadowProbeRuntime = runtime,
            syncSchedulerTiming = LibrarySyncSchedulerTiming(
                debounceMs = 0L,
                cooldownMs = 0L,
                maxDebounceMs = 0L,
            ),
        )
        activateDeviceSource(harness.backing)
        harness.backing.replaceSongs(listOf(existing))
        harness.backing.lastScanAtMs = 555L
        harness.backing.lastScanSource = ScanSource.DEVICE
        harness.backing.setPlaybackIoSnapshotProvider { playback }

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
                ),
                requestSequence = 95L,
                dirtySequenceAtStart = 17L,
            ),
        )

        assertEquals(1, runtimeCalls)
        assertTrue(shadow.accepted.isEmpty())
        assertTrue(harness.backing.hasPlaybackDeferredAutoWork)

        playback = LibraryPlaybackIoSnapshot.Idle
        harness.backing.onPlaybackIoLeaseChanged()
        runCurrent()
        advanceUntilIdle()

        assertEquals(2, runtimeCalls)
        assertEquals(2, shadow.observeCalls)
        assertEquals(1, shadow.accepted.size)
        assertEquals(1, store.autoSyncSnapshotCommitCount)
        assertEquals("fresh after playback release", harness.backing.songs.single().title)
        assertFalse(harness.backing.hasPlaybackDeferredAutoWork)
        assertEquals(
            mapOf(LibraryOperationCause.PLAYBACK_IO_RELEASE to 1),
            harness.backing.syncScheduler.lastAutoShadowDiagnostic?.causeCounts,
        )

        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun deviceReadinessCommitsStoreAndMemoryBeforeAcceptingGenerationCursor() = runTest {
        val existing = SongFixtures.song("ms_188").copy(
            title = "Before",
            mediaUri = "content://media/external_primary/audio/media/188",
            fileName = "track188.flac",
            folderPath = "Music",
            filePath = "Music/track188.flac",
            sizeBytes = 188L,
            dateModifiedMs = 2_000L,
        )
        val row = DeviceDeltaRow(
            channel = DeviceDeltaChannel.AUDIO,
            volumeName = "external_primary",
            mediaStoreId = 188L,
            mediaUri = existing.mediaUri,
            displayName = existing.fileName,
            mimeType = "audio/flac",
            relativePath = "Music/",
            sizeBytes = existing.sizeBytes,
            dateModifiedMs = 3_000L,
            generationAdded = 12L,
            generationModified = 12L,
            eligibility = LibraryEligibility.ELIGIBLE,
        )
        val observation = DeviceAutoSyncShadowObservation.DeltaCandidate(
            batch = emptyDeviceDeltaBatch(10L, 12L).copy(rows = listOf(row)),
            lyricsSidecarInventory = completeLyricsInventory(),
            presenceInventory = completePresenceInventoryFor(existing),
            advanceTo = deviceGenerationSnapshot(12L),
            followUpRequired = false,
        )
        val shadow = ControlledDeviceAutoSyncShadow(observation = observation)
        val updated = existing.copy(
            title = "After",
            dateModifiedMs = row.dateModifiedMs,
        )
        val lyrics = ScannedSongLyrics(
            songId = updated.id,
            revision = updated.lyricsCacheRevision,
            slots = LyricsSlots(),
        )
        val runtime = DeviceShadowProbeRuntime { request ->
            assertEquals(existing.id, request.probePlan.ready.single().stableObjectKey)
            DeviceShadowProbeExecutionResult(
                resolvedObjectsByStableObjectKey = emptyMap(),
                resolvedSongsByStableObjectKey = mapOf(existing.id to updated),
                resolvedLyricsByStableObjectKey = mapOf(existing.id to lyrics),
                fullReplaceLyricsStableObjectKeys = setOf(existing.id),
                issues = emptyList(),
            )
        }
        val store = FakeLibraryStore()
        val harness = scanHarness(
            scanner = ControlledScanner(),
            store = store,
            deviceAutoSyncShadow = shadow,
            deviceShadowProbeRuntime = runtime,
        )
        activateDeviceSource(harness.backing)
        harness.backing.lastScanAtMs = 555L
        harness.backing.lastScanSource = ScanSource.DEVICE
        harness.backing.replaceSongs(listOf(existing))
        var storeCommitObserved = false
        store.afterAutoSnapshotStoreCommit = {
            storeCommitObserved = true
            assertTrue(shadow.accepted.isEmpty())
            assertEquals("Before", harness.backing.songs.single().title)
            assertEquals("After", store.syncedSongs.single().title)
        }

        harness.orchestrator.executeAutoSyncForReadiness(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
                ),
                requestSequence = 196L,
                dirtySequenceAtStart = 28L,
            ),
        )

        assertTrue(storeCommitObserved)
        assertEquals("After", harness.backing.songs.single().title)
        assertEquals(1, store.autoSyncSnapshotCommitCount)
        assertEquals(12L, store.syncCheckpoints.single().generation)
        assertEquals(listOf(lyrics), store.appliedLyrics)
        assertTrue(store.stagedLyrics.isEmpty())
        assertEquals(1, shadow.accepted.size)
        assertEquals(observation, shadow.accepted.single().first)
        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun deviceReadinessRetryableFailureCommitsCheckpointAndDebtBeforeCursorAdvance() = runTest {
        val existing = SongFixtures.song("ms_189").copy(
            mediaUri = "content://media/external_primary/audio/media/189",
            fileName = "track189.flac",
            folderPath = "Music",
            filePath = "Music/track189.flac",
            sizeBytes = 189L,
            dateModifiedMs = 2_000L,
        )
        val row = DeviceDeltaRow(
            channel = DeviceDeltaChannel.AUDIO,
            volumeName = "external_primary",
            mediaStoreId = 189L,
            mediaUri = existing.mediaUri,
            displayName = existing.fileName,
            mimeType = "audio/flac",
            relativePath = "Music/",
            sizeBytes = existing.sizeBytes,
            dateModifiedMs = 3_000L,
            generationAdded = 12L,
            generationModified = 12L,
            eligibility = LibraryEligibility.ELIGIBLE,
        )
        val observation = DeviceAutoSyncShadowObservation.DeltaCandidate(
            batch = emptyDeviceDeltaBatch(10L, 12L).copy(rows = listOf(row)),
            lyricsSidecarInventory = completeLyricsInventory(),
            presenceInventory = completePresenceInventoryFor(existing),
            advanceTo = deviceGenerationSnapshot(12L),
            followUpRequired = false,
        )
        val shadow = ControlledDeviceAutoSyncShadow(observation = observation)
        val runtime = DeviceShadowProbeRuntime { request ->
            assertEquals(existing.id, request.probePlan.ready.single().stableObjectKey)
            DeviceShadowProbeExecutionResult(
                resolvedObjectsByStableObjectKey = emptyMap(),
                issues = listOf(
                    DeviceShadowProbeIssue(
                        stableObjectKey = existing.id,
                        kind = DeviceShadowProbeIssueKind.PROBE_FAILED,
                        detail = "read-failed",
                    ),
                ),
            )
        }
        val store = FakeLibraryStore()
        val harness = scanHarness(
            scanner = ControlledScanner(),
            store = store,
            deviceAutoSyncShadow = shadow,
            deviceShadowProbeRuntime = runtime,
        )
        activateDeviceSource(harness.backing)
        harness.backing.replaceSongs(listOf(existing))

        harness.orchestrator.executeAutoSyncForReadiness(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
                ),
                requestSequence = 197L,
                dirtySequenceAtStart = 29L,
            ),
        )

        assertEquals(0, store.autoSyncSnapshotCommitCount)
        assertEquals(12L, store.syncCheckpoints.single().generation)
        val retry = store.retryItems.single()
        assertEquals(existing.id, retry.stableObjectKey)
        assertEquals(DeviceShadowProbeIssueKind.PROBE_FAILED.name, retry.failureKind)
        assertEquals(1, retry.attemptCount)
        assertTrue(retry.nextRetryAtMs > 1_234L)
        assertEquals(1, shadow.accepted.size)
        assertEquals(listOf(existing), harness.backing.songs)
        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun deviceReadinessNoChangeConsumesDueRetryWithoutNewGeneration() = runTest {
        val existing = SongFixtures.song("ms_190").copy(
            title = "Before retry",
            mediaUri = "content://media/external_primary/audio/media/190",
            fileName = "track190.flac",
            folderPath = "Music",
            filePath = "Music/track190.flac",
            sizeBytes = 190L,
            dateModifiedMs = 2_000L,
        )
        val row = DeviceDeltaRow(
            channel = DeviceDeltaChannel.AUDIO,
            volumeName = "external_primary",
            mediaStoreId = 190L,
            mediaUri = existing.mediaUri,
            displayName = existing.fileName,
            mimeType = "audio/flac",
            relativePath = "Music/",
            sizeBytes = existing.sizeBytes,
            dateModifiedMs = 3_000L,
            generationAdded = 12L,
            generationModified = 12L,
            eligibility = LibraryEligibility.ELIGIBLE,
        )
        val observation = DeviceAutoSyncShadowObservation.NoChange(
            snapshot = deviceGenerationSnapshot(12L),
        )
        val shadow = ControlledDeviceAutoSyncShadow(observation = observation)
        val updated = existing.copy(
            title = "Recovered by retry",
            dateModifiedMs = row.dateModifiedMs,
        )
        val retryRuntime = DeviceRetryObservationRuntime { request ->
            assertEquals(existing.id, request.retryItems.single().stableObjectKey)
            DeviceRetryObservationResult(
                observedRowsByStableObjectKey = mapOf(existing.id to row),
                missingStableObjectKeys = emptySet(),
                unavailableStableObjectKeys = emptySet(),
                lyricsInventory = completeLyricsInventory(),
            )
        }
        val probeRuntime = DeviceShadowProbeRuntime { request ->
            assertEquals(existing.id, request.probePlan.ready.single().stableObjectKey)
            DeviceShadowProbeExecutionResult(
                resolvedObjectsByStableObjectKey = emptyMap(),
                resolvedSongsByStableObjectKey = mapOf(existing.id to updated),
                issues = emptyList(),
            )
        }
        val store = FakeLibraryStore()
        val harness = scanHarness(
            scanner = ControlledScanner(),
            store = store,
            deviceAutoSyncShadow = shadow,
            deviceShadowProbeRuntime = probeRuntime,
            deviceRetryObservationRuntime = retryRuntime,
        )
        activateDeviceSource(harness.backing)
        harness.backing.lastScanAtMs = 555L
        harness.backing.lastScanSource = ScanSource.DEVICE
        harness.backing.replaceSongs(listOf(existing))
        store.syncCheckpoints += LibrarySyncCheckpoint(
            sourceIdentity = SourceIdentityKey.device(),
            partitionKey = DeviceGenerationCheckpointCodec.partitionKey("external_primary"),
            providerVersion = "v1",
            generation = 12L,
            configFingerprint = harness.backing.configFingerprint,
            lastSuccessfulAutoSyncAtMs = 100L,
        )
        store.retryItems += LibraryRetryItem(
            sourceIdentity = SourceIdentityKey.device(),
            retryKey = LibraryRetryKey.deviceObject(existing.id),
            activationEpoch = 1L,
            stableObjectKey = existing.id,
            observedFingerprint = "old-revision",
            retryKind = LibraryRetryKind.OBJECT_PROBE,
            failureKind = DeviceShadowProbeIssueKind.PROBE_FAILED.name,
            attemptCount = 1,
            nextRetryAtMs = 1_000L,
        )

        harness.orchestrator.executeAutoSyncForReadiness(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.DEVICE_RETRY_DUE,
                ),
                requestSequence = 198L,
                dirtySequenceAtStart = 30L,
            ),
        )

        assertEquals("Recovered by retry", harness.backing.songs.single().title)
        assertTrue(store.retryItems.isEmpty())
        assertEquals(12L, store.syncCheckpoints.single().generation)
        assertEquals(1, store.autoSyncSnapshotCommitCount)
        assertTrue(shadow.accepted.isEmpty())
        assertEquals(1, shadow.restoredAnchors.size)
        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun deviceReadinessNoChangeMissingRetryObservationBacksOffWithoutDeletingSong() = runTest {
        val existing = SongFixtures.song("ms_193").copy(
            title = "Keep while uncertain",
            mediaUri = "content://media/external_primary/audio/media/193",
            fileName = "track193.flac",
            folderPath = "Music",
            filePath = "Music/track193.flac",
        )
        val observation = DeviceAutoSyncShadowObservation.NoChange(
            snapshot = deviceGenerationSnapshot(12L),
        )
        val shadow = ControlledDeviceAutoSyncShadow(observation = observation)
        val retryRuntime = DeviceRetryObservationRuntime { request ->
            assertEquals(existing.id, request.retryItems.single().stableObjectKey)
            DeviceRetryObservationResult(
                observedRowsByStableObjectKey = emptyMap(),
                missingStableObjectKeys = setOf(existing.id),
                unavailableStableObjectKeys = emptySet(),
                lyricsInventory = completeLyricsInventory(),
            )
        }
        val store = FakeLibraryStore()
        val harness = scanHarness(
            scanner = ControlledScanner(),
            store = store,
            deviceAutoSyncShadow = shadow,
            deviceRetryObservationRuntime = retryRuntime,
        )
        activateDeviceSource(harness.backing)
        harness.backing.lastScanAtMs = 555L
        harness.backing.lastScanSource = ScanSource.DEVICE
        harness.backing.replaceSongs(listOf(existing))
        store.syncCheckpoints += LibrarySyncCheckpoint(
            sourceIdentity = SourceIdentityKey.device(),
            partitionKey = DeviceGenerationCheckpointCodec.partitionKey("external_primary"),
            providerVersion = "v1",
            generation = 12L,
            configFingerprint = harness.backing.configFingerprint,
            lastSuccessfulAutoSyncAtMs = 100L,
        )
        store.retryItems += LibraryRetryItem(
            sourceIdentity = SourceIdentityKey.device(),
            retryKey = LibraryRetryKey.deviceObject(existing.id),
            activationEpoch = 1L,
            stableObjectKey = existing.id,
            observedFingerprint = "old-revision",
            retryKind = LibraryRetryKind.OBJECT_PROBE,
            failureKind = DeviceShadowProbeIssueKind.PROBE_FAILED.name,
            attemptCount = 1,
            nextRetryAtMs = 1_000L,
        )

        harness.orchestrator.executeAutoSyncForReadiness(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.DEVICE_RETRY_DUE,
                ),
                requestSequence = 204L,
                dirtySequenceAtStart = 36L,
            ),
        )

        assertEquals(listOf(existing), harness.backing.songs)
        val retry = store.retryItems.single()
        assertEquals(2, retry.attemptCount)
        assertEquals(61_234L, retry.nextRetryAtMs)
        assertEquals(DeviceShadowRetryPlanner.RETRY_OBSERVATION_MISSING, retry.failureKind)
        assertEquals(12L, store.syncCheckpoints.single().generation)
        assertEquals(0, store.autoSyncSnapshotCommitCount)
        assertEquals(1, store.autoSyncStateMutations.size)
        assertTrue(shadow.accepted.isEmpty())
        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun deviceReadinessProbeTimeCatalogRemovalStaleDropsWithoutResurrection() = runTest {
        val removedLocally = SongFixtures.song("ms_191").copy(
            mediaUri = "content://media/external_primary/audio/media/191",
            fileName = "remove-me.flac",
            folderPath = "Music",
            filePath = "Music/remove-me.flac",
        )
        val dirty = SongFixtures.song("ms_192").copy(
            title = "Before",
            mediaUri = "content://media/external_primary/audio/media/192",
            fileName = "dirty.flac",
            folderPath = "Music",
            filePath = "Music/dirty.flac",
            sizeBytes = 192L,
            dateModifiedMs = 2_000L,
        )
        val row = DeviceDeltaRow(
            channel = DeviceDeltaChannel.AUDIO,
            volumeName = "external_primary",
            mediaStoreId = 192L,
            mediaUri = dirty.mediaUri,
            displayName = dirty.fileName,
            mimeType = "audio/flac",
            relativePath = "Music/",
            sizeBytes = dirty.sizeBytes,
            dateModifiedMs = 3_000L,
            generationAdded = 12L,
            generationModified = 12L,
            eligibility = LibraryEligibility.ELIGIBLE,
        )
        val observation = DeviceAutoSyncShadowObservation.DeltaCandidate(
            batch = emptyDeviceDeltaBatch(10L, 12L).copy(rows = listOf(row)),
            lyricsSidecarInventory = completeLyricsInventory(),
            presenceInventory = completePresenceInventoryFor(removedLocally, dirty),
            advanceTo = deviceGenerationSnapshot(12L),
            followUpRequired = false,
        )
        val shadow = ControlledDeviceAutoSyncShadow(observation = observation)
        val updated = dirty.copy(title = "After", dateModifiedMs = row.dateModifiedMs)
        lateinit var harness: OrchestratorHarness
        val runtime = DeviceShadowProbeRuntime { request ->
            assertEquals(dirty.id, request.probePlan.ready.single().stableObjectKey)
            harness.backing.catalog.removeSong(removedLocally.id)
            DeviceShadowProbeExecutionResult(
                resolvedObjectsByStableObjectKey = emptyMap(),
                resolvedSongsByStableObjectKey = mapOf(dirty.id to updated),
                issues = emptyList(),
            )
        }
        val store = FakeLibraryStore()
        harness = scanHarness(
            scanner = ControlledScanner(),
            store = store,
            deviceAutoSyncShadow = shadow,
            deviceShadowProbeRuntime = runtime,
        )
        activateDeviceSource(harness.backing)
        harness.backing.lastScanAtMs = 555L
        harness.backing.lastScanSource = ScanSource.DEVICE
        val initialPrepared = harness.backing.catalog.prepareLibrarySongs(
            raw = listOf(removedLocally, dirty),
            field = harness.backing.sortField,
            direction = harness.backing.sortDirection,
            diagnosticTag = "Test",
            diagnosticReason = "deviceReadinessProbeTimeCatalogRemovalBaseline",
        )
        harness.backing.catalog.adoptPrepared(initialPrepared)

        harness.orchestrator.executeAutoSyncForReadiness(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
                ),
                requestSequence = 199L,
                dirtySequenceAtStart = 31L,
            ),
        )

        assertEquals(listOf(dirty.id), harness.backing.songs.map(Song::id))
        assertEquals("Before", harness.backing.songs.single().title)
        assertEquals(0, store.autoSyncSnapshotCommitCount)
        assertTrue(store.autoSyncStateMutations.isEmpty())
        assertTrue(shadow.accepted.isEmpty())
        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun deviceReadinessBridgeStaleAfterFirstLyricsStageCleansPendingRows() = runTest {
        val store = FakeLibraryStore()
        val harness = scanHarness(ControlledScanner(), store)
        activateDeviceSource(harness.backing)
        harness.backing.lastScanAtMs = 555L
        harness.backing.lastScanSource = ScanSource.DEVICE
        val current = SongFixtures.song("device-stage-stale").copy(title = "Before")
        val updated = current.copy(title = "After", dateModifiedMs = current.dateModifiedMs + 1L)
        harness.backing.replaceSongs(listOf(current))
        val token = requireNotNull(
            harness.backing.beginActiveAutoSyncOperationToken(
                requestSequence = 200L,
                dirtySequenceAtStart = 32L,
                cause = LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
            ),
        )
        val fullLyrics = ScannedSongLyrics(
            updated.id,
            updated.lyricsCacheRevision,
            LyricsSlots(embedded = updated.lyricsDocument),
        )
        val externalLyrics = ScannedSongLyrics(
            updated.id,
            updated.lyricsCacheRevision,
            LyricsSlots(externalTtml = updated.lyricsDocument),
        )
        store.afterStageLyrics = { harness.backing.scanGeneration += 1 }
        val plan = DeviceAutoSyncPublicationPlan(
            nextSnapshot = listOf(updated),
            visibleDelta = AutoSyncVisibleDelta(updatedIds = setOf(updated.id)),
            membershipChanges = emptyList(),
            autoSyncStateMutation = LibraryAutoSyncStateMutation(token.sourceIdentity),
            fullLyricsToStage = listOf(fullLyrics),
            externalLyricsToStage = listOf(externalLyrics),
            checkpointIncluded = false,
        )

        val result = harness.orchestrator.publishDeviceAutoSyncPlanForReadiness(
            token = token,
            scanStartSnapshot = listOf(current),
            plan = plan,
        )

        assertNull(result)
        assertEquals(0, store.autoSyncSnapshotCommitCount)
        assertTrue(store.appliedLyrics.isEmpty())
        assertTrue(store.stagedLyrics.isEmpty())
        assertEquals("Before", harness.backing.songs.single().title)
        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun deviceReadinessBridgeCancellationAfterDualLyricsStagingCleansBothRows() = runTest {
        val store = FakeLibraryStore()
        val harness = scanHarness(ControlledScanner(), store)
        activateDeviceSource(harness.backing)
        harness.backing.lastScanAtMs = 555L
        harness.backing.lastScanSource = ScanSource.DEVICE
        val first = SongFixtures.song("device-stage-cancel-full").copy(title = "Before full")
        val second = SongFixtures.song("device-stage-cancel-external").copy(title = "Before external")
        val firstUpdated = first.copy(title = "After full", dateModifiedMs = first.dateModifiedMs + 1L)
        val secondUpdated = second.copy(
            title = "After external",
            externalLyricsSignature = "changed",
        )
        harness.backing.replaceSongs(listOf(first, second))
        val token = requireNotNull(
            harness.backing.beginActiveAutoSyncOperationToken(
                requestSequence = 201L,
                dirtySequenceAtStart = 33L,
                cause = LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
            ),
        )
        val fullLyrics = ScannedSongLyrics(
            firstUpdated.id,
            firstUpdated.lyricsCacheRevision,
            LyricsSlots(embedded = firstUpdated.lyricsDocument),
        )
        val externalLyrics = ScannedSongLyrics(
            secondUpdated.id,
            secondUpdated.lyricsCacheRevision,
            LyricsSlots(externalTtml = secondUpdated.lyricsDocument),
        )
        val plan = DeviceAutoSyncPublicationPlan(
            nextSnapshot = listOf(firstUpdated, secondUpdated),
            visibleDelta = AutoSyncVisibleDelta(
                updatedIds = setOf(firstUpdated.id, secondUpdated.id),
            ),
            membershipChanges = emptyList(),
            autoSyncStateMutation = LibraryAutoSyncStateMutation(token.sourceIdentity),
            fullLyricsToStage = listOf(fullLyrics),
            externalLyricsToStage = listOf(externalLyrics),
            checkpointIncluded = false,
        )

        var stagedCount = 0
        lateinit var publication:
            kotlinx.coroutines.Deferred<com.mica.music.data.local.LibrarySyncResult?>
        store.afterStageLyrics = {
            stagedCount += 1
            if (stagedCount == 2) publication.cancel()
        }
        publication = async {
            harness.orchestrator.publishDeviceAutoSyncPlanForReadiness(
                token = token,
                scanStartSnapshot = listOf(first, second),
                plan = plan,
            )
        }
        runCurrent()
        publication.cancelAndJoin()

        assertEquals(2, stagedCount)
        assertEquals(0, store.autoSyncSnapshotCommitCount)
        assertTrue(store.appliedLyrics.isEmpty())
        assertTrue(store.stagedLyrics.isEmpty())
        assertEquals(
            listOf("Before full", "Before external"),
            harness.backing.songs.map(Song::title),
        )
        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun deviceMassDeletionQuarantineHoldsShadowAndReadinessCursorWithoutAuthorityMutation() = runTest {
        val current = List(100) { index ->
            SongFixtures.song("device-quarantine-${index + 1}")
        }
        val kept = current.last()
        val observation = DeviceAutoSyncShadowObservation.DeltaCandidate(
            batch = emptyDeviceDeltaBatch(10L, 12L),
            lyricsSidecarInventory = completeLyricsInventory(),
            presenceInventory = completePresenceInventoryFor(kept),
            advanceTo = deviceGenerationSnapshot(12L),
            followUpRequired = false,
        )
        val shadow = ControlledDeviceAutoSyncShadow(observation = observation)
        val store = FakeLibraryStore()
        val harness = scanHarness(
            scanner = ControlledScanner(),
            store = store,
            deviceAutoSyncShadow = shadow,
        )
        activateDeviceSource(harness.backing)
        harness.backing.lastScanAtMs = 555L
        harness.backing.lastScanSource = ScanSource.DEVICE
        harness.backing.replaceSongs(current)

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
                ),
                requestSequence = 202L,
                dirtySequenceAtStart = 34L,
            ),
        )

        assertEquals(1, shadow.observeCalls)
        assertTrue(shadow.accepted.isEmpty())
        assertEquals(100, harness.backing.songs.size)
        assertEquals(0, store.autoSyncSnapshotCommitCount)
        assertTrue(store.autoSyncStateMutations.isEmpty())

        harness.orchestrator.executeAutoSyncForReadiness(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
                ),
                requestSequence = 203L,
                dirtySequenceAtStart = 35L,
            ),
        )

        assertEquals(2, shadow.observeCalls)
        assertTrue(shadow.accepted.isEmpty())
        assertEquals(100, harness.backing.songs.size)
        assertEquals(0, store.autoSyncSnapshotCommitCount)
        assertTrue(store.autoSyncStateMutations.isEmpty())
        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun s3DeviceShadowUnsafePresenceCapabilityDoesNotAdvanceCursor() = runTest {
        val audio = DeviceMediaStoreChannelCapability(
            partitionKey = com.mica.music.data.scanner.DiscoveryPartitions.MEDIASTORE_AUDIO,
            columnsAvailable = setOf(
                android.provider.MediaStore.MediaColumns.IS_PENDING,
                android.provider.MediaStore.MediaColumns.IS_TRASHED,
            ),
            rowInclusionSemanticsKnown = true,
            permissionScope = "READ_MEDIA_AUDIO=granted",
            permissionScopeComplete = true,
            volumeScope = "external-aggregate",
            apiLevel = 35,
        )
        val files = audio.copy(
            partitionKey =
                com.mica.music.data.scanner.DiscoveryPartitions.MEDIASTORE_FILES_FALLBACK,
            permissionScope = "READ_MEDIA_AUDIO=denied",
            permissionScopeComplete = false,
        )
        val unsafePresence = completePresenceInventory().copy(
            deviceMediaStoreCapabilityProfile = DeviceMediaStorePresenceCapabilityProfile(
                channels = mapOf(
                    audio.partitionKey to audio,
                    files.partitionKey to files,
                ),
            ),
        )
        val observation = DeviceAutoSyncShadowObservation.DeltaCandidate(
            batch = emptyDeviceDeltaBatch(10L, 12L),
            lyricsSidecarInventory = completeLyricsInventory(),
            presenceInventory = unsafePresence,
            advanceTo = deviceGenerationSnapshot(12L),
            followUpRequired = false,
        )
        val shadow = ControlledDeviceAutoSyncShadow(observation = observation)
        val harness = scanHarness(
            scanner = ControlledScanner(),
            deviceAutoSyncShadow = shadow,
        )
        activateDeviceSource(harness.backing)

        harness.orchestrator.executeScheduled(
            ScheduledLibraryOperation(
                request = LibraryOperationRequest.AutoSync(
                    LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
                ),
                requestSequence = 93L,
                dirtySequenceAtStart = 15L,
            ),
        )

        assertEquals(1, shadow.observeCalls)
        assertTrue(shadow.accepted.isEmpty())
        harness.backing.release()
    }

    @Test
    fun s3DeviceShadowNewerPostGenerationRunsImmediateSchedulerFollowUp() = runTest {
        val first = DeviceAutoSyncShadowObservation.DeltaCandidate(
            batch = emptyDeviceDeltaBatch(10L, 12L),
            lyricsSidecarInventory = completeLyricsInventory(),
            presenceInventory = completePresenceInventory(),
            advanceTo = deviceGenerationSnapshot(12L),
            followUpRequired = true,
        )
        val second = DeviceAutoSyncShadowObservation.DeltaCandidate(
            batch = emptyDeviceDeltaBatch(12L, 14L),
            lyricsSidecarInventory = completeLyricsInventory(),
            presenceInventory = completePresenceInventory(),
            advanceTo = deviceGenerationSnapshot(14L),
            followUpRequired = false,
        )
        val shadow = ControlledDeviceAutoSyncShadow(
            observation = first,
            subsequentObservations = ArrayDeque(listOf(second)),
        )
        val harness = scanHarness(
            scanner = ControlledScanner(),
            deviceAutoSyncShadow = shadow,
        )
        activateDeviceSource(harness.backing)

        harness.backing.syncScheduler.markDirty(LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY)
        advanceTimeBy(1_500L)
        runCurrent()

        assertEquals(2, shadow.observeCalls)
        assertEquals(2, shadow.accepted.size)
        assertFalse(harness.backing.syncScheduler.pendingDirty)
        assertEquals(
            AutoSyncWakeReason.IN_PASS_FOLLOW_UP,
            harness.backing.syncScheduler.lastAutoShadowDiagnostic?.wakeReason,
        )
        harness.backing.release()
        runCurrent()
    }
    @Test
    fun safReadinessBridgeStagesLyricsThenPublishesVisiblePlan() = runTest {
        val store = FakeLibraryStore()
        val harness = scanHarness(ControlledScanner(), store)
        val tree = Uri.parse("content://provider/tree/readiness-success")
        activateFolderSource(harness.backing, tree, "Readiness")
        harness.backing.lastScanAtMs = 500L
        harness.backing.lastScanSource = ScanSource.FOLDER
        val current = SongFixtures.song("saf-readiness-success").copy(
            title = "Before",
            dateModifiedMs = 1L,
        )
        val updated = current.copy(
            title = "After",
            dateModifiedMs = 2L,
        )
        harness.backing.replaceSongs(listOf(current))
        val token = requireNotNull(
            harness.backing.beginActiveAutoSyncOperationToken(
                requestSequence = 45L,
                dirtySequenceAtStart = 8L,
                cause = LibraryOperationCause.SAF_PERIODIC_VERIFY,
            ),
        )
        val ttml = updated.lyricsDocument.copy(
            format = com.mica.music.data.LyricsFormat.TTML,
            origin = com.mica.music.data.LyricsOrigin.EXTERNAL,
        )
        val lyrics = ScannedSongLyrics(
            updated.id,
            updated.lyricsCacheRevision,
            LyricsSlots(externalTtml = ttml),
        )
        val mutation = LibraryAutoSyncStateMutation(
            sourceIdentity = token.sourceIdentity,
            checkpoints = listOf(
                LibrarySyncCheckpoint(
                    sourceIdentity = token.sourceIdentity,
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    providerVersion = SafAutoSyncPublicationPlanner.SAF_CHECKPOINT_PROVIDER_VERSION,
                    generation = SafAutoSyncPublicationPlanner.SAF_CHECKPOINT_GENERATION,
                    configFingerprint = token.configFingerprint,
                    lastSuccessfulAutoSyncAtMs = 1_000L,
                ),
            ),
        )
        val plan = SafAutoSyncPublicationPlan(
            nextSnapshot = listOf(updated),
            visibleDelta = AutoSyncVisibleDelta(updatedIds = setOf(updated.id)),
            membershipChanges = emptyList(),
            autoSyncStateMutation = mutation,
            lyricsToStage = listOf(lyrics),
            checkpointIncluded = true,
        )

        val result = harness.orchestrator.publishSafAutoSyncPlanForReadiness(
            token = token,
            scanStartSnapshot = listOf(current),
            plan = plan,
        )

        assertTrue(result != null)
        assertEquals(1, store.autoSyncSnapshotCommitCount)
        assertEquals(listOf(mutation), store.autoSyncStateMutations)
        assertEquals(listOf(lyrics), store.appliedLyrics)
        assertTrue(store.stagedLyrics.isEmpty())
        assertEquals("After", harness.backing.songs.single().title)
        assertEquals(1L, harness.backing.libraryChangeRevision)

        clearFolderPrefs(harness.backing)
        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun safNoMutationPlanStillDropsAtFinalGateWhenTokenIsStale() = runTest {
        val store = FakeLibraryStore()
        val harness = scanHarness(ControlledScanner(), store)
        val tree = Uri.parse("content://provider/tree/readiness-no-mutation-stale")
        activateFolderSource(harness.backing, tree, "Readiness")
        val current = SongFixtures.song("saf-no-mutation-stale")
        harness.backing.replaceSongs(listOf(current))
        val stale = requireNotNull(
            harness.backing.beginActiveAutoSyncOperationToken(
                requestSequence = 451L,
                dirtySequenceAtStart = 81L,
                cause = LibraryOperationCause.SAF_PERIODIC_VERIFY,
            ),
        )
        requireNotNull(
            harness.backing.beginActiveAutoSyncOperationToken(
                requestSequence = 452L,
                dirtySequenceAtStart = 82L,
                cause = LibraryOperationCause.SAF_PERIODIC_VERIFY,
            ),
        )
        val plan = SafAutoSyncPublicationPlan(
            nextSnapshot = listOf(current),
            visibleDelta = AutoSyncVisibleDelta(),
            membershipChanges = emptyList(),
            autoSyncStateMutation = LibraryAutoSyncStateMutation(stale.sourceIdentity),
            lyricsToStage = emptyList(),
            checkpointIncluded = false,
        )

        val result = harness.orchestrator.publishSafAutoSyncPlanForReadiness(
            token = stale,
            scanStartSnapshot = listOf(current),
            plan = plan,
        )

        assertNull(result)
        assertEquals(0, store.autoSyncSnapshotCommitCount)
        assertTrue(store.autoSyncStateMutations.isEmpty())
        assertEquals(listOf(current), harness.backing.songs)

        clearFolderPrefs(harness.backing)
        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun safReadinessBridgeStaleTokenAfterStagingDropsPublicationAndCleansStaging() = runTest {
        val store = FakeLibraryStore()
        val harness = scanHarness(ControlledScanner(), store)
        val tree = Uri.parse("content://provider/tree/readiness-stale")
        activateFolderSource(harness.backing, tree, "Readiness")
        harness.backing.lastScanAtMs = 500L
        harness.backing.lastScanSource = ScanSource.FOLDER
        val current = SongFixtures.song("saf-readiness-stale").copy(
            title = "Before",
            dateModifiedMs = 1L,
        )
        val updated = current.copy(
            title = "After",
            dateModifiedMs = 2L,
        )
        harness.backing.replaceSongs(listOf(current))
        val token = requireNotNull(
            harness.backing.beginActiveAutoSyncOperationToken(
                requestSequence = 46L,
                dirtySequenceAtStart = 9L,
                cause = LibraryOperationCause.SAF_PERIODIC_VERIFY,
            ),
        )
        val lyrics = ScannedSongLyrics(
            updated.id,
            updated.lyricsCacheRevision,
            LyricsSlots(externalTtml = updated.lyricsDocument),
        )
        store.afterStageLyrics = {
            harness.backing.scanGeneration += 1
        }
        val plan = SafAutoSyncPublicationPlan(
            nextSnapshot = listOf(updated),
            visibleDelta = AutoSyncVisibleDelta(updatedIds = setOf(updated.id)),
            membershipChanges = emptyList(),
            autoSyncStateMutation = LibraryAutoSyncStateMutation(token.sourceIdentity),
            lyricsToStage = listOf(lyrics),
            checkpointIncluded = false,
        )

        val result = harness.orchestrator.publishSafAutoSyncPlanForReadiness(
            token = token,
            scanStartSnapshot = listOf(current),
            plan = plan,
        )

        assertNull(result)
        assertEquals(0, store.autoSyncSnapshotCommitCount)
        assertTrue(store.autoSyncStateMutations.isEmpty())
        assertTrue(store.appliedLyrics.isEmpty())
        assertTrue(store.stagedLyrics.isEmpty())
        assertEquals("Before", harness.backing.songs.single().title)
        assertEquals(0L, harness.backing.libraryChangeRevision)

        clearFolderPrefs(harness.backing)
        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun safReadinessBridgeCancellationAfterStagingCleansPendingLyricsWithoutPublishing() = runTest {
        val store = FakeLibraryStore()
        val harness = scanHarness(ControlledScanner(), store)
        val tree = Uri.parse("content://provider/tree/readiness-cancel")
        activateFolderSource(harness.backing, tree, "Readiness")
        harness.backing.lastScanAtMs = 500L
        harness.backing.lastScanSource = ScanSource.FOLDER
        val current = SongFixtures.song("saf-readiness-cancel").copy(
            title = "Before",
            dateModifiedMs = 1L,
        )
        val updated = current.copy(
            title = "After",
            dateModifiedMs = 2L,
        )
        harness.backing.replaceSongs(listOf(current))
        val token = requireNotNull(
            harness.backing.beginActiveAutoSyncOperationToken(
                requestSequence = 47L,
                dirtySequenceAtStart = 10L,
                cause = LibraryOperationCause.SAF_PERIODIC_VERIFY,
            ),
        )
        val lyrics = ScannedSongLyrics(
            updated.id,
            updated.lyricsCacheRevision,
            LyricsSlots(externalTtml = updated.lyricsDocument),
        )
        val plan = SafAutoSyncPublicationPlan(
            nextSnapshot = listOf(updated),
            visibleDelta = AutoSyncVisibleDelta(updatedIds = setOf(updated.id)),
            membershipChanges = emptyList(),
            autoSyncStateMutation = LibraryAutoSyncStateMutation(token.sourceIdentity),
            lyricsToStage = listOf(lyrics),
            checkpointIncluded = false,
        )

        lateinit var publication: kotlinx.coroutines.Deferred<com.mica.music.data.local.LibrarySyncResult?>
        store.afterStageLyrics = { publication.cancel() }
        publication = async {
            harness.orchestrator.publishSafAutoSyncPlanForReadiness(
                token = token,
                scanStartSnapshot = listOf(current),
                plan = plan,
            )
        }
        runCurrent()
        publication.cancelAndJoin()

        assertEquals(0, store.autoSyncSnapshotCommitCount)
        assertTrue(store.autoSyncStateMutations.isEmpty())
        assertTrue(store.appliedLyrics.isEmpty())
        assertTrue(store.stagedLyrics.isEmpty())
        assertEquals("Before", harness.backing.songs.single().title)
        assertEquals(0L, harness.backing.libraryChangeRevision)

        clearFolderPrefs(harness.backing)
        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun autoPublishNoChangeCommitsCheckpointOnlyWithoutSnapshotRebuild() = runTest {
        val store = FakeLibraryStore()
        val harness = scanHarness(ControlledScanner(), store)
        activateDeviceSource(harness.backing)
        harness.backing.lastScanAtMs = 555L
        harness.backing.lastScanSource = ScanSource.DEVICE
        val existing = SongFixtures.song("auto-no-change")
        harness.backing.replaceSongs(listOf(existing))
        val token = requireNotNull(
            harness.backing.beginOperationToken(
                source = ScanSource.DEVICE,
                requestSequence = 46L,
                dirtySequenceAtStart = 9L,
                mode = LibraryOperationMode.AUTO_SYNC,
                cause = LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
            ),
        )
        val mutation = LibraryAutoSyncStateMutation(
            sourceIdentity = token.sourceIdentity,
            checkpoints = listOf(
                LibrarySyncCheckpoint(
                    sourceIdentity = token.sourceIdentity,
                    partitionKey = "mediastore:audio",
                    providerVersion = "v2",
                    generation = 20L,
                    configFingerprint = token.configFingerprint,
                    lastSuccessfulAutoSyncAtMs = 999L,
                ),
            ),
        )

        val result = harness.orchestrator.publishAutoSyncSnapshot(
            token = token,
            scanStartSnapshot = listOf(existing),
            nextSnapshot = listOf(existing),
            visibleDelta = AutoSyncVisibleDelta(),
            membershipChanges = emptyList(),
            autoSyncStateMutation = mutation,
        )

        assertEquals(0, result?.added)
        assertEquals(0, store.autoSyncSnapshotCommitCount)
        assertEquals(listOf(mutation), store.autoSyncStateMutations)
        assertEquals(0L, harness.backing.libraryChangeRevision)
        assertEquals(555L, harness.backing.lastScanAtMs)
        assertEquals(listOf(existing.id), harness.backing.songs.map(Song::id))
        harness.backing.release()
    }

    @Test
    fun autoPublishConfirmedMissingCommitsSnapshotCheckpointAndPlaylistFollowupTogether() = runTest {
        val store = FakeLibraryStore()
        val harness = scanHarness(ControlledScanner(), store)
        activateDeviceSource(harness.backing)
        harness.backing.lastScanAtMs = 777L
        harness.backing.lastScanSource = ScanSource.DEVICE
        val removed = SongFixtures.song("ms_1")
        val kept = SongFixtures.song("ms_2")
        harness.backing.replaceSongs(listOf(removed, kept))
        val token = requireNotNull(
            harness.backing.beginOperationToken(
                source = ScanSource.DEVICE,
                requestSequence = 47L,
                dirtySequenceAtStart = 10L,
                mode = LibraryOperationMode.AUTO_SYNC,
                cause = LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
            ),
        )
        val change = MembershipChange(
            stableObjectKey = removed.id,
            songId = removed.id,
            reason = MembershipRemovalReason.CONFIRMED_MISSING,
            evidenceRevision = "inventory-v2",
            sourceIdentity = token.sourceIdentity,
        )
        val mutation = LibraryAutoSyncStateMutation(
            sourceIdentity = token.sourceIdentity,
            checkpoints = listOf(
                LibrarySyncCheckpoint(
                    sourceIdentity = token.sourceIdentity,
                    partitionKey = "mediastore:audio",
                    providerVersion = "v2",
                    generation = 21L,
                    configFingerprint = token.configFingerprint,
                    lastSuccessfulAutoSyncAtMs = 1_000L,
                ),
            ),
        )

        val result = harness.orchestrator.publishAutoSyncSnapshot(
            token = token,
            scanStartSnapshot = listOf(removed, kept),
            nextSnapshot = listOf(kept),
            visibleDelta = AutoSyncVisibleDelta(
                removedStableObjectKeys = setOf(removed.id),
            ),
            membershipChanges = listOf(change),
            autoSyncStateMutation = mutation,
        )

        assertTrue(result != null)
        assertEquals(1, store.autoSyncSnapshotCommitCount)
        assertEquals(listOf(mutation), store.autoSyncStateMutations)
        val followup = store.autoSyncSnapshotFollowups.single().single()
        assertEquals(
            LibraryFollowupProtocol.PLAYLIST_REMOVE_CONFIRMED_MISSING,
            followup.action,
        )
        assertEquals(removed.id, LibraryFollowupProtocol.playlistRemovalSongId(followup.payload))
        assertEquals(1L, followup.libraryRevision)
        assertEquals(1L, harness.backing.libraryChangeRevision)
        assertEquals(
            MembershipRemovalReason.CONFIRMED_MISSING,
            harness.backing.lastLibraryChangeSet?.membershipChanges?.single()?.reason,
        )
        assertEquals(listOf(kept.id), harness.backing.songs.map(Song::id))
        assertEquals(777L, harness.backing.lastScanAtMs)
        assertEquals(ScanSource.DEVICE, harness.backing.lastScanSource)
        harness.backing.release()
    }

    @Test
    fun cancellationAfterAutoStoreCommitStillCompletesMemoryAdopt() = runTest {
        val store = FakeLibraryStore()
        val harness = scanHarness(ControlledScanner(), store)
        activateDeviceSource(harness.backing)
        harness.backing.lastScanAtMs = 700L
        harness.backing.lastScanSource = ScanSource.DEVICE
        val current = SongFixtures.song("auto-cancel-after-store").copy(title = "Before")
        val updated = current.copy(title = "After")
        harness.backing.replaceSongs(listOf(current))
        val token = requireNotNull(
            harness.backing.beginOperationToken(
                source = ScanSource.DEVICE,
                requestSequence = 481L,
                dirtySequenceAtStart = 12L,
                mode = LibraryOperationMode.AUTO_SYNC,
                cause = LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
            ),
        )
        lateinit var publication: kotlinx.coroutines.Deferred<com.mica.music.data.local.LibrarySyncResult?>
        store.afterAutoSnapshotStoreCommit = { publication.cancel() }
        publication = async {
            harness.orchestrator.publishAutoSyncSnapshot(
                token = token,
                scanStartSnapshot = listOf(current),
                nextSnapshot = listOf(updated),
                visibleDelta = AutoSyncVisibleDelta(updatedIds = setOf(updated.id)),
                membershipChanges = emptyList(),
                autoSyncStateMutation = LibraryAutoSyncStateMutation(token.sourceIdentity),
            )
        }

        runCurrent()
        publication.cancelAndJoin()

        assertEquals(listOf(updated.id), store.syncedSongs.map(Song::id))
        assertEquals("After", harness.backing.songs.single().title)
        assertEquals(1L, harness.backing.libraryChangeRevision)
        assertTrue(harness.backing.hasScanned)
        assertTrue(publication.isCancelled)

        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun releaseAfterAutoStoreCommitStillCompletesMemoryAdoptBeforeReleaseCleanup() = runTest {
        val store = FakeLibraryStore()
        val harness = scanHarness(ControlledScanner(), store)
        activateDeviceSource(harness.backing)
        harness.backing.lastScanAtMs = 700L
        harness.backing.lastScanSource = ScanSource.DEVICE
        val current = SongFixtures.song("auto-release-after-store").copy(title = "Before")
        val updated = current.copy(title = "After")
        harness.backing.replaceSongs(listOf(current))
        val token = requireNotNull(
            harness.backing.beginOperationToken(
                source = ScanSource.DEVICE,
                requestSequence = 482L,
                dirtySequenceAtStart = 13L,
                mode = LibraryOperationMode.AUTO_SYNC,
                cause = LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
            ),
        )
        store.afterAutoSnapshotStoreCommit = {
            harness.backing.release()
        }

        val result = harness.orchestrator.publishAutoSyncSnapshot(
            token = token,
            scanStartSnapshot = listOf(current),
            nextSnapshot = listOf(updated),
            visibleDelta = AutoSyncVisibleDelta(updatedIds = setOf(updated.id)),
            membershipChanges = emptyList(),
            autoSyncStateMutation = LibraryAutoSyncStateMutation(token.sourceIdentity),
        )
        advanceUntilIdle()

        assertTrue(result != null)
        assertEquals(listOf(updated.id), store.syncedSongs.map(Song::id))
        assertEquals("After", harness.backing.songs.single().title)
        assertEquals(1L, harness.backing.libraryChangeRevision)
        assertTrue(harness.backing.hasScanned)
        assertTrue(harness.backing.released)
    }

    @Test
    fun objectDerivedStoreWriteSurvivesUnrelatedAutoGenerationBump() = runTest {
        val store = FakeLibraryStore()
        val harness = scanHarness(ControlledScanner(), store)
        activateDeviceSource(harness.backing)
        val song = SongFixtures.song("cover-object-state").copy(
            albumArtUri = "file:///cover-object-state.jpg",
            coverColorArgb = 0xFFB13B66.toInt(),
        )
        harness.backing.replaceSongs(listOf(song))
        val generationBeforeAuto = harness.backing.scanGeneration

        val token = requireNotNull(
            harness.backing.beginActiveAutoSyncOperationToken(
                requestSequence = 483L,
                dirtySequenceAtStart = 14L,
                cause = LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
            ),
        )
        assertTrue(token.libraryGeneration > generationBeforeAuto)

        val committed = harness.backing.storeWriteIfCurrentObjectState(
            isCurrent = {
                harness.backing.songById(song.id)?.let { current ->
                    current.albumArtUri == song.albumArtUri &&
                        current.coverColorArgb == song.coverColorArgb
                } == true
            },
        ) {
            store.updateCoverColorArgb(song.id, song.coverColorArgb)
        }

        assertTrue(committed)
        assertEquals(listOf(song.id to song.coverColorArgb), store.coverColorWrites)
        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun autoPublishRebaseDoesNotResurrectSongRemovedByUserDuringScan() = runTest {
        val store = FakeLibraryStore()
        val harness = scanHarness(ControlledScanner(), store)
        activateDeviceSource(harness.backing)
        harness.backing.lastScanAtMs = 700L
        harness.backing.lastScanSource = ScanSource.DEVICE
        val removedByUser = SongFixtures.song("auto-user-removed")
        val kept = SongFixtures.song("auto-kept").copy(title = "Before")
        val scanStart = listOf(removedByUser, kept)
        val initialPrepared = harness.backing.catalog.prepareLibrarySongs(
            raw = scanStart,
            field = harness.backing.sortField,
            direction = harness.backing.sortDirection,
            diagnosticTag = "Test",
            diagnosticReason = "autoRebaseBaseline",
        )
        harness.backing.catalog.adoptPrepared(initialPrepared)

        val token = requireNotNull(
            harness.backing.beginOperationToken(
                source = ScanSource.DEVICE,
                requestSequence = 49L,
                dirtySequenceAtStart = 12L,
                mode = LibraryOperationMode.AUTO_SYNC,
                cause = LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
            ),
        )
        harness.backing.catalog.removeSong(removedByUser.id)
        val scannerResult = listOf(
            removedByUser,
            kept.copy(title = "After"),
        )

        val result = harness.orchestrator.publishAutoSyncSnapshot(
            token = token,
            scanStartSnapshot = scanStart,
            nextSnapshot = scannerResult,
            visibleDelta = AutoSyncVisibleDelta(updatedIds = setOf(kept.id)),
            membershipChanges = emptyList(),
            autoSyncStateMutation = LibraryAutoSyncStateMutation(token.sourceIdentity),
        )

        assertTrue(result != null)
        assertEquals(listOf(kept.id), harness.backing.songs.map(Song::id))
        assertEquals("After", harness.backing.songs.single().title)
        assertEquals(1L, harness.backing.libraryChangeRevision)
        harness.backing.release()
    }

    @Test
    fun autoPublishCannotRepopulateLibraryClearedByUser() = runTest {
        val store = FakeLibraryStore()
        val harness = scanHarness(ControlledScanner(), store)
        harness.backing.restorePersistedState(
            PersistedLibraryState(
                intent = LibraryIntentState.CLEARED_BY_USER,
                access = LibraryAccessState.AVAILABLE,
                sourceState = LibrarySourceState(
                    active = SourceActivation(SourceIdentityKey.device(), activationEpoch = 1L),
                ),
                configFingerprint = harness.backing.configFingerprint,
            ),
        )
        harness.backing.lastScanAtMs = 888L
        val token = requireNotNull(
            harness.backing.beginOperationToken(
                source = ScanSource.DEVICE,
                requestSequence = 48L,
                dirtySequenceAtStart = 11L,
                mode = LibraryOperationMode.AUTO_SYNC,
                cause = LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
            ),
        )

        val failure = runCatching {
            harness.orchestrator.publishAutoSyncSnapshot(
                token = token,
                scanStartSnapshot = emptyList(),
                nextSnapshot = listOf(SongFixtures.song("must-not-return")),
                visibleDelta = AutoSyncVisibleDelta(addedIds = setOf("must-not-return")),
                membershipChanges = emptyList(),
                autoSyncStateMutation = LibraryAutoSyncStateMutation(token.sourceIdentity),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, store.autoSyncSnapshotCommitCount)
        assertTrue(store.autoSyncStateMutations.isEmpty())
        assertTrue(harness.backing.songs.isEmpty())
        harness.backing.release()
    }

    @Test
    fun visibleAutoPublicationAllocatesRevisionOnlyAfterSuccessfulStoreCommit() = runTest {
        val harness = scanHarness(ControlledScanner(), FakeLibraryStore())
        activateDeviceSource(harness.backing)
        val token = requireNotNull(
            harness.backing.beginOperationToken(
                source = ScanSource.DEVICE,
                requestSequence = 51L,
                dirtySequenceAtStart = 3L,
                mode = LibraryOperationMode.AUTO_SYNC,
                cause = LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
            ),
        )
        var storeCalls = 0
        var publishedRevision: Long? = null

        val result = harness.backing.commitAutoSyncSnapshotAndPublishIfCurrent(
            token = token,
            changeSetForRevision = { revision ->
                LibraryChangeSet(
                    libraryRevision = revision,
                    cause = token.cause,
                    addedIds = setOf("added"),
                    updatedIds = emptySet(),
                    membershipChanges = emptyList(),
                )
            },
            storeBlock = { changeSet ->
                storeCalls++
                changeSet.libraryRevision
            },
            publishBlock = { committed, changeSet ->
                assertEquals(changeSet.libraryRevision, committed)
                publishedRevision = changeSet.libraryRevision
            },
        )

        assertEquals(1L, result)
        assertEquals(1, storeCalls)
        assertEquals(1L, publishedRevision)
        assertEquals(1L, harness.backing.libraryChangeRevision)
        assertEquals(1L, harness.backing.lastLibraryChangeSet?.libraryRevision)
        harness.backing.release()
    }

    @Test
    fun visibleAutoPublicationDropsStaleTokenWithoutAllocatingRevision() = runTest {
        val harness = scanHarness(ControlledScanner(), FakeLibraryStore())
        activateDeviceSource(harness.backing)
        val stale = requireNotNull(
            harness.backing.beginOperationToken(
                source = ScanSource.DEVICE,
                requestSequence = 52L,
                dirtySequenceAtStart = 4L,
                mode = LibraryOperationMode.AUTO_SYNC,
                cause = LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
            ),
        )
        requireNotNull(
            harness.backing.beginOperationToken(
                source = ScanSource.DEVICE,
                requestSequence = 53L,
                dirtySequenceAtStart = 5L,
                mode = LibraryOperationMode.AUTO_SYNC,
                cause = LibraryOperationCause.MEDIASTORE_FILES_DIRTY,
            ),
        )
        var storeCalls = 0

        val result = harness.backing.commitAutoSyncSnapshotAndPublishIfCurrent(
            token = stale,
            changeSetForRevision = { revision ->
                LibraryChangeSet(
                    libraryRevision = revision,
                    cause = stale.cause,
                    addedIds = setOf("late"),
                    updatedIds = emptySet(),
                    membershipChanges = emptyList(),
                )
            },
            storeBlock = {
                storeCalls++
                Unit
            },
            publishBlock = { _, _ -> },
        )

        assertNull(result)
        assertEquals(0, storeCalls)
        assertEquals(0L, harness.backing.libraryChangeRevision)
        assertNull(harness.backing.lastLibraryChangeSet)
        harness.backing.release()
    }

    @Test
    fun visibleAutoPublicationRejectsNoChangeBeforeStoreMutation() = runTest {
        val harness = scanHarness(ControlledScanner(), FakeLibraryStore())
        activateDeviceSource(harness.backing)
        val token = requireNotNull(
            harness.backing.beginOperationToken(
                source = ScanSource.DEVICE,
                requestSequence = 54L,
                dirtySequenceAtStart = 6L,
                mode = LibraryOperationMode.AUTO_SYNC,
                cause = LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
            ),
        )
        var storeCalls = 0

        val failure = runCatching {
            harness.backing.commitAutoSyncSnapshotAndPublishIfCurrent(
                token = token,
                changeSetForRevision = { revision ->
                    LibraryChangeSet(
                        libraryRevision = revision,
                        cause = token.cause,
                        addedIds = emptySet(),
                        updatedIds = emptySet(),
                        membershipChanges = emptyList(),
                    )
                },
                storeBlock = {
                    storeCalls++
                    Unit
                },
                publishBlock = { _, _ -> },
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, storeCalls)
        assertEquals(0L, harness.backing.libraryChangeRevision)
        harness.backing.release()
    }

    @Test
    fun visibleAutoPublicationRejectsMembershipEvidenceFromDifferentSource() = runTest {
        val harness = scanHarness(ControlledScanner(), FakeLibraryStore())
        activateDeviceSource(harness.backing)
        val token = requireNotNull(
            harness.backing.beginOperationToken(
                source = ScanSource.DEVICE,
                requestSequence = 55L,
                dirtySequenceAtStart = 7L,
                mode = LibraryOperationMode.AUTO_SYNC,
                cause = LibraryOperationCause.MEDIASTORE_AUDIO_DIRTY,
            ),
        )
        var storeCalls = 0
        val wrongSource = SourceIdentityKey.folder("content://tree/wrong")

        val failure = runCatching {
            harness.backing.commitAutoSyncSnapshotAndPublishIfCurrent(
                token = token,
                changeSetForRevision = { revision ->
                    LibraryChangeSet(
                        libraryRevision = revision,
                        cause = token.cause,
                        addedIds = emptySet(),
                        updatedIds = emptySet(),
                        membershipChanges = listOf(
                            MembershipChange(
                                stableObjectKey = "ms_1",
                                songId = "ms_1",
                                reason = MembershipRemovalReason.CONFIRMED_MISSING,
                                evidenceRevision = "rev",
                                sourceIdentity = wrongSource,
                            ),
                        ),
                    )
                },
                storeBlock = {
                    storeCalls++
                    Unit
                },
                publishBlock = { _, _ -> },
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, storeCalls)
        assertEquals(0L, harness.backing.libraryChangeRevision)
        harness.backing.release()
    }

    @Test
    fun successfulPublicationEmitsTransientChangeSetWithCauseAndRevision() = runTest {
        val scanner = ControlledScanner()
        val harness = scanHarness(scanner)
        val first = SongFixtures.song("change-added")
        val second = SongFixtures.song("change-updated")

        val initial = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests[0].result.complete(ScanResult(listOf(first, second), 2))
        initial.await()

        val firstChange = harness.backing.lastLibraryChangeSet!!
        assertEquals(1L, firstChange.libraryRevision)
        assertEquals(LibraryOperationCause.USER_RESCAN, firstChange.cause)
        assertEquals(setOf(first.id, second.id), firstChange.addedIds)
        assertTrue(firstChange.updatedIds.isEmpty())
        assertTrue(firstChange.membershipChanges.isEmpty())

        val updatedSecond = second.copy(title = "Changed title")
        val rescan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests[1].result.complete(ScanResult(listOf(first, updatedSecond), 2))
        rescan.await()

        val secondChange = harness.backing.lastLibraryChangeSet!!
        assertEquals(2L, secondChange.libraryRevision)
        assertEquals(setOf(second.id), secondChange.updatedIds)
        assertTrue(secondChange.addedIds.isEmpty())
        assertEquals(2L, harness.backing.libraryChangeRevision)

        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun identicalPublicationDoesNotAdvanceTransientChangeRevision() = runTest {
        val scanner = ControlledScanner()
        val harness = scanHarness(scanner)
        val song = SongFixtures.song("change-noop")

        val initial = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests[0].result.complete(ScanResult(listOf(song), 1))
        initial.await()
        assertEquals(1L, harness.backing.libraryChangeRevision)

        val rescan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests[1].result.complete(ScanResult(listOf(song), 1))
        rescan.await()

        assertEquals(1L, harness.backing.libraryChangeRevision)
        assertEquals(1L, harness.backing.lastLibraryChangeSet?.libraryRevision)

        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun shadowAuthorityRevisionIgnoresPlayStatsButTracksCanonicalPublication() = runTest {
        val scanner = ControlledScanner()
        val harness = scanHarness(scanner)
        val original = SongFixtures.song("shadow-authority").copy(title = "Before")

        val initial = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests[0].result.complete(ScanResult(listOf(original), 1))
        initial.await()
        harness.backing.catalog.updateSort(SongSortField.PLAY_COUNT, SortDirection.ASC)
        val baselineRevision = harness.backing.shadowAuthorityRevision
        assertTrue(baselineRevision > 0L)

        harness.backing.catalog.applyPlayStats(
            original.id,
            PlayStats(count = 3, totalListenSeconds = 90L, lastPlayedAtMs = 123L),
        )
        assertEquals(baselineRevision, harness.backing.shadowAuthorityRevision)

        val rescan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests[1].result.complete(
            ScanResult(listOf(original.copy(title = "After")), 1),
        )
        rescan.await()

        assertEquals(baselineRevision + 1L, harness.backing.shadowAuthorityRevision)

        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun publicationRebasePreservesPlayStatsMutationThatHappensDuringScan() = runTest {
        val scanner = ControlledScanner()
        val environment = FakeScanEnvironment()
        val harness = scanHarness(scanner, environment = environment)
        val original = SongFixtures.song("rebase-play-stats").copy(title = "Before scan")

        val initial = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests[0].result.complete(ScanResult(listOf(original), 1))
        initial.await()

        val rescan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        val scannerVersion = original.copy(title = "Scanner metadata", playCount = 0, totalListenSeconds = 0L)
        val latestStats = PlayStats(count = 9, totalListenSeconds = 321L, lastPlayedAtMs = 777L)
        environment.playStatsBySongId[original.id] = latestStats
        harness.backing.catalog.applyPlayStats(original.id, latestStats)

        scanner.deviceRequests[1].result.complete(ScanResult(listOf(scannerVersion), 1))
        rescan.await()

        val published = harness.backing.songs.single()
        assertEquals("Scanner metadata", published.title)
        assertEquals(9, published.playCount)
        assertEquals(321L, published.totalListenSeconds)
        assertEquals(777L, published.lastPlayedAtMs)

        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun publicationRebaseDoesNotResurrectSongRemovedDuringScan() = runTest {
        val scanner = ControlledScanner()
        val harness = scanHarness(scanner)
        val removed = SongFixtures.song("rebase-removed")
        val kept = SongFixtures.song("rebase-kept")

        val initial = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        scanner.deviceRequests[0].result.complete(ScanResult(listOf(removed, kept), 2))
        initial.await()

        val rescan = async { harness.orchestrator.scanDeviceWide() }
        runCurrent()
        harness.backing.catalog.removeSong(removed.id)
        runCurrent()

        scanner.deviceRequests[1].result.complete(ScanResult(listOf(removed, kept), 2))
        rescan.await()

        assertEquals(listOf(kept.id), harness.backing.songs.map(Song::id))

        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun configFingerprintChangeInvalidatesInFlightOperationBeforePublication() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val originalMinDuration = LibraryScanSettings.minTrackDurationSec(context)
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val harness = scanHarness(scanner, store)
        try {
            activateDeviceSource(harness.backing)
            val scan = async { harness.orchestrator.scanDeviceWide() }
            runCurrent()
            assertEquals(1, scanner.deviceRequests.size)

            LibraryScanSettings.setMinTrackDurationSec(context, originalMinDuration + 1)
            scanner.deviceRequests.single().result.complete(
                ScanResult(listOf(SongFixtures.song("stale-config")), 1),
            )
            scan.await()
            runCurrent()

            assertTrue(store.syncedSongs.isEmpty())
            assertTrue(harness.backing.songs.isEmpty())
            assertEquals(
                SourceIdentityKey.device(),
                harness.backing.sourceState.active?.sourceIdentity,
            )
        } finally {
            LibraryScanSettings.setMinTrackDurationSec(context, originalMinDuration)
            harness.backing.release()
            advanceUntilIdle()
        }
    }

    @Test
    fun permissionLossMarksDeviceSourceUnavailableWithoutClearingLibrary() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val harness = scanHarness(scanner, store)
        val existing = SongFixtures.song("permission-kept")
        activateDeviceSource(harness.backing)
        harness.backing.replaceSongs(listOf(existing))

        harness.backing.folder.updatePermission(false)
        runCurrent()

        assertEquals(listOf(existing.id), harness.backing.songs.map(Song::id))
        assertFalse(store.clearCompleted)
        assertEquals(LibraryIntentState.ACTIVE, harness.backing.intentState)
        assertEquals(LibraryAccessState.PERMISSION_REQUIRED, harness.backing.accessState)
        assertEquals(
            SourceIdentityKey.device(),
            harness.backing.sourceState.active?.sourceIdentity,
        )

        harness.backing.release()
        advanceUntilIdle()
    }

    @Test
    fun failedFolderSourceSwitchKeepsPreviousActiveSourceAndDiscardsPendingBinding() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val harness = scanHarness(scanner, store)
        val sourceA = Uri.parse("content://mica.test/tree/A")
        val sourceB = Uri.parse("content://mica.test/tree/B")
        val existing = SongFixtures.song("source-a")
        try {
            activateFolderSource(harness.backing, sourceA, "A")
            harness.backing.replaceSongs(listOf(existing))
            LibraryScanSettings.setPendingLibraryFolder(harness.backing.context, sourceB, "B")
            harness.backing.pendingLibraryFolderUri = sourceB.toString()
            harness.backing.pendingLibraryFolderLabel = "B"

            val scan = async { harness.orchestrator.scanLibraryFolder() }
            runCurrent()
            assertEquals(1, scanner.folderRequests.size)

            scanner.folderRequests.single().result.completeExceptionally(
                IllegalStateException("provider failed"),
            )
            scan.await()
            runCurrent()

            assertEquals(listOf(existing.id), harness.backing.songs.map(Song::id))
            assertEquals(sourceA.toString(), harness.backing.libraryFolderUri)
            assertNull(harness.backing.pendingLibraryFolderUri)
            assertEquals(
                SourceIdentityKey.folder(sourceA.toString()),
                harness.backing.sourceState.active?.sourceIdentity,
            )
            assertNull(harness.backing.sourceState.pendingTransition)
            assertEquals(sourceA, LibraryScanSettings.libraryTreeUri(harness.backing.context))
            assertNull(LibraryScanSettings.pendingLibraryTreeUri(harness.backing.context))
            assertTrue(store.syncedSongs.isEmpty())
        } finally {
            clearFolderPrefs(harness.backing)
            harness.backing.release()
            advanceUntilIdle()
        }
    }

    @Test
    fun successfulFolderSourceSwitchPromotesStagingAndBindingAtomically() = runTest {
        val scanner = ControlledScanner()
        val store = FakeLibraryStore()
        val harness = scanHarness(scanner, store)
        val sourceA = Uri.parse("content://mica.test/tree/A-success")
        val sourceB = Uri.parse("content://mica.test/tree/B-success")
        val oldSong = SongFixtures.song("source-a-success")
        val newSong = SongFixtures.song("source-b-success")
        try {
            activateFolderSource(harness.backing, sourceA, "A")
            harness.backing.replaceSongs(listOf(oldSong))
            LibraryScanSettings.setPendingLibraryFolder(harness.backing.context, sourceB, "B")
            harness.backing.pendingLibraryFolderUri = sourceB.toString()
            harness.backing.pendingLibraryFolderLabel = "B"

            val scan = async { harness.orchestrator.scanLibraryFolder() }
            runCurrent()
            val request = scanner.folderRequests.single()
            request.onLyricsBatch?.invoke(
                LyricsScanBatch(
                    completed = listOf(
                        ScannedSongLyrics(
                            newSong.id,
                            newSong.lyricsCacheRevision,
                            LyricsSlots(embedded = newSong.lyricsDocument),
                        ),
                    ),
                    readFailedCount = 0,
                ),
            )

            assertTrue(store.appliedLyrics.isEmpty())
            assertEquals(1, store.stagedLyrics.values.sumOf { it.size })
            assertEquals(sourceA.toString(), harness.backing.libraryFolderUri)

            request.result.complete(ScanResult(listOf(newSong), 1))
            scan.await()
            runCurrent()

            assertEquals(listOf(newSong.id), harness.backing.songs.map(Song::id))
            assertEquals(sourceB.toString(), harness.backing.libraryFolderUri)
            assertNull(harness.backing.pendingLibraryFolderUri)
            assertEquals(
                SourceIdentityKey.folder(sourceB.toString()),
                harness.backing.sourceState.active?.sourceIdentity,
            )
            assertNull(harness.backing.sourceState.pendingTransition)
            assertEquals(sourceB, LibraryScanSettings.libraryTreeUri(harness.backing.context))
            assertNull(LibraryScanSettings.pendingLibraryTreeUri(harness.backing.context))
            assertEquals(1, store.appliedLyrics.size)
            assertTrue(store.stagedLyrics.isEmpty())
            assertEquals(
                SourceIdentityKey.folder(sourceB.toString()),
                store.persistedState?.sourceState?.active?.sourceIdentity,
            )
        } finally {
            clearFolderPrefs(harness.backing)
            harness.backing.release()
            advanceUntilIdle()
        }
    }

    private fun activateFolderSource(
        backing: MusicLibraryBacking,
        treeUri: Uri,
        label: String,
    ) {
        LibraryScanSettings.setLibraryFolder(backing.context, treeUri, label)
        LibraryScanSettings.clearPendingLibraryFolder(backing.context)
        backing.libraryFolderUri = treeUri.toString()
        backing.libraryFolderLabel = label
        backing.pendingLibraryFolderUri = null
        backing.pendingLibraryFolderLabel = null
        backing.lastScanSource = ScanSource.FOLDER
        backing.restorePersistedState(
            PersistedLibraryState(
                intent = LibraryIntentState.ACTIVE,
                access = LibraryAccessState.AVAILABLE,
                sourceState = LibrarySourceState(
                    active = SourceActivation(
                        SourceIdentityKey.folder(treeUri.toString()),
                        activationEpoch = 1L,
                    ),
                ),
                configFingerprint = backing.configFingerprint,
            ),
        )
    }

    private fun clearFolderPrefs(backing: MusicLibraryBacking) {
        LibraryScanSettings.clearPendingLibraryFolder(backing.context)
        LibraryScanSettings.clearLibraryFolder(backing.context)
        backing.pendingLibraryFolderUri = null
        backing.pendingLibraryFolderLabel = null
        backing.libraryFolderUri = null
        backing.libraryFolderLabel = null
    }

    private fun activateDeviceSource(backing: MusicLibraryBacking) {
        backing.restorePersistedState(
            PersistedLibraryState(
                intent = LibraryIntentState.ACTIVE,
                access = LibraryAccessState.AVAILABLE,
                sourceState = LibrarySourceState(
                    active = SourceActivation(SourceIdentityKey.device(), activationEpoch = 1L),
                ),
                configFingerprint = backing.configFingerprint,
            ),
        )
    }

    private fun TestScope.scanHarness(
        scanner: ControlledScanner,
        store: LibraryStore = FakeLibraryStore(),
        environment: FakeScanEnvironment = FakeScanEnvironment(),
        deviceAutoSyncShadow: DeviceAutoSyncShadow = NoopDeviceAutoSyncShadow,
        deviceShadowProbeRuntime: DeviceShadowProbeRuntime = NoopDeviceShadowProbeRuntime,
        deviceRetryObservationRuntime: DeviceRetryObservationRuntime =
            NoopDeviceRetryObservationRuntime,
        safShadowProbeRuntime: SafShadowProbeRuntime = NoopSafShadowProbeRuntime,
        autoSyncEnabled: (ScanSource) -> Boolean = { true },
        syncSchedulerTiming: LibrarySyncSchedulerTiming = LibrarySyncSchedulerTiming(),
    ): OrchestratorHarness {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val backing = MusicLibraryBacking(
            context = ApplicationProvider.getApplicationContext(),
            libraryScanner = scanner,
            libraryStore = store,
            scanEnvironment = environment,
            mainDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            deviceAutoSyncShadow = deviceAutoSyncShadow,
            deviceShadowProbeRuntime = deviceShadowProbeRuntime,
            deviceRetryObservationRuntime = deviceRetryObservationRuntime,
            safShadowProbeRuntime = safShadowProbeRuntime,
            autoSyncEnabled = autoSyncEnabled,
            syncSchedulerTiming = syncSchedulerTiming,
            syncSchedulerNowMs = { testScheduler.currentTime },
        )
        return OrchestratorHarness(backing, backing.operationExecutor)
    }

    private fun deviceGenerationSnapshot(
        generation: Long,
    ): DeviceGenerationSnapshot.Available = DeviceGenerationSnapshot.Available(
        mapOf(
            "external_primary" to DeviceVolumeGeneration(
                volumeName = "external_primary",
                providerVersion = "v1",
                generation = generation,
            ),
        ),
    )

    private fun completePresenceInventory(): PresenceInventory = PresenceInventory.fromEntries(
        entries = emptyList(),
        discoveryReport = com.mica.music.data.scanner.DiscoveryReport.of(
            com.mica.music.data.scanner.DiscoveryPartitionStatus(
                partitionKey = com.mica.music.data.scanner.DiscoveryPartitions.MEDIASTORE_AUDIO,
                completeness = DiscoveryCompleteness.COMPLETE,
            ),
            com.mica.music.data.scanner.DiscoveryPartitionStatus(
                partitionKey = com.mica.music.data.scanner.DiscoveryPartitions.MEDIASTORE_FILES_FALLBACK,
                completeness = DiscoveryCompleteness.COMPLETE,
            ),
        ),
    )

    private fun completePresenceInventoryFor(vararg songs: Song): PresenceInventory =
        PresenceInventory.fromEntries(
            entries = songs.map { song ->
                com.mica.music.data.scanner.PresenceEntry(
                    stableObjectKey = song.id,
                    partitionKey =
                        com.mica.music.data.scanner.DiscoveryPartitions.MEDIASTORE_AUDIO,
                    eligibility = LibraryEligibility.ELIGIBLE,
                    evidenceRevision = "present:${song.id}",
                )
            },
            discoveryReport = com.mica.music.data.scanner.DiscoveryReport.of(
                com.mica.music.data.scanner.DiscoveryPartitionStatus(
                    partitionKey =
                        com.mica.music.data.scanner.DiscoveryPartitions.MEDIASTORE_AUDIO,
                    completeness = DiscoveryCompleteness.COMPLETE,
                ),
                com.mica.music.data.scanner.DiscoveryPartitionStatus(
                    partitionKey =
                        com.mica.music.data.scanner.DiscoveryPartitions.MEDIASTORE_FILES_FALLBACK,
                    completeness = DiscoveryCompleteness.COMPLETE,
                ),
            ),
        )

    private fun completeLyricsInventory(): MediaStoreLyricsSidecarInventoryResult =
        MediaStoreLyricsSidecarInventoryResult(
            refsByLyricsKey = emptyMap(),
            status = com.mica.music.data.scanner.DiscoveryPartitionStatus(
                partitionKey = com.mica.music.data.scanner.DiscoveryPartitions.MEDIASTORE_LYRICS_SIDECARS,
                completeness = DiscoveryCompleteness.COMPLETE,
            ),
        )
    private fun emptyDeviceDeltaBatch(
        fromGenerationExclusive: Long,
        toGenerationInclusive: Long,
    ): DeviceMediaStoreDeltaBatch = DeviceMediaStoreDeltaBatch(
        windows = listOf(
            DeviceDeltaWindow(
                volumeName = "external_primary",
                providerVersion = "v1",
                fromGenerationExclusive = fromGenerationExclusive,
                toGenerationInclusive = toGenerationInclusive,
            ),
        ),
        rows = emptyList(),
        statuses = DeviceDeltaChannel.entries.map { channel ->
            DeviceDeltaChannelStatus(
                volumeName = "external_primary",
                channel = channel,
                completeness = DiscoveryCompleteness.COMPLETE,
            )
        },
        identityConflicts = emptyList(),
    )

    private class ControlledDeviceAutoSyncShadow(
        private val observation: DeviceAutoSyncShadowObservation,
        private val subsequentObservations: ArrayDeque<DeviceAutoSyncShadowObservation> = ArrayDeque(),
        private val fullScanAnchor: DeviceFullScanShadowAnchor = DeviceFullScanShadowAnchor.Disabled,
    ) : DeviceAutoSyncShadow {
        var onObserve: (() -> Unit)? = null
        var observeCalls: Int = 0
        val accepted = mutableListOf<Pair<DeviceAutoSyncShadowObservation, String>>()
        val acceptedFullAnchors = mutableListOf<Pair<DeviceFullScanShadowAnchor, String>>()
        val restoredAnchors = mutableListOf<Pair<DeviceGenerationSnapshot.Available, String>>()

        override fun captureFullScanAnchor(configKey: String): DeviceFullScanShadowAnchor =
            fullScanAnchor

        override fun acceptFullScanAnchor(
            anchor: DeviceFullScanShadowAnchor,
            configKey: String,
        ) {
            acceptedFullAnchors += anchor to configKey
        }

        override fun restorePersistedAnchor(
            snapshot: DeviceGenerationSnapshot.Available,
            configKey: String,
        ): Boolean {
            restoredAnchors += snapshot to configKey
            return true
        }

        override fun observe(
            configKey: String,
            scope: DeviceAutoSyncObservationScope,
        ): DeviceAutoSyncShadowObservation {
            observeCalls += 1
            onObserve?.invoke()
            return if (observeCalls == 1) {
                observation
            } else {
                subsequentObservations.removeFirstOrNull() ?: observation
            }
        }

        override fun accept(
            observation: DeviceAutoSyncShadowObservation,
            configKey: String,
        ) {
            accepted += observation to configKey
        }
    }
    private data class OrchestratorHarness(
        val backing: MusicLibraryBacking,
        val orchestrator: LibraryOperationExecutor,
    )

    private data class ScanRequest(
        val cachedSongs: List<Song>,
        val forceRefreshLyrics: Boolean,
        val forceRefreshArtwork: Boolean,
        val forceRefreshSongIds: Set<String> = emptySet(),
        val onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)?,
        val result: CompletableDeferred<ScanResult> = CompletableDeferred(),
    )

    private class ControlledScanner : LibraryScanner {
        val deviceRequests = mutableListOf<ScanRequest>()
        val folderRequests = mutableListOf<ScanRequest>()
        val folderTargetedRequests = mutableListOf<ScanRequest>()
        val folderArtworkRequests = mutableListOf<ScanRequest>()
        val folderMetadataRequests = mutableListOf<Uri>()
        val folderMissingVerificationRequests = mutableListOf<List<String>>()
        val folderMissingVerificationCursors = mutableListOf<Int>()
        var folderMissingVerificationResult: SafIndependentMissingVerificationResult? = null
        var onObserveFolderMetadata: (() -> Unit)? = null
        var folderMetadataSnapshot = SafTreeMetadataSnapshot(
            entries = emptyList(),
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    completeness = DiscoveryCompleteness.UNAVAILABLE,
                    detail = "controlled-default",
                ),
            ),
        )

        override suspend fun observeFolderMetadata(treeUri: Uri): SafTreeMetadataSnapshot {
            folderMetadataRequests += treeUri
            onObserveFolderMetadata?.invoke()
            return folderMetadataSnapshot
        }

        override suspend fun verifyFolderObjectsMissing(
            treeUri: Uri,
            songs: Collection<Song>,
            startCursor: Int,
            budget: SafMissingVerificationBudget,
        ): SafIndependentMissingVerificationResult {
            val ids = songs.map(Song::id)
            folderMissingVerificationRequests += ids
            folderMissingVerificationCursors += startCursor
            return folderMissingVerificationResult ?: SafIndependentMissingVerificationResult(
                verifiedMissingStableObjectKeys = emptySet(),
                presentStableObjectKeys = emptySet(),
                indeterminateStableObjectKeys = ids.drop(startCursor).take(1).toSet(),
                nextCursor = (startCursor + 1).coerceAtMost(ids.size),
                hasMore = startCursor + 1 < ids.size,
            )
        }

        override suspend fun scanDevice(
            cachedSongs: List<Song>,
            onProgress: (Int, Int) -> Unit,
            forceRefreshLyrics: Boolean,
            forceRefreshArtwork: Boolean,
            onLyricsBatch: (suspend (com.mica.music.data.LyricsScanBatch) -> Unit)?,
        ): ScanResult {
            onProgress(0, cachedSongs.size)
            return ScanRequest(
                cachedSongs = cachedSongs,
                forceRefreshLyrics = forceRefreshLyrics,
                forceRefreshArtwork = forceRefreshArtwork,
                onLyricsBatch = onLyricsBatch,
            ).also(deviceRequests::add).result.await()
        }

        override suspend fun scanDeviceForSongs(
            songIds: Set<String>,
            cachedSongs: List<Song>,
            onProgress: (Int, Int) -> Unit,
            forceRefreshLyrics: Boolean,
            forceRefreshArtwork: Boolean,
            onLyricsBatch: (suspend (com.mica.music.data.LyricsScanBatch) -> Unit)?,
        ): ScanResult {
            onProgress(0, cachedSongs.size)
            return ScanRequest(
                cachedSongs = cachedSongs,
                forceRefreshLyrics = forceRefreshLyrics,
                forceRefreshArtwork = forceRefreshArtwork,
                forceRefreshSongIds = songIds,
                onLyricsBatch = onLyricsBatch,
            ).also(deviceRequests::add).result.await()
        }

        override suspend fun scanFolder(
            treeUri: Uri,
            cachedSongs: List<Song>,
            onProgress: (Int, Int) -> Unit,
            forceRefreshLyrics: Boolean,
            forceRefreshArtwork: Boolean,
            onLyricsBatch: (suspend (com.mica.music.data.LyricsScanBatch) -> Unit)?,
        ): ScanResult {
            onProgress(0, cachedSongs.size)
            return ScanRequest(
                cachedSongs = cachedSongs,
                forceRefreshLyrics = forceRefreshLyrics,
                forceRefreshArtwork = forceRefreshArtwork,
                onLyricsBatch = onLyricsBatch,
            ).also(folderRequests::add).result.await()
        }

        override suspend fun scanFolderForSongs(
            treeUri: Uri,
            songIds: Set<String>,
            cachedSongs: List<Song>,
            onProgress: (Int, Int) -> Unit,
            forceRefreshLyrics: Boolean,
            forceRefreshArtwork: Boolean,
            onLyricsBatch: (suspend (LyricsScanBatch) -> Unit)?,
        ): ScanResult {
            onProgress(0, songIds.size)
            return ScanRequest(
                cachedSongs = cachedSongs,
                forceRefreshLyrics = forceRefreshLyrics,
                forceRefreshArtwork = forceRefreshArtwork,
                forceRefreshSongIds = songIds,
                onLyricsBatch = onLyricsBatch,
            ).also(folderTargetedRequests::add).result.await()
        }

        override suspend fun scanFolderArtworkForSongs(
            treeUri: Uri,
            songIds: Set<String>,
            cachedSongs: List<Song>,
            onProgress: (Int, Int) -> Unit,
        ): ScanResult {
            onProgress(0, songIds.size)
            return ScanRequest(
                cachedSongs = cachedSongs,
                forceRefreshLyrics = false,
                forceRefreshArtwork = true,
                forceRefreshSongIds = songIds,
                onLyricsBatch = null,
            ).also(folderArtworkRequests::add).result.await()
        }
    }

    private class FakeLibraryStore(
        private val cached: CachedLibrary? = null,
        private val cachedLoader: (suspend () -> CachedLibrary?)? = null,
    ) : LibraryStore {
        var syncedSongs: List<Song> = emptyList()
        val appliedLyrics = mutableListOf<ScannedSongLyrics>()
        val stagedLyrics = linkedMapOf<String, MutableList<ScannedSongLyrics>>()
        val userExclusions = mutableListOf<LibraryUserExclusion>()
        val syncCheckpoints = mutableListOf<LibrarySyncCheckpoint>()
        val retryItems = mutableListOf<LibraryRetryItem>()
        val autoSyncStateMutations = mutableListOf<LibraryAutoSyncStateMutation>()
        val autoSyncStateMutationStarted = CompletableDeferred<Unit>()
        var autoSyncStateMutationGate: CompletableDeferred<Unit>? = null
        val autoSyncSnapshotFollowups = mutableListOf<List<LibraryFollowupOutboxItem>>()
        var autoSyncSnapshotCommitCount = 0
        var afterAutoSnapshotStoreCommit: (() -> Unit)? = null
        var afterScanSnapshotStoreCommit: (() -> Unit)? = null
        var persistedState: PersistedLibraryState? = null
        val lyricsBatchStarted = CompletableDeferred<Unit>()
        var lyricsBatchGate: CompletableDeferred<Unit>? = null
        var afterStageLyrics: (() -> Unit)? = null
        val commitStarted = CompletableDeferred<Unit>()
        var commitGate: CompletableDeferred<Unit>? = null
        var clearCompleted = false
        val coverColorWrites = mutableListOf<Pair<String, Int>>()

        override suspend fun loadCached(): CachedLibrary? =
            cachedLoader?.invoke() ?: cached

        override suspend fun loadLibraryState(): PersistedLibraryState? = persistedState

        override suspend fun loadSyncCheckpoints(
            sourceIdentity: SourceIdentityKey,
        ): List<LibrarySyncCheckpoint> =
            syncCheckpoints.filter { it.sourceIdentity == sourceIdentity }

        override suspend fun loadRetryItemsPage(
            sourceIdentity: SourceIdentityKey,
            cursor: LibraryRetryCursor,
            limit: Int,
        ): LibraryRetryPage {
            val items = retryItems.asSequence()
                .filter { it.sourceIdentity == sourceIdentity }
                .filter { it.retryKey > cursor.retryKey }
                .sortedBy(LibraryRetryItem::retryKey)
                .take(limit)
                .toList()
            return LibraryRetryPage(
                items = items,
                nextCursor = items.lastOrNull()?.let { LibraryRetryCursor(it.retryKey) },
            )
        }

        override suspend fun loadDueRetryItems(
            sourceIdentity: SourceIdentityKey,
            retryKind: LibraryRetryKind,
            activationEpoch: Long,
            nowMs: Long,
            limit: Int,
        ): List<LibraryRetryItem> = retryItems.asSequence()
            .filter { it.sourceIdentity == sourceIdentity }
            .filter { it.retryKind == retryKind }
            .filter { it.activationEpoch == null || it.activationEpoch == activationEpoch }
            .filter { it.nextRetryAtMs <= nowMs }
            .sortedWith(compareBy(LibraryRetryItem::nextRetryAtMs, LibraryRetryItem::retryKey))
            .take(limit)
            .toList()

        override suspend fun loadRetryItemsForStableObjectKeys(
            sourceIdentity: SourceIdentityKey,
            stableObjectKeys: Collection<String>,
        ): List<LibraryRetryItem> {
            val keys = stableObjectKeys.toHashSet()
            return retryItems.filter {
                it.sourceIdentity == sourceIdentity && it.stableObjectKey in keys
            }
        }

        override suspend fun loadNextRetryAtMsAfter(
            sourceIdentity: SourceIdentityKey,
            activationEpoch: Long,
            afterMs: Long,
        ): Long? = retryItems.asSequence()
            .filter { it.sourceIdentity == sourceIdentity }
            .filter { it.activationEpoch == null || it.activationEpoch == activationEpoch }
            .map(LibraryRetryItem::nextRetryAtMs)
            .filter { it > afterMs }
            .minOrNull()

        override suspend fun save(
            songs: List<Song>,
            lastScanAtMs: Long,
            lastScanSource: ScanSource,
            totalSizeMb: Int,
            sortField: SongSortField?,
            sortDirection: SortDirection?,
            fastScrollSectionTargets: Map<String, Int>?,
        ): LibrarySyncResult = syncIncremental(
            songs,
            lastScanAtMs,
            lastScanSource,
            totalSizeMb,
            sortField,
            sortDirection,
            fastScrollSectionTargets,
        )

        override suspend fun syncIncremental(
            songs: List<Song>,
            lastScanAtMs: Long,
            lastScanSource: ScanSource,
            totalSizeMb: Int,
            sortField: SongSortField?,
            sortDirection: SortDirection?,
            fastScrollSectionTargets: Map<String, Int>?,
        ): LibrarySyncResult {
            syncedSongs = songs
            return LibrarySyncResult(songs.size, 0, 0, 0)
        }

        override suspend fun updateCoverColorArgb(songId: String, coverColorArgb: Int) {
            coverColorWrites += songId to coverColorArgb
        }

        override suspend fun clear() {
            syncedSongs = emptyList()
            appliedLyrics.clear()
            stagedLyrics.clear()
            clearCompleted = true
        }

        override suspend fun clearAuthority(state: PersistedLibraryState) {
            clear()
            persistedState = state
        }

        override suspend fun saveLibraryState(state: PersistedLibraryState) {
            persistedState = state
        }

        override suspend fun loadUserExclusions(
            sourceIdentity: SourceIdentityKey,
        ): List<LibraryUserExclusion> =
            userExclusions.filter { it.sourceIdentity == sourceIdentity }

        override suspend fun applyAutoSyncState(mutation: LibraryAutoSyncStateMutation) {
            autoSyncStateMutationStarted.complete(Unit)
            autoSyncStateMutationGate?.await()
            recordAutoSyncStateMutation(mutation)
        }

        override suspend fun upsertUserExclusion(exclusion: LibraryUserExclusion) {
            userExclusions.removeAll {
                it.sourceIdentity == exclusion.sourceIdentity &&
                    it.stableObjectKey == exclusion.stableObjectKey
            }
            userExclusions += exclusion
        }

        override suspend fun applyLyricsBatch(batch: List<ScannedSongLyrics>) {
            lyricsBatchStarted.complete(Unit)
            lyricsBatchGate?.await()
            appliedLyrics += batch
        }

        override suspend fun stageLyrics(scanId: String, batch: List<ScannedSongLyrics>) {
            lyricsBatchStarted.complete(Unit)
            lyricsBatchGate?.await()
            stagedLyrics.getOrPut(scanId) { mutableListOf() } += batch
            afterStageLyrics?.invoke()
        }

        override suspend fun discardStagedLyrics(scanId: String) {
            stagedLyrics.remove(scanId)
        }

        override suspend fun commitScan(
            songs: List<Song>,
            lastScanAtMs: Long,
            lastScanSource: ScanSource,
            totalSizeMb: Int,
            sortField: SongSortField?,
            sortDirection: SortDirection?,
            fastScrollSectionTargets: Map<String, Int>?,
        ): LibrarySyncResult {
            commitStarted.complete(Unit)
            commitGate?.await()
            return syncIncremental(
                songs,
                lastScanAtMs,
                lastScanSource,
                totalSizeMb,
                sortField,
                sortDirection,
                fastScrollSectionTargets,
            )
        }

        override suspend fun commitAutoSyncSnapshotAuthority(
            songs: List<Song>,
            lastScanAtMs: Long,
            lastScanSource: ScanSource,
            totalSizeMb: Int,
            state: PersistedLibraryState,
            autoSyncStateMutation: LibraryAutoSyncStateMutation,
            followupOutboxItems: List<LibraryFollowupOutboxItem>,
            stagedLyricsId: String?,
            stagedLyricsMode: LyricsStagingMode,
            stagedExternalLyricsId: String?,
            sortField: SongSortField?,
            sortDirection: SortDirection?,
            fastScrollSectionTargets: Map<String, Int>?,
        ): LibrarySyncResult {
            autoSyncSnapshotCommitCount++
            recordAutoSyncStateMutation(autoSyncStateMutation)
            autoSyncSnapshotFollowups += followupOutboxItems
            stagedLyricsId?.let { scanId ->
                stagedLyrics.remove(scanId)?.let(appliedLyrics::addAll)
            }
            stagedExternalLyricsId?.let { scanId ->
                stagedLyrics.remove(scanId)?.let(appliedLyrics::addAll)
            }
            persistedState = state
            val result = syncIncremental(
                songs,
                lastScanAtMs,
                lastScanSource,
                totalSizeMb,
                sortField,
                sortDirection,
                fastScrollSectionTargets,
            )
            afterAutoSnapshotStoreCommit?.invoke()
            return result
        }

        override suspend fun commitScanAuthority(
            songs: List<Song>,
            lastScanAtMs: Long,
            lastScanSource: ScanSource,
            totalSizeMb: Int,
            state: PersistedLibraryState,
            autoSyncStateMutation: LibraryAutoSyncStateMutation?,
            stagedLyricsId: String?,
            sortField: SongSortField?,
            sortDirection: SortDirection?,
            fastScrollSectionTargets: Map<String, Int>?,
        ): LibrarySyncResult {
            commitStarted.complete(Unit)
            commitGate?.await()
            stagedLyricsId?.let { scanId ->
                stagedLyrics.remove(scanId)?.let(appliedLyrics::addAll)
            }
            persistedState = state
            autoSyncStateMutation?.let(::recordAutoSyncStateMutation)
            val result = syncIncremental(
                songs,
                lastScanAtMs,
                lastScanSource,
                totalSizeMb,
                sortField,
                sortDirection,
                fastScrollSectionTargets,
            )
            afterScanSnapshotStoreCommit?.invoke()
            return result
        }

        private fun recordAutoSyncStateMutation(mutation: LibraryAutoSyncStateMutation) {
            autoSyncStateMutations += mutation
            if (mutation.checkpointDeleteKeys.isNotEmpty()) {
                syncCheckpoints.removeAll {
                    it.sourceIdentity == mutation.sourceIdentity &&
                        it.partitionKey in mutation.checkpointDeleteKeys
                }
            }
            mutation.checkpoints.forEach { checkpoint ->
                syncCheckpoints.removeAll {
                    it.sourceIdentity == checkpoint.sourceIdentity &&
                        it.partitionKey == checkpoint.partitionKey
                }
                syncCheckpoints += checkpoint
            }
            if (mutation.retryDeleteKeys.isNotEmpty()) {
                retryItems.removeAll {
                    it.sourceIdentity == mutation.sourceIdentity &&
                        it.retryKey in mutation.retryDeleteKeys
                }
            }
            mutation.retryUpserts.forEach { retry ->
                retryItems.removeAll {
                    it.sourceIdentity == retry.sourceIdentity &&
                        it.retryKey == retry.retryKey
                }
                retryItems += retry
            }
        }
    }

    private class FakeScanEnvironment(
        var parserVersion: Int = CURRENT_LYRICS_PARSER_VERSION,
        var retryRequired: Boolean = false,
        private val nowMsProvider: () -> Long = { 1_234L },
        private val elapsedMsProvider: () -> Long = nowMsProvider,
        var treeReadable: Boolean = true,
        var persistedTreeReadAccess: Boolean = false,
        var treeProviderAcquirable: Boolean = true,
    ) : ScanEnvironment {
        var prunedSongIds: List<String> = emptyList()
        var duringPrune: (() -> Unit)? = null
        var prefetchedVideoCoverRefs: List<com.mica.music.data.scanner.VideoCoverPosterRef> = emptyList()
        val playStatsBySongId = mutableMapOf<String, PlayStats>()
        val scriptedTreeReadability = mutableListOf<Boolean>()
        var canReadTreeCalls: Int = 0
        var providerAcquireCalls: Int = 0
        override fun hasAudioReadPermission(): Boolean = true
        override fun canReadTree(treeUri: Uri): Boolean {
            canReadTreeCalls += 1
            return if (scriptedTreeReadability.isNotEmpty()) {
                scriptedTreeReadability.removeAt(0)
            } else {
                treeReadable
            }
        }
        override fun hasPersistedTreeReadAccess(treeUri: Uri): Boolean =
            persistedTreeReadAccess
        override fun canAcquireTreeProvider(treeUri: Uri): Boolean {
            providerAcquireCalls += 1
            return treeProviderAcquirable
        }
        override fun currentTimeMillis(): Long = nowMsProvider()
        override fun elapsedRealtimeMillis(): Long = elapsedMsProvider()
        override fun playStats(songId: String): PlayStats = playStatsBySongId[songId] ?: PlayStats(0, 0)
        override fun clearTransientCache() = Unit
        override fun pruneAlbumArtCache(songs: List<Song>) {
            prunedSongIds = songs.map(Song::id)
            duringPrune?.invoke()
        }
        override fun enqueueVideoCoverPosterPrefetch(
            videoCoverRefs: Collection<com.mica.music.data.scanner.VideoCoverPosterRef>,
        ) {
            prefetchedVideoCoverRefs = videoCoverRefs.toList()
        }
        override fun persistLastScanSource(source: ScanSource) = Unit
        override fun lyricsParserVersion(): Int = parserVersion
        override fun persistLyricsParserVersion(version: Int) {
            parserVersion = version
        }
        override fun lyricsRetryRequired(): Boolean = retryRequired
        override fun persistLyricsRetryRequired(required: Boolean) {
            retryRequired = required
        }
    }
}
