package com.mica.music.media

import androidx.media3.common.Player
import com.mica.music.data.PlaybackTuning
import com.mica.music.data.Song

/**
 * Materializes a persisted service queue before an output-path rebuild can retire the empty
 * process-start player. This is intentionally queue/cursor data only; restart never auto-resumes.
 */
internal data class ServicePlaybackBootstrap(
    val songs: List<Song>,
    val currentIndex: Int,
    val positionMs: Long,
    val repeatMode: Int,
    val playbackTuning: PlaybackTuning,
)

internal object ServicePlaybackBootstrapResolver {
    fun resolve(
        snapshot: ServicePlaybackSnapshot,
        songsById: Map<String, Song>,
    ): ServicePlaybackBootstrap? {
        val songs = snapshot.queueSongIds.mapNotNull(songsById::get)
        if (songs.isEmpty()) return null

        val currentId = snapshot.currentSongId.ifBlank {
            snapshot.queueSongIds.getOrNull(snapshot.currentIndex).orEmpty()
        }
        val currentIndex = songs.indexOfFirst { it.id == currentId }
            .takeIf { it >= 0 }
            ?: snapshot.currentIndex.coerceIn(0, songs.lastIndex)
        val repeatMode = snapshot.repeatMode.takeIf {
            it == Player.REPEAT_MODE_OFF ||
                it == Player.REPEAT_MODE_ONE ||
                it == Player.REPEAT_MODE_ALL
        } ?: Player.REPEAT_MODE_OFF

        return ServicePlaybackBootstrap(
            songs = songs,
            currentIndex = currentIndex,
            positionMs = snapshot.positionMs.coerceAtLeast(0L),
            repeatMode = repeatMode,
            playbackTuning = snapshot.playbackTuning,
        )
    }
}
