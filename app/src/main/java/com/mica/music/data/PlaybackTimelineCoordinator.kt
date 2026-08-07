package com.mica.music.data

internal data class ClearedPendingSeek(
    val reason: String,
    val pendingMs: Int,
    val positionMs: Int,
)

internal class PlaybackTimelineCoordinator(
    private val monotonicNowMs: () -> Long,
) {
    var positionMs: Int = 0
        private set

    var durationSec: Int = 0
        private set

    var pendingSeekMs: Int = -1
        private set

    var seekUiActive: Boolean = false
        private set

    private var pendingSeekSetAtElapsedMs = 0L
    private var pendingRestoreSongId: String? = null
    private var pendingRestorePositionMs = 0

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
        val maxMs = uiDurationMs(songDurationSec)
        positionMs = if (maxMs > 0) rawMs.coerceIn(0, maxMs) else rawMs.coerceAtLeast(0)
    }

    fun updatePlayerDuration(durationMs: Long) {
        if (durationMs > 0) durationSec = (durationMs / 1000).toInt()
    }

    fun resetDurationForSongChange(songDurationSec: Int): Int {
        val previousSec = durationSec
        durationSec = songDurationSec.coerceAtLeast(0)
        return previousSec
    }

    fun armPendingSeek(targetMs: Int) {
        pendingSeekMs = targetMs
        pendingSeekSetAtElapsedMs = monotonicNowMs()
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
}
