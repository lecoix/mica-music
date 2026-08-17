package com.mica.music.media

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackOutputRebuildCoordinatorTest {
    @Test
    fun `stale candidate cannot publish over newer stack and is released`() {
        val oldBuildPaused = CountDownLatch(1)
        val releaseOldBuild = CountDownLatch(1)
        val published = mutableListOf<String>()
        val released = mutableListOf<String>()
        val coordinator = PlaybackOutputRebuildCoordinator<String, IntentSnapshot, String>(
            capture = { IntentSnapshot("song-7", 42_000L, playWhenReady = true) },
            buildCandidate = { target, _ ->
                if (target == "old") {
                    oldBuildPaused.countDown()
                    assertTrue(releaseOldBuild.await(5, TimeUnit.SECONDS))
                }
                "candidate-$target"
            },
            publishCandidate = { _, snapshot, candidate ->
                published += "$candidate:${snapshot.songId}:${snapshot.positionMs}:${snapshot.playWhenReady}"
            },
            releaseCandidate = { candidate, reason -> released += "$candidate:$reason" },
        )

        lateinit var oldResult: PlaybackOutputRebuildResult
        val oldThread = thread {
            oldResult = coordinator.rebuild("old")
        }
        assertTrue(oldBuildPaused.await(5, TimeUnit.SECONDS))

        val newResult = coordinator.rebuild("new")
        releaseOldBuild.countDown()
        oldThread.join(5_000L)

        assertEquals(PlaybackOutputRebuildResult.Published(2L), newResult)
        assertEquals(PlaybackOutputRebuildResult.Superseded(1L), oldResult)
        assertEquals(listOf("candidate-new:song-7:42000:true"), published)
        assertEquals(listOf("candidate-old:superseded-before-publication"), released)
    }

    @Test
    fun `candidate build failure leaves published stack untouched`() {
        var publishCount = 0
        var releaseCount = 0
        val coordinator = PlaybackOutputRebuildCoordinator<String, IntentSnapshot, String>(
            capture = { IntentSnapshot("song-1", 10L, playWhenReady = false) },
            buildCandidate = { _, _ -> error("build failed") },
            publishCandidate = { _, _, _ -> publishCount++ },
            releaseCandidate = { _, _ -> releaseCount++ },
        )

        val result = coordinator.rebuild("usb")

        assertTrue(result is PlaybackOutputRebuildResult.Failed)
        assertEquals(0, publishCount)
        assertEquals(0, releaseCount)
    }

    @Test
    fun `publication receives exact captured playback intent`() {
        val expected = IntentSnapshot("song-3", 91_234L, playWhenReady = false)
        var restored: IntentSnapshot? = null
        val coordinator = PlaybackOutputRebuildCoordinator<String, IntentSnapshot, String>(
            capture = { expected },
            buildCandidate = { _, _ -> "candidate" },
            publishCandidate = { _, snapshot, _ -> restored = snapshot },
            releaseCandidate = { _, _ -> error("candidate should remain published") },
        )

        val result = coordinator.rebuild("shared")

        assertEquals(PlaybackOutputRebuildResult.Published(1L), result)
        assertEquals(expected, restored)
    }

    @Test
    fun `candidate cannot activate until current output release completes`() {
        val releaseStarted = CountDownLatch(1)
        val finishRelease = CountDownLatch(1)
        val effects = mutableListOf<String>()
        val coordinator = PlaybackOutputRebuildCoordinator<String, IntentSnapshot, String>(
            capture = { IntentSnapshot("song-3", 91_234L, playWhenReady = true) },
            buildCandidate = { target, _ -> "candidate-$target" },
            stageCandidate = { _, _, candidate -> effects += "stage-$candidate" },
            retirePublished = { target, _ ->
                effects += "retire-$target-start"
                releaseStarted.countDown()
                assertTrue(finishRelease.await(5, TimeUnit.SECONDS))
                effects += "retire-$target-complete"
            },
            publishCandidate = { _, _, candidate -> effects += "activate-$candidate" },
            releaseCandidate = { candidate, reason -> effects += "release-$candidate:$reason" },
        )

        val rebuild = thread { coordinator.rebuild("shared") }
        assertTrue(releaseStarted.await(5, TimeUnit.SECONDS))
        assertEquals(
            listOf("stage-candidate-shared", "retire-shared-start"),
            effects,
        )

        finishRelease.countDown()
        rebuild.join(5_000L)

        assertEquals(
            listOf(
                "stage-candidate-shared",
                "retire-shared-start",
                "retire-shared-complete",
                "activate-candidate-shared",
            ),
            effects,
        )
    }

    @Test
    fun `snapshot transform supplies interrupted playback intent to publication`() {
        var published: IntentSnapshot? = null
        val coordinator = PlaybackOutputRebuildCoordinator<String, IntentSnapshot, String>(
            capture = { IntentSnapshot("song-3", 91_234L, playWhenReady = false) },
            buildCandidate = { _, _ -> "candidate" },
            publishCandidate = { _, snapshot, _ -> published = snapshot },
            releaseCandidate = { _, _ -> },
        )

        val result = coordinator.rebuild("usb") { snapshot ->
            snapshot.copy(playWhenReady = true)
        }

        assertEquals(PlaybackOutputRebuildResult.Published(1L), result)
        assertEquals(true, published?.playWhenReady)
    }

    @Test
    fun newerRebuildMintsGenerationWhileOldRetirementBlocksAndOldCannotPublish() {
        val retirementStarted = CountDownLatch(1)
        val finishRetirement = CountDownLatch(1)
        val firstRetireCompleted = CountDownLatch(1)
        val secondGenerationPublished = CountDownLatch(1)
        val published = mutableListOf<String>()
        val released = mutableListOf<String>()
        val coordinator = PlaybackOutputRebuildCoordinator<String, IntentSnapshot, String>(
            onGenerationPublished = {
                if (it == 2L) secondGenerationPublished.countDown()
            },
            capture = { IntentSnapshot("song", 1L, playWhenReady = true) },
            buildCandidate = { target, _ -> target },
            retirePublished = { target, _ ->
                if (target == "first") {
                    retirementStarted.countDown()
                    assertTrue(finishRetirement.await(5, TimeUnit.SECONDS))
                    firstRetireCompleted.countDown()
                }
            },
            awaitOldStackBarrier = { _, _ ->
                assertTrue(firstRetireCompleted.await(5, TimeUnit.SECONDS))
                OldStackBarrierDisposition.TerminalProof
            },
            publishCandidate = { target, _, _ -> published += target },
            releaseCandidate = { candidate, reason -> released += "$candidate:$reason" },
        )

        lateinit var firstResult: PlaybackOutputRebuildResult
        lateinit var secondResult: PlaybackOutputRebuildResult
        val first = thread { firstResult = coordinator.rebuild("first") }
        assertTrue(retirementStarted.await(5, TimeUnit.SECONDS))
        val second = thread { secondResult = coordinator.rebuild("second") }
        assertTrue(secondGenerationPublished.await(5, TimeUnit.SECONDS))

        finishRetirement.countDown()
        first.join(5_000L)
        second.join(5_000L)

        assertEquals(PlaybackOutputRebuildResult.Superseded(1L), firstResult)
        assertEquals(PlaybackOutputRebuildResult.Published(2L), secondResult)
        assertEquals(listOf("second"), published)
        assertEquals(listOf("first:superseded-after-retirement"), released)
    }

    @Test
    fun releaseReturnWithoutTerminalProofDoesNotPublishCandidate() {
        val published = mutableListOf<String>()
        val released = mutableListOf<String>()
        val coordinator = PlaybackOutputRebuildCoordinator<String, IntentSnapshot, String>(
            capture = { IntentSnapshot("song", 1L, playWhenReady = true) },
            buildCandidate = { _, _ -> "candidate" },
            retirePublished = { _, _ -> },
            awaitOldStackBarrier = { _, _ -> OldStackBarrierDisposition.FailedWithoutProof },
            publishCandidate = { _, _, candidate -> published += candidate },
            releaseCandidate = { candidate, reason -> released += "$candidate:$reason" },
        )

        val result = coordinator.rebuild("shared")

        assertTrue(result is PlaybackOutputRebuildResult.Failed)
        assertEquals(emptyList<String>(), published)
        assertEquals(listOf("candidate:retirement-barrier-failed"), released)
    }

    @Test
    fun retirementHangTimesOutFailClosedWithoutPublishing() {
        val hang = CountDownLatch(1)
        val published = mutableListOf<String>()
        val released = mutableListOf<String>()
        val coordinator = PlaybackOutputRebuildCoordinator<String, IntentSnapshot, String>(
            capture = { IntentSnapshot("song", 1L, playWhenReady = true) },
            buildCandidate = { _, _ -> "candidate" },
            retirePublished = { _, _ ->
                hang.await(5, TimeUnit.SECONDS)
            },
            retirementTimeoutMs = 50L,
            publishCandidate = { _, _, candidate -> published += candidate },
            releaseCandidate = { candidate, reason -> released += "$candidate:$reason" },
        )

        val result = coordinator.rebuild("shared")
        hang.countDown()

        assertTrue(result is PlaybackOutputRebuildResult.Failed)
        assertEquals(emptyList<String>(), published)
        assertEquals(listOf("candidate:retirement-timed-out"), released)
    }

    @Test
    fun refusedBeginRetiringStopsCutoverBeforePublish() {
        val published = mutableListOf<String>()
        val released = mutableListOf<String>()
        val coordinator = PlaybackOutputRebuildCoordinator<String, IntentSnapshot, String>(
            capture = { IntentSnapshot("song", 1L, playWhenReady = true) },
            buildCandidate = { _, _ -> "candidate" },
            retirePublished = { _, _ -> error("playback stack retirement refused") },
            publishCandidate = { _, _, candidate -> published += candidate },
            releaseCandidate = { candidate, reason -> released += "$candidate:$reason" },
        )

        val result = coordinator.rebuild("shared")

        assertTrue(result is PlaybackOutputRebuildResult.Failed)
        assertEquals(emptyList<String>(), published)
        assertEquals(listOf("candidate:publication-failed"), released)
    }

    private data class IntentSnapshot(
        val songId: String,
        val positionMs: Long,
        val playWhenReady: Boolean,
    )
}
