package com.mica.music.data

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

data class PlaybackSurfaceState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val isBuffering: Boolean = false,
    val playbackError: String? = null,
    val playbackQueueMode: PlaybackQueueMode = PlaybackQueueMode.OFF,
    val playbackTuning: PlaybackTuning = PlaybackTuning(),
    val currentIndex: Int = 0,
)

data class PlaybackProgressState(
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val pendingSeekMs: Int = -1,
    val positionRevision: Long = 0L,
)

data class PlaybackQueueState(
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = 0,
)

/**
 * UI-facing playback facade.
 *
 * PlaybackRuntime owns MediaController lifecycle, Player.Listener semantics and playback
 * application state. This class only projects runtime snapshots into Compose state and preserves
 * the existing user-intent API used by the UI.
 */
class PlayerController internal constructor(
    context: Context,
    mediaControllerConnector: MediaControllerConnector,
    sessionStorage: PlaybackSessionStorage,
    songResolver: PlaybackSongResolver,
    dispatcher: CoroutineDispatcher,
    queueMirrorDispatcher: CoroutineDispatcher = Dispatchers.Default,
    monotonicNowMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    constructor(context: Context, songResolver: PlaybackSongResolver) : this(
        context = context,
        mediaControllerConnector = AndroidMediaControllerConnector(context.applicationContext),
        sessionStorage = PreferencesPlaybackSessionStorage(context.applicationContext),
        songResolver = songResolver,
        dispatcher = Dispatchers.Main.immediate,
    )

    constructor(context: Context) : this(
        context = context,
        songResolver = ProcessPlaybackSongResolver(TransientPlaybackCatalog()),
    )

    internal companion object {
        const val PENDING_SEEK_CONVERGE_TOLERANCE_MS = PlaybackRuntime.PENDING_SEEK_CONVERGE_TOLERANCE_MS
        const val PENDING_SEEK_MAX_AGE_MS = PlaybackRuntime.PENDING_SEEK_MAX_AGE_MS
        const val PENDING_SEEK_DRIFT_BAILOUT_MIN_AGE_MS = PlaybackRuntime.PENDING_SEEK_DRIFT_BAILOUT_MIN_AGE_MS
        const val PENDING_SEEK_AHEAD_DRIFT_MS = PlaybackRuntime.PENDING_SEEK_AHEAD_DRIFT_MS
        const val QUEUE_MIRROR_DEBOUNCE_MS = PlaybackRuntime.QUEUE_MIRROR_DEBOUNCE_MS
    }

    /** 寮€濮嬫挱鏀炬煇鏇叉椂鍥炶皟锛堢敤浜庣粺璁℃挱鏀炬鏁帮級銆?*/
    var onSongPlayStarted: ((songId: String) -> Unit)? = null

    var onSongListenSecondsAdded: ((songId: String, seconds: Long) -> Unit)? = null

    var userMessage by mutableStateOf<UserMessage?>(null)
        private set

    var playbackSurfaceState by mutableStateOf(PlaybackSurfaceState())
        private set

    var playbackProgressState by mutableStateOf(PlaybackProgressState())
        private set

    var playbackQueueState by mutableStateOf(PlaybackQueueState())
        private set

    private val runtime = PlaybackRuntime(
        context = context,
        mediaControllerConnector = mediaControllerConnector,
        sessionStorage = sessionStorage,
        songResolver = songResolver,
        dispatcher = dispatcher,
        queueMirrorDispatcher = queueMirrorDispatcher,
        monotonicNowMs = monotonicNowMs,
        stateSink = ::applyRuntimeSnapshot,
        playStartedSink = { songId -> onSongPlayStarted?.invoke(songId) },
        listenSecondsSink = { songId, seconds -> onSongListenSecondsAdded?.invoke(songId, seconds) },
    )

    private fun applyRuntimeSnapshot(snapshot: PlaybackRuntimeSnapshot) {
        playbackSurfaceState = PlaybackSurfaceState(
            currentSong = snapshot.currentSong,
            isPlaying = snapshot.isPlaying,
            playWhenReady = snapshot.playWhenReady,
            isBuffering = snapshot.isBuffering,
            playbackError = snapshot.playbackError,
            playbackQueueMode = snapshot.playbackQueueMode,
            playbackTuning = snapshot.playbackTuning,
            currentIndex = snapshot.currentIndex,
        )
        playbackProgressState = PlaybackProgressState(
            positionMs = snapshot.positionMs,
            durationMs = snapshot.durationMs,
            pendingSeekMs = snapshot.pendingSeekMs,
            positionRevision = snapshot.positionRevision,
        )
        val nextQueueState = PlaybackQueueState(
            queue = snapshot.queue,
            currentIndex = snapshot.currentIndex,
        )
        if (nextQueueState != playbackQueueState) playbackQueueState = nextQueueState
        userMessage = snapshot.userMessage
    }

    fun peekTrackSkipDirection(): TrackSkipDirection? = runtime.peekTrackSkipDirection()

    fun consumeTrackSkipDirection(): TrackSkipDirection? = runtime.consumeTrackSkipDirection()

    fun setSeekUiActive(active: Boolean) = runtime.setSeekUiActive(active)

    @Deprecated("Use setSeekUiActive", ReplaceWith("setSeekUiActive(active)"))
    internal fun setAlacSeekUiActive(active: Boolean) = setSeekUiActive(active)

    internal fun uiPositionMs(): Int = runtime.uiPositionMs()

    internal fun uiDurationMs(): Int = runtime.uiDurationMs()

    internal fun persistPlaybackSessionNow() = runtime.persistPlaybackSessionNow()

    @Deprecated("Use bootstrapQueue(ServicePlaybackStateStore) instead")
    internal fun restoreSession(session: PlaybackSession) = runtime.restoreSession(session)

    @Deprecated("Use bootstrapQueue instead")
    internal fun reconcileRestoredSessionIndex() = runtime.reconcileRestoredSessionIndex()

    internal val isConnected: Boolean
        get() = runtime.isConnected

    fun connectIfNeeded() = runtime.connectIfNeeded()

    fun retryConnect() = runtime.retryConnect()

    fun bootstrapQueue(resolveSong: (String) -> Song?): Boolean = runtime.bootstrapQueue(resolveSong)

    fun syncPlaybackState() = runtime.syncPlaybackState()

    fun setQueue(newQueue: List<Song>) = runtime.setQueue(newQueue)

    fun refreshQueueMetadata(latestSongs: List<Song>) = runtime.refreshQueueMetadata(latestSongs)

    fun syncPosition() = runtime.syncPosition()

    fun pauseIfPlaying() = runtime.pauseIfPlaying()

    fun setPlaybackVolume(volume: Float) = runtime.setPlaybackVolume(volume)

    val playbackVolume: Float
        get() = runtime.playbackVolume

    fun setPlaybackSpeed(speed: Float) = runtime.setPlaybackSpeed(speed)

    fun setPlaybackPitchSemitones(semitones: Float) = runtime.setPlaybackPitchSemitones(semitones)

    fun resetPlaybackTuning() = runtime.resetPlaybackTuning()

    fun togglePlay() = runtime.togglePlay()

    fun playSongById(songId: String) = runtime.playSongById(songId)

    fun playSingleSong(song: Song) = runtime.playSingleSong(song)

    fun insertPlayNext(song: Song) = runtime.insertPlayNext(song)

    fun moveInQueue(fromIndex: Int, toIndex: Int) = runtime.moveInQueue(fromIndex, toIndex)

    fun removeFromQueue(index: Int) = runtime.removeFromQueue(index)

    fun playSong(index: Int) = runtime.playSong(index)

    fun cyclePlaybackQueueMode() = runtime.cyclePlaybackQueueMode()

    fun manualNextTarget(): Int? = runtime.manualNextTarget()

    fun manualPreviousTarget(): Int? = runtime.manualPreviousTarget()

    fun next() = runtime.next()

    fun previous() = runtime.previous()

    fun seek(seconds: Int) = runtime.seekToMs(seconds * 1000)

    fun seekToMs(targetMs: Int) = runtime.seekToMs(targetMs)

    fun clearUserMessage() = runtime.clearUserMessage()

    fun clearPlaybackError() = runtime.clearPlaybackError()

    fun release() = runtime.release()
}

