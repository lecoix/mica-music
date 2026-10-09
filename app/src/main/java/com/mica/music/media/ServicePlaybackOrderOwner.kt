package com.mica.music.media

import android.os.Bundle
import com.mica.music.queue.PlaybackShuffleOrder

internal data class AcceptedPlaybackOrder(
    val playbackIds: List<String>,
    val sourceIds: List<String>,
    val shuffleEnabled: Boolean,
)

/** Main-looper owner. Only accepted permutations are installed, persisted or returned to clients. */
internal class ServicePlaybackOrderOwner {
    private var accepted: AcceptedPlaybackOrder? = null

    fun project(physicalIds: List<String>): AcceptedPlaybackOrder? {
        val previous = accepted ?: return null
        if (physicalIds.isEmpty()) { accepted = null; return null }
        val members = physicalIds.toHashSet()
        fun reconcile(ids: List<String>): List<String> {
            val kept = ids.filter { it in members }
            val seen = kept.toHashSet()
            return kept + physicalIds.filterNot(seen::contains)
        }
        return previous.copy(playbackIds = reconcile(previous.playbackIds),
            sourceIds = reconcile(previous.sourceIds)).also { accepted = it }
    }

    fun accept(request: PlaybackShuffleRequest, physicalIds: List<String>, currentId: String?): AcceptedPlaybackOrder? {
        if (request.playbackIndices != null) {
            if (!PlaybackShuffleSessionCommand.isCurrent(request.requestId) ||
                request.physicalFingerprint != PlaybackShuffleSessionCommand.fingerprint(physicalIds) ||
                request.playbackIndices.size != physicalIds.size) return null
            return AcceptedPlaybackOrder(request.playbackIndices.map(physicalIds::get),
                requireNotNull(request.sourceIndices).map(physicalIds::get), request.enabled)
                .also { accepted = it }
        }
        // Compatibility for old clients; seed is consumed once, never replayed on a new cursor.
        if (!PlaybackShuffleSessionCommand.isCurrent(0L)) return null
        val playback = if (request.enabled) PlaybackShuffleOrder.orderedIds(physicalIds, currentId,
            request.seed ?: return null) else physicalIds
        return AcceptedPlaybackOrder(playback, physicalIds, request.enabled).also { accepted = it }
    }

    fun restore(physicalIds: List<String>, playbackIds: List<String>, sourceIds: List<String>, enabled: Boolean) {
        if (accepted != null || playbackIds.isEmpty()) return
        accepted = AcceptedPlaybackOrder(playbackIds, sourceIds.ifEmpty { playbackIds }, enabled)
        project(physicalIds)
    }

    fun snapshot(physicalIds: List<String>): Bundle = project(physicalIds)?.let {
        PlaybackShuffleSessionCommand.encodeOrder(physicalIds, it.playbackIds, it.sourceIds,
            it.shuffleEnabled, 0L)
    } ?: Bundle.EMPTY
}
