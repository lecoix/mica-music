package com.mica.music.data

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-lifetime owner for play-count / listen-seconds persistence.
 *
 * Binds [PlayerController] publish callbacks once for the process. Writes go to
 * [PlayHistoryStore] on a scope that outlives Activity/ViewModel. Optional presentation
 * sinks (typically the current [MusicLibrary]) may refresh Compose song rows; missing
 * or released sinks must never block persistence.
 */
class PlaybackStatisticsRepository(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val isPersistentSong: (String) -> Boolean = { true },
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val presentation = AtomicReference<PresentationSink?>(null)
    private var bound = false

    fun bind(playerController: PlayerController) {
        if (bound) return
        bound = true
        playerController.onSongPlayStarted = { songId -> recordPlay(songId) }
        playerController.onSongListenSecondsAdded = { songId, seconds ->
            recordListenSeconds(songId, seconds)
        }
    }

    fun attachPresentationSink(token: Any, sink: (songId: String, stats: PlayStats) -> Unit) {
        presentation.set(PresentationSink(token, sink))
    }

    fun detachPresentationSink(token: Any) {
        presentation.updateAndGet { current ->
            if (current?.token === token) null else current
        }
    }

    fun recordPlay(songId: String) {
        if (!isPersistentSong(songId)) return
        scope.launch {
            val stats = PlayHistoryStore.recordPlay(appContext, songId)
            notifyPresentation(songId, stats)
        }
    }

    fun recordListenSeconds(songId: String, seconds: Long) {
        if (seconds <= 0L || !isPersistentSong(songId)) return
        scope.launch {
            val stats = PlayHistoryStore.recordListenSeconds(appContext, songId, seconds)
            notifyPresentation(songId, stats)
        }
    }

    private suspend fun notifyPresentation(songId: String, stats: PlayStats) {
        val sink = presentation.get() ?: return
        withContext(mainDispatcher) {
            val current = presentation.get()
            if (current === sink) {
                current.sink(songId, stats)
            }
        }
    }

    private data class PresentationSink(
        val token: Any,
        val sink: (songId: String, stats: PlayStats) -> Unit,
    )
}
