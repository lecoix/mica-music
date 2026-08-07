package com.mica.music.data

import kotlin.math.abs

internal class PlaybackTuningCoordinator {
    var requested: PlaybackTuning = PlaybackTuning()
        private set

    private var pendingRequested: PlaybackTuning? = null
    private var effective = PlaybackTuning()
    private var pendingEffective: PlaybackTuning? = null

    fun request(tuning: PlaybackTuning) {
        requested = tuning
        pendingRequested = tuning
    }

    fun onConnected(
        reported: PlaybackTuning,
        tuningAvailable: Boolean,
    ): PlaybackTuning? {
        val pending = pendingRequested
        requested = pending ?: reported
        val target = effectiveFor(requested, tuningAvailable)
        effective = target
        return if (pending != null || !equal(target, reported)) {
            pendingEffective = target
            target
        } else {
            pendingRequested = null
            pendingEffective = null
            null
        }
    }

    fun onPlaybackParametersChanged(
        reported: PlaybackTuning,
        tuningAvailable: Boolean,
    ) {
        effective = reported
        val pending = pendingEffective
        if (pending != null && equal(reported, pending)) {
            pendingEffective = null
            pendingRequested = null
            return
        }
        if (tuningAvailable) {
            requested = reported
            pendingRequested = null
        }
    }

    fun targetForSync(
        tuningAvailable: Boolean,
        force: Boolean,
    ): PlaybackTuning? {
        val target = effectiveFor(requested, tuningAvailable)
        val changed = !equal(target, effective)
        effective = target
        return target.takeIf { force || changed }
    }

    fun markApplyIssued(target: PlaybackTuning) {
        pendingEffective = target
    }

    fun matchesRequested(tuning: PlaybackTuning): Boolean = equal(tuning, requested)

    private fun effectiveFor(
        requested: PlaybackTuning,
        tuningAvailable: Boolean,
    ): PlaybackTuning = if (tuningAvailable) requested else PlaybackTuning()

    private fun equal(left: PlaybackTuning, right: PlaybackTuning): Boolean =
        abs(left.speed - right.speed) < EPSILON &&
            abs(left.pitchSemitones - right.pitchSemitones) < EPSILON

    private companion object {
        const val EPSILON = 0.001f
    }
}
