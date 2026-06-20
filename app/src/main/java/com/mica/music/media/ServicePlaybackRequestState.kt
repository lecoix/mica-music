package com.mica.music.media

internal class ServicePlaybackRequestState(
    private val sequencer: PlaybackRequestSequencer = PlaybackRequestSequencer(),
) {
    private data class TerminalFailure(
        val request: PlaybackRequest,
        val failure: PlaybackFailure,
    )

    var activeRequest: PlaybackRequest? = null
        private set

    var consecutiveFailures: Int = 0
        private set

    private var terminalFailure: TerminalFailure? = null

    fun begin(
        song: com.mica.music.data.Song,
        positionMs: Long,
    ): PlaybackRequest {
        val request = sequencer.next(song, positionMs)
        activeRequest = request
        terminalFailure = null
        return request
    }

    fun accepts(requestId: Long): Boolean = activeRequest?.id == requestId

    fun accepts(
        requestId: Long,
        songId: String,
        sourceRevision: String,
    ): Boolean {
        val request = activeRequest ?: return false
        return request.id == requestId &&
            request.songId == songId &&
            request.sourceRevision == sourceRevision
    }

    fun markPlaybackProgress(requestId: Long, positionMs: Long) {
        if (!accepts(requestId)) return
        if (positionMs >= STABLE_PLAYBACK_RESET_MS) consecutiveFailures = 0
    }

    fun failureFor(songId: String, sourceRevision: String): PlaybackFailure? {
        val terminal = terminalFailure ?: return null
        return terminal.failure.takeIf {
            terminal.request.songId == songId &&
                terminal.request.sourceRevision == sourceRevision
        }
    }

    fun markFailed(requestId: Long, failure: PlaybackFailure): Int? {
        val request = activeRequest?.takeIf { it.id == requestId } ?: return null
        if (terminalFailure?.request?.id == requestId) return null
        terminalFailure = TerminalFailure(request, failure)
        consecutiveFailures++
        return consecutiveFailures
    }

    private companion object {
        const val STABLE_PLAYBACK_RESET_MS = 1_000L
    }
}
