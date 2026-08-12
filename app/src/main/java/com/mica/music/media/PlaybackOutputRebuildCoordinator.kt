package com.mica.music.media

import java.util.concurrent.atomic.AtomicLong

/**
 * Two-phase owner for a full playback-stack rebuild.
 *
 * A request first invalidates the previous generation, captures playback intent, and builds a
 * candidate without touching the published stack. Publication is serialized as an inert candidate
 * stage, complete retirement of the current output, then candidate activation. This break-before-
 * make order prevents two audio renderers from overlapping. A stale candidate is only released; it
 * can never replace the current player.
 */
internal class PlaybackOutputRebuildCoordinator<Target, Snapshot, Candidate>(
    private val onGenerationPublished: (Long) -> Unit = {},
    private val capture: () -> Snapshot,
    private val buildCandidate: (Target, Snapshot) -> Candidate,
    private val stageCandidate: (Target, Snapshot, Candidate) -> Unit = { _, _, _ -> },
    private val retirePublished: (Target, Snapshot) -> Unit = { _, _ -> },
    private val publishCandidate: (Target, Snapshot, Candidate) -> Unit,
    private val releaseCandidate: (Candidate, String) -> Unit,
) {
    private val generation = AtomicLong()
    private val publicationLock = Any()

    fun rebuild(
        target: Target,
        transformSnapshot: (Snapshot) -> Snapshot = { it },
    ): PlaybackOutputRebuildResult {
        val requestGeneration = synchronized(publicationLock) {
            generation.incrementAndGet().also(onGenerationPublished)
        }
        val snapshot = transformSnapshot(capture())
        if (generation.get() != requestGeneration) {
            return PlaybackOutputRebuildResult.Superseded(requestGeneration)
        }
        val candidate = try {
            buildCandidate(target, snapshot)
        } catch (error: Throwable) {
            return PlaybackOutputRebuildResult.Failed(requestGeneration, error)
        }

        synchronized(publicationLock) {
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
                retirePublished(target, snapshot)
                if (generation.get() != requestGeneration) {
                    releaseCandidate(candidate, "superseded-after-retirement")
                    return PlaybackOutputRebuildResult.Superseded(requestGeneration)
                }
                publishCandidate(target, snapshot, candidate)
                PlaybackOutputRebuildResult.Published(requestGeneration)
            } catch (error: Throwable) {
                releaseCandidate(candidate, "publication-failed")
                PlaybackOutputRebuildResult.Failed(requestGeneration, error)
            }
        }
    }
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
