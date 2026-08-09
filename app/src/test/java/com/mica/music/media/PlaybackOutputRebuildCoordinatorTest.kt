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
    fun `new generation cannot split an in-progress publication side effect`() {
        val publicationStarted = CountDownLatch(1)
        val finishPublication = CountDownLatch(1)
        val secondGenerationPublished = CountDownLatch(1)
        val effects = mutableListOf<String>()
        val coordinator = PlaybackOutputRebuildCoordinator<String, IntentSnapshot, String>(
            onGenerationPublished = {
                if (it == 2L) secondGenerationPublished.countDown()
            },
            capture = { IntentSnapshot("song", 1L, playWhenReady = true) },
            buildCandidate = { target, _ -> target },
            publishCandidate = { target, _, _ ->
                effects += "$target-before"
                if (target == "first") {
                    publicationStarted.countDown()
                    assertTrue(finishPublication.await(5, TimeUnit.SECONDS))
                }
                effects += "$target-after"
            },
            releaseCandidate = { _, _ -> Unit },
        )

        val first = thread { coordinator.rebuild("first") }
        assertTrue(publicationStarted.await(5, TimeUnit.SECONDS))
        val second = thread { coordinator.rebuild("second") }
        assertTrue(!secondGenerationPublished.await(100, TimeUnit.MILLISECONDS))

        finishPublication.countDown()
        first.join(5_000L)
        second.join(5_000L)

        assertTrue(secondGenerationPublished.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("first-before", "first-after", "second-before", "second-after"), effects)
    }

    private data class IntentSnapshot(
        val songId: String,
        val positionMs: Long,
        val playWhenReady: Boolean,
    )
}
