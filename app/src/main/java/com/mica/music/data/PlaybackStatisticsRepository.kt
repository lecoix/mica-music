package com.mica.music.data

import android.content.Context
import com.mica.music.util.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-lifetime owner for play-count / listen-seconds persistence.
 *
 * Exposes narrow playback-event sinks; the app composition root wires producers to them. Writes go to
 * [PlayHistoryStore] on a scope that outlives Activity/ViewModel. Optional presentation
 * sinks (typically the current [MusicLibrary]) may refresh Compose song rows; missing
 * or released sinks must never block persistence.
 *
 * Persistence is a single FIFO consumer over [mutations]: producers only enqueue, so overlapping
 * play-count and listen-seconds updates cannot race inside [PlayHistoryStore]'s get-then-edit path.
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
    private val mutations = Channel<Mutation>(capacity = Channel.UNLIMITED)

    init {
        scope.launch {
            for (mutation in mutations) {
                val stats = try {
                    when (mutation) {
                        is Mutation.PlayStarted -> PlayHistoryStore.recordPlay(appContext, mutation.songId)
                        is Mutation.ListenSeconds -> PlayHistoryStore.recordListenSeconds(
                            appContext,
                            mutation.songId,
                            mutation.seconds,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    DiagnosticLog.event(
                        "PlaybackStats",
                        "persistence failed song=${mutation.songId} type=${mutation::class.simpleName}",
                        error,
                    )
                    continue
                }

                try {
                    notifyPresentation(mutation.songId, stats)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // Presentation is best-effort. A Compose/ViewModel callback must never kill the
                    // process-lifetime persistence writer or strand future UNLIMITED channel items.
                    DiagnosticLog.event(
                        "PlaybackStats",
                        "presentation failed song=${mutation.songId}",
                        error,
                    )
                }
            }
        }
    }

    val playStartedSink: (String) -> Unit = ::recordPlay
    val listenSecondsSink: (String, Long) -> Unit = ::recordListenSeconds

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
        check(mutations.trySend(Mutation.PlayStarted(songId)).isSuccess) {
            "Playback statistics writer is unavailable"
        }
    }

    fun recordListenSeconds(songId: String, seconds: Long) {
        if (seconds <= 0L || !isPersistentSong(songId)) return
        check(mutations.trySend(Mutation.ListenSeconds(songId, seconds)).isSuccess) {
            "Playback statistics writer is unavailable"
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

    private sealed interface Mutation {
        val songId: String

        data class PlayStarted(override val songId: String) : Mutation

        data class ListenSeconds(
            override val songId: String,
            val seconds: Long,
        ) : Mutation
    }

    private data class PresentationSink(
        val token: Any,
        val sink: (songId: String, stats: PlayStats) -> Unit,
    )
}