/**
 * 鍒ゆ柇 seek 鍚庢槸鍚﹀簲鏀惧純 [PlayerController] 鐨?pending UI 閽夋銆?
 * @return 娓呴櫎鍘熷洜锛沗null` 琛ㄧず缁х画閽変綇 pending銆?
 */
internal fun evaluatePendingSeekClear(
    pendingMs: Int,
    reportedMs: Int,
    pendingAgeMs: Long,
): String? {
    if (pendingMs < 0) return null
    val drift = kotlin.math.abs(reportedMs - pendingMs)
    if (drift <= PlayerController.PENDING_SEEK_CONVERGE_TOLERANCE_MS) {
        return "converged driftMs=$drift"
    }
    if (pendingAgeMs > PlayerController.PENDING_SEEK_MAX_AGE_MS) {
        return "timeout ageMs=$pendingAgeMs driftMs=$drift"
    }
    if (pendingAgeMs > PlayerController.PENDING_SEEK_DRIFT_BAILOUT_MIN_AGE_MS &&
        reportedMs - pendingMs > PlayerController.PENDING_SEEK_AHEAD_DRIFT_MS
    ) {
        return "ahead-drift ageMs=$pendingAgeMs pendingMs=$pendingMs reportedMs=$reportedMs"
    }
    return null
}
