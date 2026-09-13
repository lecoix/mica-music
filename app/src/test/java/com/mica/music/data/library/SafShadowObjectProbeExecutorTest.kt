package com.mica.music.data.library

import com.mica.music.data.LyricsProbeResult
import com.mica.music.data.LyricsSlots
import com.mica.music.data.Song
import com.mica.music.data.scanner.DiscoveryCompleteness
import com.mica.music.data.scanner.DiscoveryPartitionStatus
import com.mica.music.data.scanner.DiscoveryPartitions
import com.mica.music.data.scanner.DiscoveryReport
import com.mica.music.data.scanner.SafFastVerifyPlan
import com.mica.music.data.scanner.SafTargetedMetadataSnapshot
import com.mica.music.data.scanner.SafTreeMetadataEntry
import com.mica.music.data.scanner.SafTreeMetadataSnapshot
import com.mica.music.data.scanner.ScanOptions
import com.mica.music.data.scanner.ScannedSong
import com.mica.music.data.scanner.TrackDraft
import com.mica.music.testutil.SongFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SafShadowObjectProbeExecutorTest {

    private val current = SongFixtures.song("doc-1").copy(
        mediaUri = "content://provider/document/doc-1",
        fileName = "song.flac",
        folderPath = "Album",
        filePath = "Album/song.flac",
        sizeBytes = 1_234L,
        dateModifiedMs = 5_678L,
        externalLyricsSignature = "lyrics:v1",
    )

    @Test
    fun successfulProbeRequiresStablePostObservationBeforeBecomingResolved() {
        val entry = entry(current)
        var probeCalls = 0
        val execution = execute(
            entry = entry,
            audioProbe = SafShadowAudioProbeApi { _, cached ->
                probeCalls += 1
                ScannedSong(
                    song = cached!!.copy(title = "updated"),
                    lyrics = LyricsProbeResult.Complete(LyricsSlots()),
                )
            },
        )

        assertEquals(1, probeCalls)
        assertEquals(1, execution.attemptedCount)
        assertTrue(execution.issues.isEmpty())
        assertEquals("updated", execution.provisionalSongsByStableObjectKey.getValue(current.id).title)
        assertEquals(current.id, execution.provisionalLyricsByStableObjectKey.getValue(current.id).songId)

        val validation = SafShadowPostProbeValidator.validate(
            initialSnapshot = snapshot(entry),
            postSnapshot = snapshot(entry),
            execution = execution,
        )
        assertTrue(validation.issues.isEmpty())
        assertEquals("updated", validation.resolvedSongsByStableObjectKey.getValue(current.id).title)
        assertEquals(current.id, validation.resolvedLyricsByStableObjectKey.getValue(current.id).songId)
    }

    @Test
    fun playbackLeaseIsResampledImmediatelyBeforeProbe() {
        val entry = entry(current)
        var probeCalls = 0
        val execution = execute(
            entry = entry,
            playback = LibraryPlaybackIoSnapshot(
                currentStableObjectKey = current.id,
                currentMediaUri = current.mediaUri,
                hasActivePlaybackInstance = true,
            ),
            audioProbe = SafShadowAudioProbeApi { _, _ ->
                probeCalls += 1
                ScannedSong(current)
            },
        )

        assertEquals(0, probeCalls)
        assertEquals(0, execution.attemptedCount)
        assertEquals(setOf(current.id), execution.playbackDeferredKeys)
        assertTrue(execution.provisionalSongsByStableObjectKey.isEmpty())
    }

    @Test
    fun postObservationChangeDiscardsProvisionalProbeResult() {
        val entry = entry(current)
        val execution = execute(
            entry = entry,
            audioProbe = successfulProbe(),
        )
        val changedAfterProbe = entry.copy(
            sizeBytes = entry.sizeBytes + 1L,
            lastModifiedMs = entry.lastModifiedMs + 1L,
        )

        val validation = SafShadowPostProbeValidator.validate(
            initialSnapshot = snapshot(entry),
            postSnapshot = snapshot(changedAfterProbe),
            execution = execution,
        )

        assertTrue(validation.resolvedSongsByStableObjectKey.isEmpty())
        assertTrue(validation.resolvedLyricsByStableObjectKey.isEmpty())
        assertEquals(
            SafShadowProbeIssueKind.POST_OBSERVATION_CHANGED,
            validation.issues.single().kind,
        )
    }

    @Test
    fun unknownProviderFingerprintResolvesAfterStableStrongVerify() {
        val unknown = entry(current).copy(lastModifiedMs = 0L)
        var strongCalls = 0
        val execution = execute(
            entry = unknown,
            reasons = setOf(SafAutoProbeReason.UNKNOWN_FINGERPRINT_VERIFY),
            strongFingerprintApi = SafStrongResourceFingerprintApi {
                strongCalls += 1
                "sha256:stable"
            },
            audioProbe = successfulProbe(),
        )

        val validation = SafShadowPostProbeValidator.validate(
            initialSnapshot = snapshot(unknown),
            postSnapshot = snapshot(unknown),
            execution = execution,
        )

        assertEquals(2, strongCalls)
        assertTrue(validation.issues.isEmpty())
        assertEquals(
            "sha256:stable",
            execution.strongValidatedFingerprintsByStableObjectKey.getValue(current.id),
        )
        assertEquals("updated", validation.resolvedSongsByStableObjectKey.getValue(current.id).title)
    }

    @Test
    fun strongFingerprintChangeDuringUnknownProbeDiscardsResult() {
        val unknown = entry(current).copy(lastModifiedMs = 0L)
        val fingerprints = ArrayDeque(listOf("before", "after"))
        val execution = execute(
            entry = unknown,
            reasons = setOf(SafAutoProbeReason.UNKNOWN_FINGERPRINT_VERIFY),
            strongFingerprintApi = SafStrongResourceFingerprintApi {
                fingerprints.removeFirstOrNull()
            },
            audioProbe = successfulProbe(),
        )

        assertTrue(execution.provisionalSongsByStableObjectKey.isEmpty())
        assertTrue(execution.strongValidatedFingerprintsByStableObjectKey.isEmpty())
        assertEquals(
            SafShadowProbeIssueKind.POST_OBSERVATION_CHANGED,
            execution.issues.single().kind,
        )
    }

    @Test
    fun s4FrozenUnknownVerifyWallBudgetIsThirtySeconds() {
        assertEquals(30_000L, UNKNOWN_VERIFY_WALL_TIME_BUDGET_MS)
    }

    @Test
    fun unknownVerifyWallTimeBudgetDefersRemainingObjectsWithoutFailureRetry() {
        val secondSong = current.copy(
            id = "doc-2",
            mediaUri = "content://provider/document/doc-2",
            fileName = "song-2.flac",
            filePath = "Album/song-2.flac",
        )
        val first = entry(current).copy(lastModifiedMs = 0L)
        val second = entry(secondSong).copy(lastModifiedMs = 0L)
        var nowMs = 0L
        val execution = SafShadowObjectProbeExecutor.execute(
            request = SafShadowProbeRequest(
                probePlan = SafAutoProbePlan(
                    objects = listOf(first, second).map { entry ->
                        SafAutoProbeObjectPlan(
                            entry = entry,
                            reasons = setOf(SafAutoProbeReason.UNKNOWN_FINGERPRINT_VERIFY),
                            disposition = SafAutoProbeDisposition.READY,
                        )
                    },
                    heavyProbeParallelism = 1,
                    unknownFingerprintCandidateCount = 2,
                    unknownFingerprintDueCount = 2,
                    unknownFingerprintSelectedCount = 2,
                ),
                scanOptions = ScanOptions(),
                currentSongs = listOf(current, secondSong),
                playbackSnapshotProvider = { LibraryPlaybackIoSnapshot.Idle },
                unknownVerifyWallTimeBudgetMs = 50L,
                monotonicTimeMsProvider = { nowMs },
            ),
            audioProbeApi = SafShadowAudioProbeApi { _, cached ->
                nowMs += 20L
                ScannedSong(
                    song = cached!!,
                    lyrics = LyricsProbeResult.Complete(LyricsSlots()),
                )
            },
            strongFingerprintApi = SafStrongResourceFingerprintApi {
                nowMs += 20L
                "sha256:stable"
            },
        )

        assertEquals(1, execution.attemptedCount)
        assertEquals(60L, execution.unknownVerifyWallTimeMs)
        assertEquals(setOf(current.id), execution.provisionalSongsByStableObjectKey.keys)
        assertEquals(
            SafShadowProbeIssueKind.UNKNOWN_VERIFY_BUDGET_DEFERRED,
            execution.issues.single().kind,
        )
        assertEquals(secondSong.id, execution.issues.single().stableObjectKey)
    }

    @Test
    fun concreteBurstUsesConfiguredParallelProbeLanes() {
        val songs = List(4) { index ->
            current.copy(
                id = "parallel-$index",
                mediaUri = "content://provider/document/parallel-$index",
                fileName = "parallel-$index.flac",
                filePath = "Album/parallel-$index.flac",
            )
        }
        val entries = songs.map(::entry)
        val plan = SafAutoProbePlan(
            objects = entries.map { entry ->
                SafAutoProbeObjectPlan(
                    entry = entry,
                    reasons = setOf(SafAutoProbeReason.NEW_OBJECT),
                    disposition = SafAutoProbeDisposition.READY,
                )
            },
            heavyProbeParallelism = 4,
        )
        val entered = CountDownLatch(4)
        val release = CountDownLatch(1)
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val workerFailure = AtomicReference<Throwable?>(null)

        val worker = Thread {
            runCatching {
                val execution = SafShadowObjectProbeExecutor.execute(
                request = SafShadowProbeRequest(
                    probePlan = plan,
                    scanOptions = ScanOptions(),
                    currentSongs = songs,
                    playbackSnapshotProvider = { LibraryPlaybackIoSnapshot.Idle },
                ),
                audioProbeApi = SafShadowAudioProbeApi { _, cached ->
                    val now = active.incrementAndGet()
                    maxActive.updateAndGet { previous -> maxOf(previous, now) }
                    entered.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                    active.decrementAndGet()
                    ScannedSong(
                        song = cached!!,
                        lyrics = LyricsProbeResult.Complete(LyricsSlots()),
                    )
                },
            )
                assertEquals(4, execution.attemptedCount)
                assertEquals(4, execution.provisionalSongsByStableObjectKey.size)
            }.onFailure(workerFailure::set)
        }
        worker.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertTrue(maxActive.get() >= 2)
        release.countDown()
        worker.join(3_000L)
        assertTrue(!worker.isAlive)
        workerFailure.get()?.let { throw AssertionError("parallel probe worker failed", it) }
    }

    @Test
    fun tenThousandChangedObjectsInvokeOnlyOneHeavyProbeBudgetBatch() {
        val songs = List(10_000) { index ->
            current.copy(
                id = "doc-" + index.toString().padStart(5, '0'),
                mediaUri = "content://provider/document/doc-" + index.toString().padStart(5, '0'),
                fileName = "song-" + index.toString().padStart(5, '0') + ".flac",
                filePath = "Album/song-" + index.toString().padStart(5, '0') + ".flac",
            )
        }
        val entries = songs.map(::entry)
        val verifyPlan = SafFastVerifyPlan(
            added = emptyList(),
            changed = entries,
            unknownFingerprint = emptyList(),
            removedStableObjectKeys = emptySet(),
            unchangedCount = 0,
            removalSuppressedCount = 0,
            discoveryReport = DiscoveryReport.of(
                DiscoveryPartitionStatus(
                    partitionKey = DiscoveryPartitions.SAF_TREE,
                    completeness = DiscoveryCompleteness.COMPLETE,
                ),
            ),
        )
        val probePlan = SafAutoProbePlanner.plan(
            verifyPlan = verifyPlan,
            playback = LibraryPlaybackIoSnapshot.Idle,
        )
        var probeCalls = 0

        val execution = SafShadowObjectProbeExecutor.execute(
            request = SafShadowProbeRequest(
                probePlan = probePlan,
                scanOptions = ScanOptions(),
                currentSongs = songs,
                playbackSnapshotProvider = { LibraryPlaybackIoSnapshot.Idle },
            ),
            audioProbeApi = SafShadowAudioProbeApi { _, cached ->
                probeCalls += 1
                ScannedSong(
                    song = cached!!,
                    lyrics = LyricsProbeResult.Complete(LyricsSlots()),
                )
            },
        )

        assertEquals(SafAutoProbePlanner.DEFAULT_HEAVY_PROBE_BUDGET, probeCalls)
        assertEquals(SafAutoProbePlanner.DEFAULT_HEAVY_PROBE_BUDGET, execution.attemptedCount)
        assertEquals(SafAutoProbePlanner.DEFAULT_HEAVY_PROBE_BUDGET, execution.provisionalSongsByStableObjectKey.size)
        assertTrue(execution.issues.isEmpty())
        assertEquals(
            10_000 - SafAutoProbePlanner.DEFAULT_HEAVY_PROBE_BUDGET,
            probePlan.budgetDeferred.size,
        )
    }

    @Test
    fun targetedPostObservationResolvesStableObject() {
        val observed = entry(current)
        val execution = execute(
            entry = observed,
            audioProbe = successfulProbe(),
        )
        val post = SafTargetedMetadataSnapshot(
            entries = listOf(observed.copy(probeDraft = null)),
            requestedFolderPaths = setOf(current.folderPath),
            completeFolderPaths = setOf(current.folderPath),
        )

        val validation = SafShadowPostProbeValidator.validate(
            initialSnapshot = snapshot(observed),
            postSnapshot = post,
            execution = execution,
        )

        assertEquals(setOf(current.id), validation.resolvedSongsByStableObjectKey.keys)
        assertTrue(validation.issues.isEmpty())
    }

    @Test
    fun targetedPostObservationRejectsIncompleteFolder() {
        val observed = entry(current)
        val execution = execute(
            entry = observed,
            audioProbe = successfulProbe(),
        )
        val post = SafTargetedMetadataSnapshot(
            entries = listOf(observed.copy(probeDraft = null)),
            requestedFolderPaths = setOf(current.folderPath),
            completeFolderPaths = emptySet(),
            failedFolderPaths = setOf(current.folderPath),
        )

        val validation = SafShadowPostProbeValidator.validate(
            initialSnapshot = snapshot(observed),
            postSnapshot = post,
            execution = execution,
        )

        assertTrue(validation.resolvedSongsByStableObjectKey.isEmpty())
        assertEquals(
            SafShadowProbeIssueKind.POST_OBSERVATION_CHANGED,
            validation.issues.single().kind,
        )
        assertEquals("post-validation-folder-incomplete", validation.issues.single().detail)
    }

    @Test
    fun targetedPostObservationRejectsExternalLyricsRevisionChange() {
        val observed = entry(current)
        val execution = execute(
            entry = observed,
            audioProbe = successfulProbe(),
        )
        val post = SafTargetedMetadataSnapshot(
            entries = listOf(
                observed.copy(
                    externalLyricsSignature = "lyrics:v2",
                    probeDraft = null,
                ),
            ),
            requestedFolderPaths = setOf(current.folderPath),
            completeFolderPaths = setOf(current.folderPath),
        )

        val validation = SafShadowPostProbeValidator.validate(
            initialSnapshot = snapshot(observed),
            postSnapshot = post,
            execution = execution,
        )

        assertTrue(validation.resolvedSongsByStableObjectKey.isEmpty())
        assertEquals(
            SafShadowProbeIssueKind.POST_OBSERVATION_CHANGED,
            validation.issues.single().kind,
        )
    }

    @Test
    fun unknownProviderFingerprintStaysUnresolvedAfterProbe() {
        val unknown = entry(current).copy(lastModifiedMs = 0L)
        val execution = execute(
            entry = unknown,
            audioProbe = successfulProbe(),
        )

        val validation = SafShadowPostProbeValidator.validate(
            initialSnapshot = snapshot(unknown),
            postSnapshot = snapshot(unknown),
            execution = execution,
        )

        assertTrue(validation.resolvedSongsByStableObjectKey.isEmpty())
        assertEquals(
            SafShadowProbeIssueKind.UNVERIFIABLE_FINGERPRINT,
            validation.issues.single().kind,
        )
    }

    private fun execute(
        entry: SafTreeMetadataEntry,
        playback: LibraryPlaybackIoSnapshot = LibraryPlaybackIoSnapshot.Idle,
        reasons: Set<SafAutoProbeReason> =
            setOf(SafAutoProbeReason.METADATA_OR_FINGERPRINT_CHANGED),
        strongFingerprintApi: SafStrongResourceFingerprintApi =
            NoopSafStrongResourceFingerprintApi,
        audioProbe: SafShadowAudioProbeApi,
    ): SafShadowProbeExecutionResult = SafShadowObjectProbeExecutor.execute(
        request = SafShadowProbeRequest(
            probePlan = SafAutoProbePlan(
                objects = listOf(
                    SafAutoProbeObjectPlan(
                        entry = entry,
                        reasons = reasons,
                        disposition = SafAutoProbeDisposition.READY,
                    ),
                ),
                heavyProbeParallelism = 1,
            ),
            scanOptions = ScanOptions(),
            currentSongs = listOf(current),
            playbackSnapshotProvider = { playback },
        ),
        audioProbeApi = audioProbe,
        strongFingerprintApi = strongFingerprintApi,
    )

    private fun successfulProbe() = SafShadowAudioProbeApi { _, cached ->
        ScannedSong(
            song = cached!!.copy(title = "updated"),
            lyrics = LyricsProbeResult.Complete(LyricsSlots()),
        )
    }

    private fun entry(song: Song): SafTreeMetadataEntry {
        val draft = TrackDraft(
            mediaStoreId = 0L,
            title = song.title,
            artist = song.artist,
            album = song.album,
            albumId = 0L,
            durationSec = song.durationSec,
            mimeType = song.metadata.playbackMimeType,
            displayName = song.fileName,
            sizeBytes = song.sizeBytes,
            bitrateBpsFromStore = song.metadata.bitrateKbps * 1000,
            mediaUri = song.mediaUri,
            coverColorArgb = song.coverColorArgb,
            year = song.year,
            folderPath = song.folderPath,
            filePath = song.filePath,
            externalLyricsSignature = song.externalLyricsSignature,
            dateAddedMs = song.dateAddedMs,
            dateModifiedMs = song.dateModifiedMs,
        )
        return SafTreeMetadataEntry(
            stableObjectKey = song.id,
            mediaUri = song.mediaUri,
            fileName = song.fileName,
            folderPath = song.folderPath,
            filePath = song.filePath,
            mimeType = song.metadata.playbackMimeType,
            sizeBytes = song.sizeBytes,
            lastModifiedMs = song.dateModifiedMs,
            externalLyricsSignature = song.externalLyricsSignature,
            probeDraft = draft,
        )
    }

    private fun snapshot(entry: SafTreeMetadataEntry) = SafTreeMetadataSnapshot(
        entries = listOf(entry),
        discoveryReport = DiscoveryReport.of(
            DiscoveryPartitionStatus(
                partitionKey = DiscoveryPartitions.SAF_TREE,
                completeness = DiscoveryCompleteness.COMPLETE,
            ),
        ),
    )
}
