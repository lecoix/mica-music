package com.mica.music.data.remote

import com.mica.music.data.PlayStats
import com.mica.music.data.PlayStatsSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Presentation-only play statistics for stable remote media ids. */
internal class RemotePlayStatsPresentation {
    private val mutableStats = MutableStateFlow<Map<String, PlayStats>>(emptyMap())
    val stats: StateFlow<Map<String, PlayStats>> = mutableStats.asStateFlow()

    /**
     * Publishes persisted stats for the current remote catalog without allowing a late disk read to
     * roll back newer playback events that were already presented in this process.
     */
    fun publishCatalog(mediaIds: Collection<String>, persisted: PlayStatsSnapshot) {
        val activeIds = mediaIds.asSequence()
            .filter { RemoteMediaIdCodec.decode(it) != null }
            .toSet()
        mutableStats.update { current ->
            buildMap {
                activeIds.forEach { mediaId ->
                    val merged = mergePlayStats(current[mediaId], persisted[mediaId])
                    if (merged.hasPresentationValue()) put(mediaId, merged)
                }
            }
        }
    }

    /** Applies one newly persisted playback-stat update when it belongs to a stable remote id. */
    fun applyLive(mediaId: String, stats: PlayStats) {
        if (RemoteMediaIdCodec.decode(mediaId) == null) return
        mutableStats.update { current ->
            val merged = mergePlayStats(current[mediaId], stats)
            if (!merged.hasPresentationValue()) current - mediaId else current + (mediaId to merged)
        }
    }
}

internal fun mergePlayStats(first: PlayStats?, second: PlayStats?): PlayStats {
    val a = first ?: EmptyPlayStats
    val b = second ?: EmptyPlayStats
    return PlayStats(
        count = maxOf(a.count, b.count),
        lastPlayedAtMs = maxOf(a.lastPlayedAtMs, b.lastPlayedAtMs),
        totalListenSeconds = maxOf(a.totalListenSeconds, b.totalListenSeconds),
    )
}

private fun PlayStats.hasPresentationValue(): Boolean =
    count != 0 || lastPlayedAtMs != 0L || totalListenSeconds != 0L

private val EmptyPlayStats = PlayStats(count = 0, lastPlayedAtMs = 0L, totalListenSeconds = 0L)
