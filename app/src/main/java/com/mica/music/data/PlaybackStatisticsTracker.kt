package com.mica.music.data

import androidx.media3.common.Player

/** Runtime rules for publishing play counts and whole listened seconds exactly once. */
internal class PlaybackStatisticsTracker(
    private val monotonicNowMs: () -> Long,
    private val onListenSecondsAdded: (songId: String, seconds: Long) -> Unit,
) {
    private var currentSongId: String? = null
    private var requestedSongId: String? = null
    private var pendingPlayStartedSongId: String? = null
    private var listeningSongId: String? = null
    private var listeningStartedAtMs = 0L

    val statisticsSongId: String?
        get() = currentSongId

    val pendingSongId: String?
        get() = pendingPlayStartedSongId

    fun reset(currentSongId: String?) {
        this.currentSongId = currentSongId
        requestedSongId = null
        pendingPlayStartedSongId = null
    }

    fun requestPlayback(songId: String) {
        requestedSongId = songId
    }

    fun clearRequestAndPending() {
        requestedSongId = null
        pendingPlayStartedSongId = null
    }

    fun onTransition(songId: String?, reason: Int): Boolean {
        val previousSongId = currentSongId
        val requested = requestedSongId == songId
        val shouldCount = when {
            songId == null -> false
            requested && songId != previousSongId -> true
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && songId != previousSongId -> true
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> true
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && songId != previousSongId -> true
            else -> false
        }
        if (songId != null) currentSongId = songId
        if (requested) requestedSongId = null
        if (shouldCount) pendingPlayStartedSongId = songId
        return shouldCount
    }

    fun publishPlayStartedIfReady(playerSongId: String?, playing: Boolean): String? {
        val pendingSongId = pendingPlayStartedSongId ?: return null
        if (!playing || pendingSongId != playerSongId) return null
        pendingPlayStartedSongId = null
        currentSongId = pendingSongId
        return pendingSongId
    }

    fun observePlayback(songId: String?, playing: Boolean) {
        val now = monotonicNowMs()
        val activeSongId = listeningSongId
        if (activeSongId != null && (!playing || songId != activeSongId)) {
            val seconds = ((now - listeningStartedAtMs).coerceAtLeast(0L)) / 1_000L
            listeningSongId = null
            listeningStartedAtMs = 0L
            if (seconds > 0L) onListenSecondsAdded(activeSongId, seconds)
        }
        if (playing && songId != null && listeningSongId == null) {
            listeningSongId = songId
            listeningStartedAtMs = now
        }
    }
}
