package com.mica.music.playback

import kotlin.math.roundToLong

internal data class ClearedPendingSeek(
    val reason: String,
    val pendingMs: Int,
    val positionMs: Int,
)

internal class PlaybackTimelineCoordinator(
    private val monotonicNowMs: () -> Long,
) {
    internal companion object {
        const val PENDING_SEEK_CONVERGE_TOLERANCE_MS = 1_500
        const val PENDING_SEEK_MAX_AGE_MS = 4_000L
        const val PENDING_SEEK_DRIFT_BAILOUT_MIN_AGE_MS = 500L
        const val PENDING_SEEK_AHEAD_DRIFT_MS = 1_500
    }

    var positionMs: Int = 0
        private set

    var durationSec: Int = 0
        private set

    var pendingSeekMs: Int = -1
        private set

    var seekUiActive: Boolean = false
        private set

    var positionRevision: Long = 0L
        private set

    private var pendingSeekSetAtElapsedMs = 0L
    private var pendingRestoreSongId: String? = null
    private var pendingRestorePositionMs = 0
    private var presentationInitialized = false
    private var presentationAdvancing = false
    private var presentationAnchorPositionMs = 0L
    private var presentationAnchorElapsedMs = 0L
    private var presentationSpeed = 1f

    fun setSeekUiActive(active: Boolean) {
        seekUiActive = active
    }

    fun uiDurationMs(songDurationSec: Int): Int =
        maxOf(songDurationSec.coerceAtLeast(0), durationSec.coerceAtLeast(0)) * 1000

    fun uiPositionMs(songDurationSec: Int): Int {
        val maxMs = uiDurationMs(songDurationSec)
        val position = if (maxMs > 0) {
            positionMs.coerceIn(0, maxMs)
        } else {
            positionMs.coerceAtLeast(0)
        }
        return pendingSeekMs.takeIf { it >= 0 }?.let { pending ->
            if (maxMs > 0) pending.coerceIn(0, maxMs) else pending.coerceAtLeast(0)
        } ?: position
    }

    fun setPositionClamped(rawMs: Int, songDurationSec: Int) {
        positionMs = clampPosition(rawMs.toLong(), songDurationSec)
        presentationInitialized = false
        presentationAdvancing = false
    }

    fun samplePresentationPosition(
        rawMs: Int,
        songDurationSec: Int,
        isAdvancing: Boolean,
        playbackSpeed: Float,
    ) {
        val nowMs = monotonicNowMs()
        val candidate = clampPosition(rawMs.toLong(), songDurationSec)
        val safeSpeed = playbackSpeed.takeIf { it.isFinite() && it > 0f } ?: 1f
        if (!presentationInitialized) {
            positionMs = candidate
            resetPresentationAnchor(nowMs, candidate, isAdvancing, safeSpeed)
            return
        }

        val projected = projectedPosition(nowMs, songDurationSec)
        when {
            presentationAdvancing && !isAdvancing -> {
                positionMs = projected
                resetPresentationAnchor(nowMs, projected, false, safeSpeed)
            }
            !presentationAdvancing && isAdvancing -> {
                resetPresentationAnchor(nowMs, positionMs, true, safeSpeed)
            }
            presentationAdvancing && presentationSpeed != safeSpeed -> {
                positionMs = projected
                resetPresentationAnchor(nowMs, projected, true, safeSpeed)
            }
            presentationAdvancing -> positionMs = projected
            else -> Unit
        }
    }

    fun updatePlayerDuration(durationMs: Long) {
        if (durationMs > 0) durationSec = (durationMs / 1000).toInt()
    }

    fun resetDurationForSongChange(songDurationSec: Int): Int {
        val previousSec = durationSec
        durationSec = songDurationSec.coerceAtLeast(0)
        markPositionDiscontinuity()
        return previousSec
    }

    fun armPendingSeek(targetMs: Int) {
        markPositionDiscontinuity()
        pendingSeekMs = targetMs
        pendingSeekSetAtElapsedMs = monotonicNowMs()
    }

    fun markPositionDiscontinuity() {
        positionRevision += 1L
    }

    fun reconcilePendingSeek(reportedMs: Int): ClearedPendingSeek? {
        val pending = pendingSeekMs
        if (pending < 0) return null
        val ageMs = if (pendingSeekSetAtElapsedMs > 0L) {
            monotonicNowMs() - pendingSeekSetAtElapsedMs
        } else {
            0L
        }
        val reason = evaluatePendingSeekClear(pending, reportedMs, ageMs) ?: return null
        clearPendingSeek()
        return ClearedPendingSeek(reason, pending, positionMs)
    }

    fun clearPendingSeek(reason: String? = null): ClearedPendingSeek? {
        if (pendingSeekMs < 0) return null
        val cleared = reason?.let { ClearedPendingSeek(it, pendingSeekMs, positionMs) }
        pendingSeekMs = -1
        pendingSeekSetAtElapsedMs = 0L
        return cleared
    }

    fun setPendingRestore(songId: String, positionMs: Int) {
        pendingRestoreSongId = songId
        pendingRestorePositionMs = positionMs
    }

    fun releasePendingRestore(songId: String?) {
        if (pendingRestoreSongId == songId) clearPendingRestore()
    }

    fun restorePositionForSync(songId: String?): Int? {
        val pendingSongId = pendingRestoreSongId ?: return null
        if (pendingSongId == songId) return pendingRestorePositionMs
        clearPendingRestore()
        return null
    }

    fun pendingRestorePosition(): Int? =
        pendingRestoreSongId?.let { pendingRestorePositionMs }

    fun consumeRestoreStartPosition(songId: String): Int {
        val position = pendingRestorePositionMs
            .takeIf { pendingRestoreSongId == songId && it >= 1_000 }
            ?: 0
        clearPendingRestore()
        return position
    }

    private fun clearPendingRestore() {
        pendingRestoreSongId = null
        pendingRestorePositionMs = 0
    }

    private fun projectedPosition(nowMs: Long, songDurationSec: Int): Int {
        val elapsedMs = (nowMs - presentationAnchorElapsedMs).coerceAtLeast(0L)
        val advancedMs = (elapsedMs * presentationSpeed).roundToLong()
        return clampPosition(presentationAnchorPositionMs + advancedMs, songDurationSec)
    }

    private fun resetPresentationAnchor(
        nowMs: Long,
        positionMs: Int,
        isAdvancing: Boolean,
        speed: Float,
    ) {
        presentationInitialized = true
        presentationAnchorElapsedMs = nowMs
        presentationAnchorPositionMs = positionMs.toLong()
        presentationAdvancing = isAdvancing
        presentationSpeed = speed
    }

    private fun clampPosition(rawMs: Long, songDurationSec: Int): Int {
        val maxMs = uiDurationMs(songDurationSec).toLong()
        val clamped = if (maxMs > 0L) rawMs.coerceIn(0L, maxMs) else rawMs.coerceAtLeast(0L)
        return clamped.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

internal fun evaluatePendingSeekClear(
    pendingMs: Int,
    reportedMs: Int,
    pendingAgeMs: Long,
): String? {
    if (pendingMs < 0) return null
    val drift = kotlin.math.abs(reportedMs - pendingMs)
    if (drift <= PlaybackTimelineCoordinator.PENDING_SEEK_CONVERGE_TOLERANCE_MS) {
        return "converged driftMs=$drift"
    }
    if (pendingAgeMs > PlaybackTimelineCoordinator.PENDING_SEEK_MAX_AGE_MS) {
        return "timeout ageMs=$pendingAgeMs driftMs=$drift"
    }
    if (pendingAgeMs > PlaybackTimelineCoordinator.PENDING_SEEK_DRIFT_BAILOUT_MIN_AGE_MS &&
        reportedMs - pendingMs > PlaybackTimelineCoordinator.PENDING_SEEK_AHEAD_DRIFT_MS
    ) {
        return "ahead-drift ageMs=$pendingAgeMs pendingMs=$pendingMs reportedMs=$reportedMs"
    }
    return null
}
