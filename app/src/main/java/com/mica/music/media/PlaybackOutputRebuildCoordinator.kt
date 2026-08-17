package com.mica.music.media

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Two-phase owner for a full playback-stack rebuild.
 *
 * A request first invalidates the previous generation, captures playback intent, and builds a
 * candidate without touching the published stack. Publication is serialized as an inert candidate
 * stage, complete retirement of the current output, then candidate activation. This break-before-
 * make order prevents two audio renderers from overlapping. A stale candidate is only released; it
 * can never replace the current player.
 *
 * Generation mint is a short critical section and never shares a lock with staging, old-stack
 * retirement, framework release, or candidate activation. Candidate publish is compare/current-epoch
 * guarded. `ExoPlayer.release()` returning is teardown work, not terminal proof.
 */
internal class PlaybackOutputRebuildCoordinator<Target, Snapshot, Candidate>(
    private val onGenerationPublished: (Long) -> Unit = {},
    private val capture: () -> Snapshot,
    private val buildCandidate: (Target, Snapshot) -> Candidate,
    private val stageCandidate: (Target, Snapshot, Candidate) -> Unit = { _, _, _ -> },
    private val retirePublished: (Target, Snapshot) -> Unit = { _, _ -> },
    private val awaitOldStackBarrier: (Target, Snapshot) -> OldStackBarrierDisposition =
        { _, _ -> OldStackBarrierDisposition.TerminalProof },
    private val retirementTimeoutMs: Long = Long.MAX_VALUE,
    private val publishCandidate: (Target, Snapshot, Candidate) -> Unit,
    private val releaseCandidate: (Candidate, String) -> Unit,
) {
    private val generation = AtomicLong()
    private val publishClaimLock = Any()

    fun rebuild(
        target: Target,
        transformSnapshot: (Snapshot) -> Snapshot = { it },
    ): PlaybackOutputRebuildResult {
        val requestGeneration = generation.incrementAndGet()
        onGenerationPublished(requestGeneration)
        val snapshot = transformSnapshot(capture())
        if (generation.get() != requestGeneration) {
            return PlaybackOutputRebuildResult.Superseded(requestGeneration)
        }
        val candidate = try {
            buildCandidate(target, snapshot)
        } catch (error: Throwable) {
            return PlaybackOutputRebuildResult.Failed(requestGeneration, error)
        }

        if (generation.get() != requestGeneration) {
            releaseCandidate(candidate, "superseded-before-publication")
            return PlaybackOutputRebuildResult.Superseded(requestGeneration)
        }
        return try {
            stageCandidate(target, snapshot, candidate)
            if (generation.get() != requestGeneration) {
                releaseCandidate(candidate, "superseded-after-staging")
                return PlaybackOutputRebuildResult.Superseded(requestGeneration)
            }
            if (!runBoundedRetirement(target, snapshot)) {
                releaseCandidate(candidate, "retirement-timed-out")
                return PlaybackOutputRebuildResult.Failed(
                    requestGeneration,
                    IllegalStateException("old-stack retirement timed out"),
                )
            }
            when (awaitOldStackBarrier(target, snapshot)) {
                OldStackBarrierDisposition.TerminalProof -> Unit
                OldStackBarrierDisposition.TimedOut -> {
                    releaseCandidate(candidate, "retirement-barrier-timed-out")
                    return PlaybackOutputRebuildResult.Failed(
                        requestGeneration,
                        IllegalStateException("old-stack barrier timed out"),
                    )
                }
                OldStackBarrierDisposition.FailedWithoutProof -> {
                    releaseCandidate(candidate, "retirement-barrier-failed")
                    return PlaybackOutputRebuildResult.Failed(
                        requestGeneration,
                        IllegalStateException("old-stack barrier lacked terminal proof"),
                    )
                }
            }
            synchronized(publishClaimLock) {
                if (generation.get() != requestGeneration) {
                    releaseCandidate(candidate, "superseded-after-retirement")
                    return PlaybackOutputRebuildResult.Superseded(requestGeneration)
                }
                publishCandidate(target, snapshot, candidate)
                PlaybackOutputRebuildResult.Published(requestGeneration)
            }
        } catch (error: Throwable) {
            releaseCandidate(candidate, "publication-failed")
            PlaybackOutputRebuildResult.Failed(requestGeneration, error)
        }
    }

    private fun runBoundedRetirement(target: Target, snapshot: Snapshot): Boolean {
        if (retirementTimeoutMs == Long.MAX_VALUE) {
            retirePublished(target, snapshot)
            return true
        }
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        thread(name = "mica-old-stack-retirement", isDaemon = true) {
            try {
                retirePublished(target, snapshot)
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                done.countDown()
            }
        }
        if (!done.await(retirementTimeoutMs, TimeUnit.MILLISECONDS)) {
            return false
        }
        failure.get()?.let { throw it }
        return true
    }
}

internal enum class OldStackBarrierDisposition {
    TerminalProof,
    TimedOut,
    FailedWithoutProof,
}

internal sealed interface PlaybackOutputRebuildResult {
    val generation: Long

    data class Published(override val generation: Long) : PlaybackOutputRebuildResult
    data class Superseded(override val generation: Long) : PlaybackOutputRebuildResult
    data class Failed(
        override val generation: Long,
        val error: Throwable,
    ) : PlaybackOutputRebuildResult
}
