package com.mica.music.media

import android.os.Handler
import com.mica.music.data.LyricsDocument
import com.mica.music.data.LyricsSlot
import com.mica.music.data.SharedLyricsMemoryCache
import com.mica.music.data.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal data class LyricsLoadSpec(
    val songId: String,
    val lyricsRevision: String,
    val lyricsDataVersion: Int,
    val priority: List<LyricsSlot>,
)

internal sealed interface NotificationLyricsLoadState {
    data class Ready(val song: Song) : NotificationLyricsLoadState
    data object Loading : NotificationLyricsLoadState
    data object Absent : NotificationLyricsLoadState
    data class Failed(val error: Throwable) : NotificationLyricsLoadState
}

private data class LyricsLoadKey(
    val songId: String,
    val lyricsRevision: String,
    val lyricsDataVersion: Int,
)

internal class NotificationLyricsSongCache(
    private val scope: CoroutineScope,
    private val handler: Handler,
    private val loadSong: suspend (LyricsLoadSpec) -> Song?,
) {
    private val lock = Any()
    private val waiters = mutableMapOf<LyricsLoadKey, MutableList<(NotificationLyricsLoadState) -> Unit>>()

    fun request(
        decoded: Song,
        spec: LyricsLoadSpec,
        onResult: (NotificationLyricsLoadState) -> Unit,
    ): NotificationLyricsLoadState {
        require(decoded.id == spec.songId)
        SharedLyricsMemoryCache.get(spec.songId, spec.lyricsRevision, spec.lyricsDataVersion)?.let { document ->
            return if (document.lines.isEmpty()) {
                NotificationLyricsLoadState.Absent
            } else {
                NotificationLyricsLoadState.Ready(decoded.copy(lyricsDocument = document, lyricsLoaded = true))
            }
        }
        val key = LyricsLoadKey(spec.songId, spec.lyricsRevision, spec.lyricsDataVersion)
        val shouldLoad = synchronized(lock) {
            val existing = waiters[key]
            if (existing == null) {
                waiters[key] = mutableListOf(onResult)
                true
            } else {
                existing += onResult
                false
            }
        }
        if (shouldLoad) {
            scope.launch {
                try {
                    val document = SharedLyricsMemoryCache.load(
                        spec.songId,
                        spec.lyricsRevision,
                        spec.lyricsDataVersion,
                    ) {
                        loadSong(spec)?.lyricsDocument ?: LyricsDocument()
                    }
                    val result = if (document.lines.isEmpty()) {
                        NotificationLyricsLoadState.Absent
                    } else {
                        NotificationLyricsLoadState.Ready(
                            decoded.copy(lyricsDocument = document, lyricsLoaded = true),
                        )
                    }
                    dispatch(key, result)
                } catch (error: CancellationException) {
                    synchronized(lock) { waiters.remove(key) }
                    throw error
                } catch (error: Throwable) {
                    dispatch(key, NotificationLyricsLoadState.Failed(error))
                }
            }
        }
        return NotificationLyricsLoadState.Loading
    }

    fun songWithLyrics(
        decoded: Song,
        lyricsRevision: String,
        lyricsDataVersion: Int,
        priority: List<LyricsSlot>,
        onLoaded: () -> Unit,
    ): Song {
        return when (val state = request(
            decoded = decoded,
            spec = LyricsLoadSpec(decoded.id, lyricsRevision, lyricsDataVersion, priority.toList()),
        ) { onLoaded() }) {
            is NotificationLyricsLoadState.Ready -> state.song
            else -> decoded
        }
    }

    fun clear() {
        synchronized(lock) { waiters.clear() }
    }

    private fun dispatch(key: LyricsLoadKey, result: NotificationLyricsLoadState) {
        val callbacks = synchronized(lock) { waiters.remove(key).orEmpty() }
        if (callbacks.isNotEmpty()) handler.post { callbacks.forEach { it(result) } }
    }
}
