package com.mica.music.data

internal enum class PlaybackMediaTransition {
    Explicit,
    Automatic,
    Repeat,
    Other,
}

internal data class PlaybackPositionDiscontinuity(
    val oldSongId: String?,
    val newSongId: String?,
    val oldPositionMs: Long,
    val newPositionMs: Long,
    val automatic: Boolean,
)

/** Runtime rules for publishing each real playback session and whole listened seconds exactly once. */
internal class PlaybackStatisticsTracker(
    private val monotonicNowMs: () -> Long,
    private val onListenSecondsAdded: (songId: String, seconds: Long) -> Unit,
) {
    private data class TransitionEvidence(
        val songId: String?,
        val transition: PlaybackMediaTransition,
    )

    private data class PendingPlaySession(
        val songId: String,
        val generation: Long,
    )

    private var currentSongId: String? = null
    private var requestedSongId: String? = null
    private var batchTransition: TransitionEvidence? = null
    private var batchDiscontinuity: PlaybackPositionDiscontinuity? = null
    private var eventBatchGeneration = 0L
    private var pendingPlaySession: PendingPlaySession? = null
    private var listeningSongId: String? = null
    private var listeningStartedAtMs = 0L

    val statisticsSongId: String?
        get() = currentSongId

    val pendingSongId: String?
        get() = pendingPlaySession?.songId

    fun reset(currentSongId: String?) {
        this.currentSongId = currentSongId
        requestedSongId = null
        batchTransition = null
        batchDiscontinuity = null
        pendingPlaySession = null
    }

    fun requestPlayback(songId: String) {
        requestedSongId = songId
        pendingPlaySession = null
    }

    fun clearRequestAndPending() {
        requestedSongId = null
        batchTransition = null
        batchDiscontinuity = null
        pendingPlaySession = null
    }

    fun onTransition(songId: String?, transition: PlaybackMediaTransition) {
        batchTransition = TransitionEvidence(songId, transition)
        if (songId != null) currentSongId = songId
    }

    fun onPositionDiscontinuity(discontinuity: PlaybackPositionDiscontinuity) {
        batchDiscontinuity = discontinuity
        if (discontinuity.newSongId != null) currentSongId = discontinuity.newSongId
    }

    /** Accepts an automatic boundary observed directly by the service-owned player. */
    fun onConfirmedAutomaticBoundary(discontinuity: PlaybackPositionDiscontinuity): Boolean {
        val songId = discontinuity.newSongId ?: return false
        val changedSong = discontinuity.oldSongId != discontinuity.newSongId
        val repeatedSong = discontinuity.oldSongId == discontinuity.newSongId &&
            discontinuity.oldPositionMs > discontinuity.newPositionMs
        if (!discontinuity.automatic || (!changedSong && !repeatedSong)) return false

        eventBatchGeneration += 1
        pendingPlaySession = PendingPlaySession(songId, eventBatchGeneration)
        currentSongId = songId
        return true
    }

    /** Resolves one Media3 onEvents batch into at most one new playback generation. */
    fun finishEventBatch(): Boolean {
        val transition = batchTransition
        val discontinuity = batchDiscontinuity
        val requested = requestedSongId
        val explicitSongId = requested?.takeIf { songId ->
            val explicitTransition = transition?.let {
                it.songId == songId && it.transition == PlaybackMediaTransition.Explicit
            } == true
            val explicitSeek = discontinuity?.let {
                !it.automatic && it.newSongId == songId
            } == true
            explicitTransition || explicitSeek
        }
        val candidateSongId = explicitSongId
        batchTransition = null
        batchDiscontinuity = null
        eventBatchGeneration += 1
        if (candidateSongId == null) return false

        pendingPlaySession = PendingPlaySession(candidateSongId, eventBatchGeneration)
        if (requested == candidateSongId) requestedSongId = null
        currentSongId = candidateSongId
        return true
    }

    fun publishPlayStartedIfReady(playerSongId: String?, playing: Boolean): String? {
        val pending = pendingPlaySession ?: return null
        if (!playing || pending.songId != playerSongId) return null
        pendingPlaySession = null
        currentSongId = pending.songId
        return pending.songId
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
