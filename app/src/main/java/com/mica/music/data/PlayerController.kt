package com.mica.music.data

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Timeline
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.mica.music.media.PendingPlaybackNavigation
import com.mica.music.media.ConfirmedPlaybackBoundary
import com.mica.music.media.PlaybackRouter
import com.mica.music.media.ServicePlaybackStateStore
import com.mica.music.media.SongMediaItemCodec
import com.mica.music.util.DiagnosticLog
import com.mica.music.util.TrackSwitchPerformance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

data class PlaybackSurfaceState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
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
)

data class PlaybackQueueState(
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = 0,
)

internal class PendingMediaSelection {
    private var targetSongId: String? = null

    fun select(songId: String) {
        targetSongId = songId
    }

    fun shouldAccept(mediaId: String?): Boolean {
        val target = targetSongId ?: return true
        if (mediaId != target) return false
        targetSongId = null
        return true
    }

    fun accepts(mediaId: String?): Boolean {
        val target = targetSongId ?: return true
        return mediaId == target
    }

    fun clear() {
        targetSongId = null
    }
}

/**
 * 把 MediaController 桥接成 Compose State，同时承载队列。
 */
class PlayerController internal constructor(
    private val context: Context,
    private val mediaControllerConnector: MediaControllerConnector,
    private val sessionStorage: PlaybackSessionStorage,
    private val songResolver: PlaybackSongResolver,
    dispatcher: CoroutineDispatcher,
    private val queueMirrorDispatcher: CoroutineDispatcher = Dispatchers.Default,
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
        /** seek 落位后与 pending 的最大可接受偏差。 */
        const val PENDING_SEEK_CONVERGE_TOLERANCE_MS = 1_500

        /** pending 最长信任窗口；超时后改跟 Exo 上报进度。 */
        const val PENDING_SEEK_MAX_AGE_MS = 4_000L

        /** 至少等待这么久再判断「Exo 仍明显超前」并放弃钉死 UI。 */
        const val PENDING_SEEK_DRIFT_BAILOUT_MIN_AGE_MS = 500L

        /** Exo 仍比 pending 超前超过该值时放弃钉死（典型：往回拖 seek 尚未落位）。 */
        const val PENDING_SEEK_AHEAD_DRIFT_MS = 1_500

        const val QUEUE_MIRROR_DEBOUNCE_MS = 100L

    }

    private val appCtx = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var deferredPlaybackPublish: Runnable? = null
    private val queueCoordinator = PlaybackQueueCoordinator(
        scope = scope,
        workerDispatcher = queueMirrorDispatcher,
        mirrorDebounceMs = QUEUE_MIRROR_DEBOUNCE_MS,
    )
    private val timelineCoordinator = PlaybackTimelineCoordinator(monotonicNowMs)
    private val tuningCoordinator = PlaybackTuningCoordinator()
    private val playbackOrderState: PlaybackOrderState
        get() = queueCoordinator.order

    /** 开始播放某曲时回调（用于统计播放次数）。 */
    var onSongPlayStarted: ((songId: String) -> Unit)? = null

    var onSongListenSecondsAdded: ((songId: String, seconds: Long) -> Unit)? = null

    private val currentIndex: Int
        get() = queueCoordinator.currentIndex

    /** 供播放页切歌擦除动画消费；在 [next]/[previous]/自动下一曲 时设置。 */
    private var trackSkipDirection by mutableStateOf<TrackSkipDirection?>(null)

    fun peekTrackSkipDirection(): TrackSkipDirection? = trackSkipDirection

    fun consumeTrackSkipDirection(): TrackSkipDirection? {
        val direction = trackSkipDirection
        trackSkipDirection = null
        return direction
    }

    private var isPlaying by mutableStateOf(false)

    private val positionMs: Int
        get() = timelineCoordinator.positionMs

    private val durationSec: Int
        get() = timelineCoordinator.durationSec

    private val pendingSeekMs: Int
        get() = timelineCoordinator.pendingSeekMs

    fun setSeekUiActive(active: Boolean) {
        timelineCoordinator.setSeekUiActive(active)
    }

    @Deprecated("Use setSeekUiActive", ReplaceWith("setSeekUiActive(active)"))
    internal fun setAlacSeekUiActive(active: Boolean) = setSeekUiActive(active)

    internal fun uiPositionMs(): Int {
        return timelineCoordinator.uiPositionMs(currentSong?.durationSec ?: 0)
    }

    internal fun uiDurationMs(): Int =
        timelineCoordinator.uiDurationMs(currentSong?.durationSec ?: 0)

    private fun maxDurationMs(): Int = uiDurationMs()

    private fun songMetaDurationMs(): Int =
        (currentSong?.durationSec ?: 0).coerceAtLeast(0) * 1000

    private fun playerReportedDurationMs(): Int = durationSec.coerceAtLeast(0) * 1000

    private fun exoDurationMs(controller: Player?): Int? {
        val raw = controller?.duration ?: return null
        if (raw == C.TIME_UNSET || raw < 0L) return null
        return raw.toInt()
    }

    /** 切歌时丢弃上一首 Exo 上报的 [durationSec]，避免 max(meta, player) 把旧总长带进新曲。 */
    private fun resetDurationForSongChange(song: Song) {
        val previousSec = timelineCoordinator.resetDurationForSongChange(song.durationSec)
        DiagnosticLog.event(
            "Player",
            "duration-reset song=${song.id} prevPlayerSec=$previousSec metaSec=${song.durationSec} " +
                "uiDurationMs=${uiDurationMs()}",
        )
    }

    private fun seekDiagnosticFields(controller: MediaController?): String {
        val exoDur = exoDurationMs(controller)
        val exoPos = controller?.currentPosition?.coerceAtLeast(0L)?.toInt()
        val exoMediaId = controller?.currentMediaItem?.mediaId
        val playbackState = controller?.playbackState
        val seekAvailable = controller?.availableCommands?.contains(
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        ) == true
        return buildString {
            append("metaDurationMs=${songMetaDurationMs()}")
            append(" playerDurationMs=${playerReportedDurationMs()}")
            append(" exoDurationMs=${exoDur ?: "unset"}")
            append(" exoPositionMs=${exoPos ?: "n/a"}")
            append(" uiDurationMs=${uiDurationMs()}")
            append(" pendingSeekMs=$pendingSeekMs")
            append(" exoMediaId=$exoMediaId")
            append(" expectedSongId=${currentSong?.id}")
            append(" playbackState=$playbackState")
            append(" commandAvailable=$seekAvailable")
        }
    }

    private fun setPositionMsClamped(rawMs: Int) {
        timelineCoordinator.setPositionClamped(rawMs, currentSong?.durationSec ?: 0)
        publishProgressState()
    }

    private fun notifyPlaybackProgress(rawMs: Int) {
        setPositionMsClamped(rawMs)
        reconcilePendingSeekAfterProgress(rawMs)
    }

    private fun commitSongQueue(queue: List<Song>) {
        queueCoordinator.replaceQueue(queue)
    }

    private fun queueModel(): PlaybackQueueModel = queueCoordinator.snapshot()

    private fun installQueueModel(model: PlaybackQueueModel) {
        queueCoordinator.commit(model)
    }

    private fun reconcilePendingSeekAfterProgress(reportedMs: Int) {
        timelineCoordinator.reconcilePendingSeek(reportedMs)?.let(::logPendingSeekCleared)
        publishProgressState()
    }

    private fun armPendingSeek(targetMs: Int) {
        timelineCoordinator.armPendingSeek(targetMs)
    }

    private fun canAcceptSeek(controller: MediaController?): Boolean {
        if (controller == null) return false
        if (controller.playbackState != Player.STATE_READY) return false
        if (!controller.availableCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            return false
        }
        val expectedSongId = currentSong?.id ?: return false
        return controller.currentMediaItem?.mediaId == expectedSongId
    }

    internal fun persistPlaybackSessionNow() {
        savePlaybackSession(sync = true)
    }

    private fun maybePersistPlaybackSession(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSessionPersistMs < 3_000L) return
        lastSessionPersistMs = now
        savePlaybackSession(sync = false)
    }

    private fun savePlaybackSession(sync: Boolean) {
        val song = currentSong ?: return
        sessionStorage.save(
            PlaybackSession(
                songId = song.id,
                positionMs = uiPositionMs(),
                shuffleEnabled = playbackOrderState.shuffleEnabled,
            ),
            sync = sync,
        )
    }

    /** 曲库就绪后恢复上次播放的歌曲与进度（不自动开始播放）。优先使用 [bootstrapQueue]。 */
    @Deprecated("Use bootstrapQueue(ServicePlaybackStateStore) instead")
    internal fun restoreSession(session: PlaybackSession) {
        if (songQueue.isEmpty()) return
        val index = songQueue.indexOfFirst { it.id == session.songId }
        if (index < 0) {
            sessionStorage.clear()
            return
        }
        queueCoordinator.replaceOrder(
            playbackOrderState
                .withQueue(songQueue.map { it.id }, preserveId = session.songId)
                .setShuffleEnabled(session.shuffleEnabled),
        )
        if (session.shuffleEnabled) {
            applyPlaybackOrderState(playbackOrderState, songQueue)
        } else {
            queueCoordinator.replaceCurrentIndex(index)
        }
        publishPlaybackStates()
        val pos = session.positionMs.coerceAtLeast(0)
        if (pos > 0) {
            timelineCoordinator.setPendingRestore(session.songId, pos)
            setPositionMsClamped(pos)
            val durSec = currentSong?.durationSec ?: 0
            if (durSec > 0) timelineCoordinator.updatePlayerDuration(durSec * 1000L)
        }
    }

    private fun preserveSongIdForQueue(): String? = currentSong?.id

    /** 曲库与 [restoreSession] 就绪后再次对齐索引，避免 [onConnected] 与恢复竞态。 */
    @Deprecated("Use bootstrapQueue instead")
    internal fun reconcileRestoredSessionIndex() {
        timelineCoordinator.pendingRestorePosition()?.let(::setPositionMsClamped)
    }

    private fun clearPendingSeek(reason: String? = null) {
        timelineCoordinator.clearPendingSeek(reason)?.let(::logPendingSeekCleared)
        publishProgressState()
    }

    private fun logPendingSeekCleared(cleared: ClearedPendingSeek) {
        DiagnosticLog.event(
            "Player",
            "pending-seek-clear reason=${cleared.reason} pendingMs=${cleared.pendingMs} " +
                "positionMs=${cleared.positionMs}",
        )
    }

    private var isBuffering by mutableStateOf(false)

    private var playbackError by mutableStateOf<String?>(null)

    var userMessage by mutableStateOf<UserMessage?>(null)
        private set

    private val songQueue: List<Song>
        get() = queueCoordinator.queue

    private var playbackQueueMode by mutableStateOf(PlaybackQueueMode.OFF)

    private val playbackTuning: PlaybackTuning
        get() = tuningCoordinator.requested

    private val currentSong: Song?
        get() = songQueue.getOrNull(currentIndex.coerceIn(0, (songQueue.size - 1).coerceAtLeast(0)))

    var playbackSurfaceState by mutableStateOf(PlaybackSurfaceState())
        private set

    var playbackProgressState by mutableStateOf(PlaybackProgressState())
        private set

    var playbackQueueState by mutableStateOf(PlaybackQueueState())
        private set

    private fun publishPlaybackStates() {
        publishSurfaceState()
        publishProgressState()
        publishQueueState()
    }

    private fun publishSurfaceState() {
        playbackSurfaceState = PlaybackSurfaceState(
            currentSong = currentSong,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            playbackError = playbackError,
            playbackQueueMode = playbackQueueMode,
            playbackTuning = playbackTuning,
            currentIndex = currentIndex,
        )
    }

    private fun publishProgressState() {
        playbackProgressState = PlaybackProgressState(
            positionMs = uiPositionMs(),
            durationMs = uiDurationMs(),
            pendingSeekMs = pendingSeekMs,
        )
    }

    private fun publishQueueState() {
        val nextState = PlaybackQueueState(
            queue = songQueue,
            currentIndex = currentIndex,
        )
        if (nextState != playbackQueueState) {
            playbackQueueState = nextState
        }
    }

    private fun applyPlaybackOrderState(
        order: PlaybackOrderState,
        candidates: List<Song> = songQueue,
    ) {
        installQueueModel(queueModel().applyOrder(order, candidates))
    }

    private fun resetPlaybackOrderFromQueue(
        queue: List<Song>,
        preserveSongId: String?,
    ): List<Song> {
        installQueueModel(queueModel().resetFromQueue(queue, preserveSongId))
        return songQueue
    }

    private val connectionSession = PlaybackConnectionSession(
        connector = mediaControllerConnector,
        listenerFactory = ::createPlayerListener,
        onConnected = ::onConnected,
        onDisconnected = ::onControllerDisconnected,
        onFailure = { postUserMessage("无法连接播放服务，请稍后重试") },
        onPlaybackBoundary = ::onConfirmedPlaybackBoundary,
    )
    private val controller: MediaController?
        get() = connectionSession.controller

    internal val isConnected: Boolean
        get() = connectionSession.isConnected

    /** Prevents callbacks from the previously playing item from undoing an optimistic selection. */
    private val pendingMediaSelection = PendingMediaSelection()
    private var pendingQueue: List<Song>? = null
    private var pendingSingleSongId: String? = null
    private val playbackStatistics = PlaybackStatisticsTracker(
        monotonicNowMs = monotonicNowMs,
        onListenSecondsAdded = { songId, seconds ->
            onSongListenSecondsAdded?.invoke(songId, seconds)
        },
    )
    private var lastSessionPersistMs: Long = 0L

    private fun releasePendingRestorePosition(songId: String?) {
        timelineCoordinator.releasePendingRestore(songId)
    }

    fun connectIfNeeded() = connectionSession.connectIfNeeded()

    fun retryConnect() = connectionSession.retry()

    private fun onConfirmedPlaybackBoundary(boundary: ConfirmedPlaybackBoundary) {
        val armed = playbackStatistics.onConfirmedAutomaticBoundary(
            PlaybackPositionDiscontinuity(
                oldSongId = boundary.oldSongId,
                newSongId = boundary.newSongId,
                oldPositionMs = boundary.oldPositionMs,
                newPositionMs = boundary.newPositionMs,
                automatic = true,
            ),
        )
        logPlayCountProbe(
            "service-boundary arm=$armed oldSong=${boundary.oldSongId.shortSongIdOrNone()} " +
                "newSong=${boundary.newSongId.shortSongIdOrNone()} " +
                "oldPositionMs=${boundary.oldPositionMs} newPositionMs=${boundary.newPositionMs}",
        )
        controller?.let { publishPlayCountIfStarted(it, it.isPlaying) }
    }

    private fun createPlayerListener(
        c: MediaController,
        isCurrentConnection: () -> Boolean,
    ): Player.Listener =
        object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (!isCurrentConnection()) return
                if (c.mediaItemCount <= 0) return
                val mirrorAligned = isQueueMirrorAligned(c)
                logPlayCountProbe(
                    "timeline reason=${timelineChangeReasonForLog(reason)} " +
                        "playerSong=${c.currentMediaItem?.mediaId.shortSongIdOrNone()} " +
                        "current=${currentSong?.id.shortSongIdOrNone()} " +
                        "playerIndex=${c.currentMediaItemIndex} playerItems=${c.mediaItemCount} " +
                        "queueSize=${songQueue.size} mirrorAligned=$mirrorAligned " +
                        "pending=${playbackStatistics.pendingSongId?.shortSongId() ?: "none"}",
                )
                if (!mirrorAligned) {
                    scheduleQueueMirrorFromPlayer(c)
                    return
                }
                val signature = queueCoordinator.orderSignature(c)
                if (!queueCoordinator.hasSignature(signature)) {
                    scheduleQueueMirrorFromPlayer(c)
                } else {
                    syncQueueIndexFromPlayer(c)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (!isCurrentConnection()) return
                val previousSongId = currentSong?.id
                val previousStatsSongId = playbackStatistics.statisticsSongId
                val transitionSongId = mediaItem?.mediaId
                val pendingBefore = playbackStatistics.pendingSongId
                if (!syncIndexFromPlayer(c)) {
                    logPlayCountProbe(
                        "transition-skip reason=${mediaTransitionReasonForLog(reason)} " +
                            "transitionSong=${transitionSongId.shortSongIdOrNone()} " +
                            "playerSong=${c.currentMediaItem?.mediaId.shortSongIdOrNone()} " +
                            "previous=${previousSongId.shortSongIdOrNone()} " +
                            "pendingBefore=${pendingBefore.shortSongIdOrNone()} " +
                            "cause=sync-index-rejected",
                    )
                    return
                }
                playbackStatistics.observePlayback(c.currentMediaItem?.mediaId, c.isPlaying)
                val newSongId = currentSong?.id
                val currentSongChanged = previousSongId != newSongId
                val shouldResetPosition =
                    reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK &&
                        reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                        (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED ||
                            currentSongChanged)
                if (shouldResetPosition) {
                    clearPendingSeek()
                    setPositionMsClamped(0)
                }
                val unsupportedMessage = mediaItem
                    ?.let(SongMediaItemCodec::decode)
                    ?.let(PlaybackRouter::unsupportedMessage)
                if (unsupportedMessage != null) {
                    if (playbackError != unsupportedMessage) postUserMessage(unsupportedMessage)
                    playbackError = unsupportedMessage
                } else {
                    playbackError = null
                }
                playbackStatistics.onTransition(
                    transitionSongId,
                    reason.toPlaybackMediaTransition(),
                )
                logPlayCountProbe(
                    "transition reason=${mediaTransitionReasonForLog(reason)} " +
                        "transitionSong=${transitionSongId.shortSongIdOrNone()} " +
                        "playerSong=${c.currentMediaItem?.mediaId.shortSongIdOrNone()} " +
                        "previous=${previousSongId.shortSongIdOrNone()} " +
                        "new=${newSongId.shortSongIdOrNone()} changed=$currentSongChanged " +
                        "statsPrevious=${previousStatsSongId.shortSongIdOrNone()} " +
                        "reset=$shouldResetPosition " +
                        "isPlaying=${c.isPlaying} playerIndex=${c.currentMediaItemIndex} " +
                        "playerItems=${c.mediaItemCount} queueIndex=$currentIndex queueSize=${songQueue.size} " +
                        "pendingBefore=${pendingBefore.shortSongIdOrNone()} " +
                        "pendingAfter=${playbackStatistics.pendingSongId.shortSongIdOrNone()}",
                )
                timelineCoordinator.updatePlayerDuration(c.duration)
                syncEffectivePlaybackTuning(reason = "transition")
                publishPlaybackStates()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!isCurrentConnection()) return
                if (!pendingMediaSelection.accepts(c.currentMediaItem?.mediaId)) return
                playbackStatistics.observePlayback(c.currentMediaItem?.mediaId, false)
                playbackStatistics.clearRequestAndPending()
                val song = c.currentMediaItem?.let { SongMediaItemCodec.decode(it) }
                val presentation = song
                    ?.let(PlaybackRouter::unsupportedMessage)
                    ?.let { PlaybackErrorPresentation(it, it) }
                    ?: PlaybackErrorMapper.toPresentation(error, song?.title)
                DiagnosticLog.event(
                    "Player",
                    "playback-error code=${error.errorCode} song=${song?.id ?: "unknown"} " +
                        "message=${error.message ?: "none"}",
                    error,
                )
                clearPendingMediaSelection()
                isBuffering = false
                playbackError = presentation.inlineMessage
                presentation.snackbarMessage?.let(::postUserMessage)
                syncIndexFromPlayer(c)
                publishPlaybackStates()
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                if (!isCurrentConnection()) return
                playbackStatistics.observePlayback(c.currentMediaItem?.mediaId, playing)
                isPlaying = playing
                if (playbackStatistics.pendingSongId != null) {
                    logPlayCountProbe(
                        "is-playing-changed playing=$playing " +
                            "playerSong=${c.currentMediaItem?.mediaId.shortSongIdOrNone()} " +
                            "pending=${playbackStatistics.pendingSongId.shortSongIdOrNone()}",
                    )
                }
                if (playing) {
                    releasePendingRestorePosition(c.currentMediaItem?.mediaId)
                    syncPosition()
                    publishPlayCountIfStarted(c, true)
                }
                publishSurfaceState()
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (!isCurrentConnection()) return
                syncPlaybackState()
                val armed = playbackStatistics.finishEventBatch()
                if (armed || playbackStatistics.pendingSongId != null) {
                    logPlayCountProbe(
                        "event-batch arm=$armed " +
                            "playerSong=${c.currentMediaItem?.mediaId.shortSongIdOrNone()} " +
                            "pending=${playbackStatistics.pendingSongId.shortSongIdOrNone()} " +
                            "isPlaying=${c.isPlaying}",
                    )
                }
                publishPlayCountIfStarted(c, c.isPlaying)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (!isCurrentConnection()) return
                if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                    playbackStatistics.observePlayback(c.currentMediaItem?.mediaId, false)
                }
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY && c.duration > 0) {
                    timelineCoordinator.updatePlayerDuration(c.duration)
                }
                if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                    syncIndexFromPlayer(c)
                }
                publishPlaybackStates()
            }

            @UnstableApi
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (!isCurrentConnection()) return
                val automatic = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                if (automatic ||
                    reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                ) {
                    val oldSongId = oldPosition.mediaItem?.mediaId
                    val newSongId = newPosition.mediaItem?.mediaId ?: c.currentMediaItem?.mediaId
                    playbackStatistics.onPositionDiscontinuity(
                        PlaybackPositionDiscontinuity(
                            oldSongId = oldSongId,
                            newSongId = newSongId,
                            oldPositionMs = oldPosition.positionMs,
                            newPositionMs = newPosition.positionMs,
                            automatic = automatic,
                        ),
                    )
                }
                if (automatic) {
                    val songId = newPosition.mediaItem?.mediaId ?: c.currentMediaItem?.mediaId
                    logPlayCountProbe(
                        "playback-boundary reason=auto-transition " +
                            "song=${songId.shortSongIdOrNone()} " +
                            "oldPositionMs=${oldPosition.positionMs} " +
                            "newPositionMs=${newPosition.positionMs} isPlaying=${c.isPlaying}",
                    )
                } else if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                ) {
                    clearPendingSeek("discontinuity")
                    notifyPlaybackProgress(newPosition.positionMs.toInt().coerceAtLeast(0))
                }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                if (!isCurrentConnection()) return
                syncPlaybackQueueModeFromPlayer(c)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (!isCurrentConnection()) return
                syncPlaybackQueueModeFromPlayer(c)
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                if (!isCurrentConnection()) return
                val reported = PlaybackTuning.fromPlaybackParameters(playbackParameters)
                tuningCoordinator.onPlaybackParametersChanged(
                    reported = reported,
                    tuningAvailable = playbackTuningAvailableFor(currentSong),
                )
                publishSurfaceState()
            }
        }

    private fun onConnected(c: MediaController) {

        pendingQueue?.let {
            applyQueue(c, it, preservePlayback = true)
            pendingQueue = null
        }
        pendingSingleSongId?.let { songId ->
            pendingSingleSongId = null
            playSongById(songId)
        }
        syncPlaybackQueueModeFromPlayer(c)
        syncIndexFromPlayer(c)
        val playerTuning = PlaybackTuning.fromPlaybackParameters(c.playbackParameters)
        tuningCoordinator.onConnected(
            reported = playerTuning,
            tuningAvailable = playbackTuningAvailableFor(currentSong),
        )?.let { target ->
            c.setPlaybackParameters(target.toPlaybackParameters())
        }

        playbackStatistics.reset(c.currentMediaItem?.mediaId)
        isPlaying = c.isPlaying
        playbackStatistics.observePlayback(c.currentMediaItem?.mediaId, c.isPlaying)
        timelineCoordinator.updatePlayerDuration(c.duration)
        syncPosition()
        publishPlaybackStates()
    }

    private fun onControllerDisconnected(disconnectedController: MediaController?) {
        playbackStatistics.observePlayback(disconnectedController?.currentMediaItem?.mediaId, false)
        queueCoordinator.clearMirror()
        pendingMediaSelection.clear()
        playbackStatistics.reset(null)
        PendingPlaybackNavigation.clear()
        publishPlaybackStates()
    }

    private fun publishPlayCountIfStarted(player: Player, playing: Boolean) {
        val pendingSongId = playbackStatistics.pendingSongId ?: return
        val playerSongId = player.currentMediaItem?.mediaId
        if (!playing) {
            logPlayCountProbe(
                "publish-skip reason=not-playing playerSong=${playerSongId.shortSongIdOrNone()} " +
                    "pending=${pendingSongId.shortSongId()}",
            )
            return
        }
        if (pendingSongId != playerSongId) {
            logPlayCountProbe(
                "publish-skip reason=song-mismatch playerSong=${playerSongId.shortSongIdOrNone()} " +
                    "pending=${pendingSongId.shortSongId()}",
            )
            return
        }
        val publishedSongId = playbackStatistics.publishPlayStartedIfReady(playerSongId, playing)
            ?: return
        logPlayCountProbe(
            "publish-consume song=${publishedSongId.shortSongId()} " +
                "playerIndex=${player.currentMediaItemIndex} playerItems=${player.mediaItemCount}",
        )
        onSongPlayStarted?.invoke(publishedSongId)
    }

    private fun syncIndexFromPlayer(c: MediaController): Boolean {
        if (songQueue.isEmpty()) {
            queueCoordinator.replaceCurrentIndex(0)
            publishPlaybackStates()
            return true
        }
        val mediaId = c.currentMediaItem?.mediaId
        if (!pendingMediaSelection.shouldAccept(mediaId)) return false
        val mediaIdIndex = mediaId?.let { id ->
            songQueue.indexOfFirst { it.id == id }.takeIf { it >= 0 }
        }
        val idx = c.currentMediaItemIndex
        val nextIndex = when {
            mediaIdIndex != null -> mediaIdIndex
            idx in songQueue.indices -> idx
            else -> currentIndex.coerceIn(0, songQueue.lastIndex)
        }
        queueCoordinator.replaceCurrentIndex(nextIndex)
        songQueue.getOrNull(nextIndex)?.id?.let { id ->
            queueCoordinator.replaceOrder(playbackOrderState.moveTo(id))
        }
        publishPlaybackStates()
        return true
    }

    /** 从服务侧权威队列镜像到 UI 状态（[songQueue] / [currentIndex]）。 */
    private fun syncQueueMirrorFromPlayer(
        c: Player,
        resolver: (String) -> Song? = songResolver::resolve,
    ) {
        if (c.mediaItemCount <= 0) return
        val mirrorStartedNs = SystemClock.elapsedRealtimeNanos()
        val result = queueCoordinator.rebuildMirrorNow(
            player = c,
            resolver = resolver,
            onApplied = ::publishPlaybackStates,
        )
        if (result.applied && c is MediaController) {
            syncIndexFromPlayer(c)
        }
        logQueueSyncMs(
            action = "mirror-rebuild",
            mirrorStartedNs,
            "playerItems=${result.itemsCount} resolved=${result.resolvedCount} mode=immediate",
        )
    }

    private fun scheduleQueueMirrorFromPlayer(c: MediaController) {
        queueCoordinator.scheduleMirror(
            player = c,
            isCurrentPlayer = { controller === c },
            fallbackResolver = { songResolver::resolve },
            onApplied = ::publishPlaybackStates,
            syncIndex = { syncIndexFromPlayer(c) },
            log = ::logQueueSyncMs,
        )
    }

    /** seek 等未改 playlist 的 timeline 变化：仅同步当前索引，避免扫全队列。 */
    private fun syncQueueIndexFromPlayer(c: MediaController) {
        val startedNs = SystemClock.elapsedRealtimeNanos()
        syncIndexFromPlayer(c)
        logQueueSyncMs(
            action = "mirror-index-sync",
            startedNs,
            "playerItems=${c.mediaItemCount} items=${songQueue.size} index=$currentIndex",
        )
    }

    private fun isQueueMirrorAligned(c: Player): Boolean {
        if (songQueue.isEmpty() || c.mediaItemCount != songQueue.size) return false
        val mediaId = c.currentMediaItem?.mediaId ?: return false
        if (songQueue.none { it.id == mediaId }) return false
        val playerIndex = c.currentMediaItemIndex
        if (playerIndex !in songQueue.indices) return false
        return runCatching { c.getMediaItemAt(playerIndex).mediaId == mediaId }.getOrDefault(false)
    }

    /**
     * 冷启动：优先用 [ServicePlaybackStateStore] 持久化的 songId 顺序 hydrate 队列（service_wins）。
     * 若服务已有队列或服务侧无持久化记录，则仅镜像；返回 false 表示调用方应装入全曲库。
     */
    fun bootstrapQueue(resolveSong: (String) -> Song?): Boolean {
        val c = controller
        if (c != null && c.mediaItemCount > 0) {
            syncQueueMirrorFromPlayer(c, resolver = resolveSong)
            return true
        }
        val snapshot = ServicePlaybackStateStore(appCtx).load() ?: return false
        val session = sessionStorage.load()
        val persistedExternalSongs = snapshot.externalSongs.associateBy { it.id }
        val hydrated = snapshot.queueSongIds.mapNotNull { id ->
            songResolver.resolve(id) ?: resolveSong(id) ?: persistedExternalSongs[id]?.toSong()
        }
        if (hydrated.isEmpty()) return false
        val preserveId = snapshot.currentSongId.ifBlank {
            snapshot.queueSongIds.getOrNull(snapshot.currentIndex).orEmpty()
        }
        queueCoordinator.replaceOrder(
            PlaybackOrderState(
                sourceIds = hydrated.map { it.id },
                playbackIds = hydrated.map { it.id },
                currentId = preserveId.takeIf { id -> hydrated.any { it.id == id } },
                shuffleEnabled = session?.shuffleEnabled == true,
            ),
        )
        setQueue(hydrated)
        val index = songQueue.indexOfFirst { it.id == preserveId }.takeIf { it >= 0 }
            ?: snapshot.currentIndex.coerceIn(0, songQueue.lastIndex)
        queueCoordinator.replaceCurrentIndex(index)
        if (snapshot.positionMs > 0) {
            timelineCoordinator.setPendingRestore(
                songId = songQueue[index].id,
                positionMs = snapshot.positionMs.toInt(),
            )
            setPositionMsClamped(snapshot.positionMs.toInt())
        }
        publishPlaybackStates()
        controller?.let { active ->
            syncQueueToService(
                c = active,
                targetIndex = index,
                positionMs = snapshot.positionMs,
                preserveCurrentPlayback = false,
            )
            active.seekTo(index, snapshot.positionMs)
        }
        return true
    }

    fun syncPlaybackState() {
        val c = controller ?: return
        if (!syncIndexFromPlayer(c)) return
        syncEffectivePlaybackTuning(reason = "sync-state")
        syncPosition()
        playbackStatistics.observePlayback(c.currentMediaItem?.mediaId, c.isPlaying)
        isPlaying = c.isPlaying
        isBuffering = c.playbackState == Player.STATE_BUFFERING
        timelineCoordinator.updatePlayerDuration(c.duration)
        publishPlaybackStates()
    }

    fun setQueue(newQueue: List<Song>) {
        if (newQueue.isEmpty() && songQueue.isEmpty()) return
        pendingSingleSongId?.let { pendingId ->
            if (newQueue.none { it.id == pendingId }) pendingSingleSongId = null
        }

        val startedMs = SystemClock.elapsedRealtime()
        val previousQueueSize = songQueue.size
        val preserveId = preserveSongIdForQueue()
        val sameOrderAndIds = songQueue.isNotEmpty() &&
            newQueue.size == songQueue.size &&
            newQueue.indices.all { i -> newQueue[i].id == songQueue[i].id }

        val playbackUnchanged = sameOrderAndIds && newQueue.indices.all { i ->
            val old = songQueue[i]
            val neu = newQueue[i]
            old.mediaUri == neu.mediaUri &&
                old.playbackUri == neu.playbackUri &&
                old.metadata.playbackMimeType == neu.metadata.playbackMimeType
        }

        if (playbackUnchanged) {
            val metadataDiff = summarizePlaybackUnchangedQueueDiff(songQueue, newQueue, currentIndex)
            if (songQueue != newQueue) {
                commitSongQueue(newQueue)
                preserveId?.let { queueCoordinator.replaceOrder(playbackOrderState.moveTo(it)) }
                publishPlaybackStates()
            }
            controller?.let { c ->
                if (c.mediaItemCount > 0) {
                    syncQueueToService(
                        c = c,
                        targetIndex = currentIndex,
                        positionMs = runCatching { c.currentPosition }.getOrDefault(0L),
                        preserveCurrentPlayback = true,
                    )
                }
            }
            controller?.let { syncPlaybackState() }
            DiagnosticLog.event(
                "LibraryQueue",
                "setQueue playbackUnchanged durMs=${SystemClock.elapsedRealtime() - startedMs} " +
                    "previous=$previousQueueSize new=${newQueue.size} controllerItems=${controller?.mediaItemCount ?: 0} " +
                    metadataDiff,
            )
            return
        }

        val previousIndex = currentIndex
        val orderedQueue = resetPlaybackOrderFromQueue(newQueue, preserveId)
        publishPlaybackStates()

        if (sameOrderAndIds) {
            controller?.let {
                applyQueue(it, orderedQueue, preservePlayback = true, preserveSongId = preserveId)
            }
            DiagnosticLog.event(
                "LibraryQueue",
                "setQueue sameOrderApply durMs=${SystemClock.elapsedRealtime() - startedMs} " +
                    "previous=$previousQueueSize new=${newQueue.size} controllerItems=${controller?.mediaItemCount ?: 0}",
            )
            return
        }
        val c = controller
        if (c == null) {
            pendingQueue = orderedQueue
            if (orderedQueue.isEmpty()) {
                queueCoordinator.replaceCurrentIndex(0)
            } else {
                applyPreserveIndexForQueue(orderedQueue, preserveId, previousIndex)
            }
            publishPlaybackStates()
            DiagnosticLog.event(
                "LibraryQueue",
                "setQueue pendingController durMs=${SystemClock.elapsedRealtime() - startedMs} " +
                    "previous=$previousQueueSize new=${newQueue.size}",
            )
            return
        }
        applyQueue(c, orderedQueue, preservePlayback = true, preserveSongId = preserveId)
        DiagnosticLog.event(
            "LibraryQueue",
            "setQueue applyQueue durMs=${SystemClock.elapsedRealtime() - startedMs} " +
                "previous=$previousQueueSize new=${newQueue.size} controllerItems=${c.mediaItemCount}",
        )
    }

    fun refreshQueueMetadata(latestSongs: List<Song>) {
        val startedMs = SystemClock.elapsedRealtime()
        if (songQueue.isEmpty() || latestSongs.isEmpty()) {
            DiagnosticLog.event(
                "LibraryQueue",
                "refreshQueueMetadata skipped durMs=${SystemClock.elapsedRealtime() - startedMs} " +
                    "queue=${songQueue.size} latest=${latestSongs.size}",
            )
            return
        }
        val latestById = latestSongs.associateBy { it.id }
        val refreshed = songQueue.map { queued -> latestById[queued.id] ?: queued }
        if (refreshed == songQueue) {
            DiagnosticLog.event(
                "LibraryQueue",
                "refreshQueueMetadata unchanged durMs=${SystemClock.elapsedRealtime() - startedMs} " +
                    "queue=${songQueue.size} latest=${latestSongs.size}",
            )
            return
        }
        DiagnosticLog.event(
            "LibraryQueue",
            "refreshQueueMetadata changed durMs=${SystemClock.elapsedRealtime() - startedMs} " +
                "queue=${songQueue.size} latest=${latestSongs.size}",
        )
        setQueue(refreshed)
    }

    private fun applyPreserveIndexForQueue(
        newQueue: List<Song>,
        preserveSongId: String?,
        fallbackIndex: Int = currentIndex,
    ) {
        if (newQueue.isEmpty()) {
            queueCoordinator.replaceCurrentIndex(0)
            publishPlaybackStates()
            return
        }
        installQueueModel(
            queueModel().preserveIndexForQueue(
                nextQueue = newQueue,
                preserveSongId = preserveSongId,
                fallbackIndex = fallbackIndex,
            ),
        )
        publishPlaybackStates()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun applyQueue(
        c: MediaController,
        newQueue: List<Song>,
        preservePlayback: Boolean = false,
        preserveSongId: String? = preserveSongIdForQueue(),
    ) {
        if (newQueue.isEmpty()) {
            c.clearMediaItems()
            installQueueModel(queueModel().linearQueue(emptyList(), 0))
            isPlaying = false
            playbackError = null
            sessionStorage.clear()
            publishPlaybackStates()
            return
        }

        val playingId = preserveSongId
        val keepIndex = playingId?.let { id -> newQueue.indexOfFirst { it.id == id } } ?: -1
        val foundOldSong = preservePlayback && keepIndex >= 0
        queueCoordinator.replaceCurrentIndex(if (foundOldSong) keepIndex else 0)
        publishPlaybackStates()
        if (wasPlayingBeforeQueueChange(c) && !foundOldSong) {
            c.setMediaItems(
                newQueue.map { it.toMediaItem() },
                currentIndex,
                0L,
            )
            c.play()
            postUserMessage("当前歌曲已从库中移除")
        } else {
            if (c.mediaItemCount == 0) {
                c.setMediaItems(
                    newQueue.map { it.toMediaItem() },
                    currentIndex,
                    0L,
                )
            } else {
                syncExoQueuePreservingPlayback()
            }
        }
    }

    private fun wasPlayingBeforeQueueChange(c: MediaController): Boolean = c.isPlaying

    fun syncPosition() {
        timelineCoordinator.restorePositionForSync(currentSong?.id)?.let { positionMs ->
            setPositionMsClamped(positionMs)
            return
        }
        val c = controller ?: return
        timelineCoordinator.updatePlayerDuration(c.duration)
        val expectedSongId = currentSong?.id
        val controllerSongId = c.currentMediaItem?.mediaId
        if (expectedSongId != null &&
            controllerSongId != null &&
            controllerSongId != expectedSongId
        ) {
            DiagnosticLog.event(
                "Player",
                "position-sync-skipped staleController current=$expectedSongId " +
                    "controller=$controllerSongId rawMs=${c.currentPosition.coerceAtLeast(0L)}",
            )
            publishProgressState()
            return
        }
        notifyPlaybackProgress(c.currentPosition.toInt().coerceAtLeast(0))
        playbackStatistics.observePlayback(c.currentMediaItem?.mediaId, c.isPlaying)
        isPlaying = c.isPlaying
        isBuffering = c.playbackState == Player.STATE_BUFFERING
        publishSurfaceState()
    }

    fun pauseIfPlaying() {
        if (!isPlaying) return
        controller?.pause()
    }

    fun setPlaybackVolume(volume: Float) {
        controller?.volume = volume.coerceIn(0f, 1f)
    }

    /** Current app-level playback gain, excluding the service-side ReplayGain multiplier. */
    val playbackVolume: Float
        get() = controller?.volume ?: 1f

    fun setPlaybackSpeed(speed: Float) {
        applyPlaybackTuning(playbackTuning.withSpeed(speed))
    }

    fun setPlaybackPitchSemitones(semitones: Float) {
        applyPlaybackTuning(playbackTuning.withPitchSemitones(semitones))
    }

    fun resetPlaybackTuning() {
        applyPlaybackTuning(PlaybackTuning())
    }

    private fun applyPlaybackTuning(tuning: PlaybackTuning) {
        tuningCoordinator.request(tuning)
        syncEffectivePlaybackTuning(reason = "user-request", force = true)
        publishSurfaceState()
    }

    private fun syncEffectivePlaybackTuning(reason: String, force: Boolean = false) {
        val effective = tuningCoordinator.targetForSync(
            tuningAvailable = playbackTuningAvailableFor(currentSong),
            force = force,
        ) ?: return
        val c = controller ?: run {
            connectIfNeeded()
            return
        }
        tuningCoordinator.markApplyIssued(effective)
        c.setPlaybackParameters(effective.toPlaybackParameters())
        if (!tuningCoordinator.matchesRequested(effective)) {
            DiagnosticLog.event(
                "PlaybackTuning",
                "effective-default reason=$reason requestedSpeed=${playbackTuning.speed} " +
                    "requestedPitch=${playbackTuning.pitchMultiplier} song=${currentSong?.id}",
            )
        }
    }

    private fun playbackTuningAvailableFor(song: Song?): Boolean =
        song?.let { !DsdSupport.isDsdSong(it) } ?: true

    fun togglePlay() {
        if (songQueue.isEmpty()) return
        val c = controller
        if (c == null) {
            connectIfNeeded()
            postUserMessage("播放服务未就绪")
            return
        }
        playbackError = null
        if (isPlaying) {
            c.pause()
            return
        }
        if (c.mediaItemCount > 0 && c.currentMediaItem?.mediaId == currentSong?.id) {
            releasePendingRestorePosition(currentSong?.id)
            c.play()
        } else {
            playSong(currentIndex)
        }
    }

    fun playSongById(songId: String) {
        val index = songQueue.indexOfFirst { it.id == songId }
        if (index >= 0) playSong(index)
    }

    /**
     * Replaces the active queue with one externally opened song and starts it once the
     * MediaController is available. This preserves the user's explicit play request across a
     * cold service connection without adding the song to the library.
     */
    fun playSingleSong(song: Song) {
        setQueue(listOf(song))
        pendingSingleSongId = song.id
        if (controller == null) {
            connectIfNeeded()
            return
        }
        pendingSingleSongId = null
        playSongById(song.id)
    }

    /**
     * 将曲目插入当前播放位置之后（下一首播放）。
     *
     * Exo 单链路：通过 MediaController 更新播放队列。
     */
    fun insertPlayNext(song: Song) {
        if (songQueue.isEmpty()) {
            setQueue(listOf(song))
            playSong(0)
            postUserMessage("已加入下一首播放")
            return
        }

        if (songQueue.getOrNull(currentIndex)?.id == song.id) {
            postUserMessage("正在播放该歌曲")
            return
        }
        installQueueModel(queueModel().insertPlayNext(song))
        val updatedList = songQueue
        val updatedIndex = currentIndex
        val insertedAt = updatedList.indexOfFirst { it.id == song.id }
        val active = controller
        when {
            active == null -> pendingQueue = updatedList
            else -> syncQueueToService(
                c = active,
                targetIndex = updatedIndex,
                positionMs = active.currentPosition.coerceAtLeast(0L),
                preserveCurrentPlayback = true,
            )
        }
        DiagnosticLog.event(
            "Player",
            "insertPlayNext song=${song.id} insertAt=$insertedAt playIndex=$updatedIndex; ${playbackSnapshot()}",
        )
        publishPlaybackStates()
        postUserMessage("已加入下一首播放")
    }

    fun moveInQueue(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in songQueue.indices || toIndex !in songQueue.indices) return
        if (fromIndex == toIndex) return
        val queueBeforeMove = songQueue
        val activeController = controller
        val canMoveIncrementally = activeController?.let { c ->
            MediaControllerQueueSync.canMoveItemIncrementally(c, queueBeforeMove, fromIndex, toIndex)
        } == true
        installQueueModel(queueModel().move(fromIndex, toIndex))
        val list = songQueue
        val newCurrent = currentIndex
        activeController?.let { c ->
            if (canMoveIncrementally) {
                val moveStartedNs = SystemClock.elapsedRealtimeNanos()
                c.moveMediaItem(fromIndex, toIndex)
                logQueueSyncMs(
                    action = "controller-moveMediaItem",
                    startedNs = moveStartedNs,
                    details = "from=$fromIndex to=$toIndex items=${list.size}",
                )
            } else {
                syncQueueToService(
                    c = c,
                    targetIndex = newCurrent,
                    positionMs = c.currentPosition.coerceAtLeast(0L),
                    preserveCurrentPlayback = true,
                )
            }
        } ?: run {
            pendingQueue = list
        }
        publishPlaybackStates()
    }

    fun removeFromQueue(index: Int) {
        if (index !in songQueue.indices) return
        val removingCurrent = index == currentIndex
        val wasPlaying = isPlaying
        val updated = queueModel().removeAt(index)
        val list = updated.queue
        if (list.isEmpty()) {
            installQueueModel(updated)
            isPlaying = false
            playbackError = null
            controller?.clearMediaItems()
            publishPlaybackStates()
            return
        }
        val newIndex = updated.currentIndex
        applyQueueOrder(list, newIndex)
        if (removingCurrent) {
            if (wasPlaying) {
                playSong(newIndex, anchorPlaybackOrder = false)
            } else {
                queueCoordinator.replaceCurrentIndex(newIndex)
                publishPlaybackStates()
            }
        }
    }

    /** 更新内存队列；经 [MediaController] 同步服务侧 Exo 播放列表。 */
    private fun applyQueueOrder(list: List<Song>, newIndex: Int) {
        installQueueModel(queueModel().linearQueue(list, newIndex))
        publishPlaybackStates()
        if (controller == null) {
            pendingQueue = list
            return
        }
        syncExoQueuePreservingPlayback()
    }

    fun playSong(index: Int) = playSong(index, anchorPlaybackOrder = false)

    private fun playSong(index: Int, anchorPlaybackOrder: Boolean) {
        if (songQueue.isEmpty()) return
        val c = controller ?: run {
            connectIfNeeded()
            postUserMessage("播放服务未就绪")
            return
        }
        var safe = index.coerceIn(0, songQueue.lastIndex)
        val previousIndex = currentIndex
        playbackError = null
        var song = songQueue[safe]
        queueCoordinator.replaceOrder(playbackOrderState.moveTo(song.id))
        PlaybackRouter.unsupportedMessage(song)?.let { message ->
            playbackError = message
            postUserMessage(message)
        }
        if (safe != previousIndex) {
            TrackSwitchPerformance.begin(
                fromIndex = previousIndex,
                toIndex = safe,
                songId = song.id,
                queueSize = songQueue.size,
            )
        }
        DiagnosticLog.event(
            "Player",
            "playSong requested=$index resolved=$safe; song=${song.id} ${song.title}; " +
                "format=${song.metadata.formatLabel}; path=${song.filePath}; ${playbackSnapshot()}",
        )
        val requestedStartMs = timelineCoordinator.consumeRestoreStartPosition(song.id)
        queueCoordinator.replaceCurrentIndex(safe)
        clearPendingSeek()
        if (safe != previousIndex) {
            resetDurationForSongChange(song)
        }
        setPositionMsClamped(requestedStartMs)
        syncEffectivePlaybackTuning(reason = "play-song")
        deferredPlaybackPublish?.let { mainHandler.removeCallbacks(it) }
        val publish = Runnable {
            deferredPlaybackPublish = null
            if (currentIndex == safe) publishPlaybackStates()
        }
        deferredPlaybackPublish = publish
        mainHandler.post(publish)
        playbackStatistics.requestPlayback(song.id)
        pendingMediaSelection.select(song.id)
        startControllerPlayback(c, safe, requestedStartMs, song.id)
    }

    private fun clearPendingMediaSelection() {
        pendingMediaSelection.clear()
        playbackStatistics.clearRequestAndPending()
    }

    private fun startControllerPlayback(
        expectedController: MediaController,
        index: Int,
        positionMs: Int,
        songId: String,
    ) {
        if (controller !== expectedController) {
            clearPendingMediaSelection()
            PendingPlaybackNavigation.clear()
            return
        }
        if (currentIndex != index || songQueue.getOrNull(index)?.id != songId) {
            clearPendingMediaSelection()
            PendingPlaybackNavigation.clear()
            return
        }
        val navigationPlan = PlaybackQueueNavigation.plan(
            queueIds = songQueue.map { it.id },
            requestedIndex = index,
            songId = songId,
            currentMediaId = expectedController.currentMediaItem?.mediaId,
            serviceItemCount = expectedController.mediaItemCount,
            serviceMediaIdAt = { serviceIndex ->
                runCatching { expectedController.getMediaItemAt(serviceIndex).mediaId }
                    .getOrNull()
            },
        ) ?: run {
            clearPendingMediaSelection()
            PendingPlaybackNavigation.clear()
            return
        }
        val serviceIndex = when (navigationPlan) {
            is PlaybackQueueNavigationPlan.SeekAligned -> {
                PendingPlaybackNavigation.clear()
                DiagnosticLog.event(
                    "QueueSync",
                    "controller-sync-skipped manual-nav items=${songQueue.size} " +
                        "serviceItems=${expectedController.mediaItemCount} " +
                        "target=${navigationPlan.serviceIndex}",
                )
                navigationPlan.serviceIndex
            }
            is PlaybackQueueNavigationPlan.CarryQueuePayload -> {
                val mapStartedNs = SystemClock.elapsedRealtimeNanos()
                val queueItems = songQueue.map { it.toMediaItem() }
                PendingPlaybackNavigation.prepare(targetSongId = songId, items = queueItems)
                logQueueSyncMs(
                    action = "play-switch-nav",
                    mapStartedNs,
                    "songId=$songId items=${queueItems.size} " +
                        "serviceItems=${expectedController.mediaItemCount}",
                )
                navigationPlan.serviceIndex
            }
            is PlaybackQueueNavigationPlan.SyncQueue -> {
                val queueItems = songQueue.map { it.toMediaItem() }
                syncQueueToService(
                    c = expectedController,
                    targetIndex = navigationPlan.serviceIndex,
                    positionMs = positionMs.toLong(),
                    preserveCurrentPlayback = true,
                    prebuiltItems = queueItems,
                )
                resolveControllerIndexForSongId(expectedController, songId) ?: navigationPlan.serviceIndex
            }
        }
        TrackSwitchPerformance.mark("audio-start", "index=$serviceIndex songId=$songId")
        expectedController.seekTo(serviceIndex, positionMs.toLong())
        expectedController.play()
    }

    private fun resolveControllerIndexForSongId(c: Player, songId: String): Int? {
        for (i in 0 until c.mediaItemCount) {
            if (runCatching { c.getMediaItemAt(i).mediaId }.getOrNull() == songId) return i
        }
        return null
    }

    /**
     * 将内存 [songQueue] 同步到服务侧权威播放列表。
     * 插播/重排时保持当前曲；显式 [playSong] 切歌时对齐到目标索引。
     */
    private fun syncQueueToService(
        c: Player,
        targetIndex: Int,
        positionMs: Long,
        preserveCurrentPlayback: Boolean,
        prebuiltItems: List<MediaItem>? = null,
    ) {
        val syncStartedNs = SystemClock.elapsedRealtimeNanos()
        val plan = MediaControllerQueueSync.planSync(
            player = c,
            queue = songQueue,
            targetIndex = targetIndex,
            positionMs = positionMs,
            preserveCurrentPlayback = preserveCurrentPlayback,
            prebuiltItems = prebuiltItems,
        ) ?: return
        val result = plan.result
        if (plan is PlaybackQueueSyncPlan.Skip) {
            DiagnosticLog.event(
                "QueueSync",
                "controller-sync-skipped items=${result.itemsCount} serviceItems=${c.mediaItemCount} " +
                    "target=${result.startIndex}",
            )
            return
        }
        MediaControllerQueueSync.executeSyncPlan(c, plan)
        logQueueSyncMs(
            action = if (plan is PlaybackQueueSyncPlan.ReplaceMediaItems) {
                "controller-replaceMediaItems"
            } else {
                "controller-setMediaItems"
            },
            syncStartedNs,
            "items=${result.itemsCount} startIndex=${result.startIndex} " +
                "preserve=${result.preserveCurrentPlayback} aligned=${result.queueAligned} " +
                "targetMismatch=${result.targetMismatch} reusedMap=${result.reusedMap}",
        )
    }

    private fun logQueueSyncMs(action: String, startedNs: Long, details: String) {
        DiagnosticLog.event("QueueSync", "$action durMs=${formatQueueSyncMs(startedNs)} $details")
    }

    private fun formatQueueSyncMs(startedNs: Long, endedNs: Long = SystemClock.elapsedRealtimeNanos()): String =
        String.format(
            java.util.Locale.US,
            "%.2f",
            (endedNs - startedNs) / 1_000_000.0,
        )

    private fun syncExoQueuePreservingPlayback() {
        val c = controller ?: return
        if (songQueue.isEmpty()) return
        if (c.mediaItemCount <= 0) return
        syncQueueToService(
            c = c,
            targetIndex = currentIndex,
            positionMs = 0L,
            preserveCurrentPlayback = true,
        )
        if (c.playWhenReady) c.play()
    }

    fun cyclePlaybackQueueMode() {
        val nextMode = playbackQueueMode.next()
        controller?.let { applyPlaybackQueueMode(it, nextMode) }
        // 本地 playbackQueueMode 由 onRepeatModeChanged / onShuffleModeEnabledChanged 镜像回写
    }

    private fun applyPlaybackQueueMode(c: MediaController, mode: PlaybackQueueMode = playbackQueueMode) {
        when (mode) {
            PlaybackQueueMode.OFF -> {
                setAppShuffleEnabled(false, c)
                c.shuffleModeEnabled = false
                c.repeatMode = Player.REPEAT_MODE_OFF
            }
            PlaybackQueueMode.REPEAT_ALL -> {
                setAppShuffleEnabled(false, c)
                c.shuffleModeEnabled = false
                c.repeatMode = Player.REPEAT_MODE_ALL
            }
            PlaybackQueueMode.REPEAT_ONE -> {
                setAppShuffleEnabled(false, c)
                c.shuffleModeEnabled = false
                c.repeatMode = Player.REPEAT_MODE_ONE
            }
            PlaybackQueueMode.SHUFFLE -> {
                setAppShuffleEnabled(true, c)
                c.shuffleModeEnabled = false
                c.repeatMode = Player.REPEAT_MODE_OFF
            }
        }
    }

    private fun setAppShuffleEnabled(enabled: Boolean, c: MediaController) {
        if (songQueue.isNotEmpty() && playbackOrderState.shuffleEnabled != enabled) {
            val order = playbackOrderState
                .withQueue(songQueue.map { it.id }, preserveSongIdForQueue())
                .setShuffleEnabled(enabled)
            applyPlaybackOrderState(order, songQueue)
            syncQueueToService(
                c = c,
                targetIndex = currentIndex,
                positionMs = runCatching { c.currentPosition }.getOrDefault(0L),
                preserveCurrentPlayback = true,
            )
        } else {
            queueCoordinator.replaceOrder(playbackOrderState.copy(shuffleEnabled = enabled))
        }
        if (enabled) {
            playbackQueueMode = PlaybackQueueMode.SHUFFLE
        } else if (playbackQueueMode == PlaybackQueueMode.SHUFFLE) {
            playbackQueueMode = PlaybackQueueMode.OFF
        }
        maybePersistPlaybackSession(force = true)
        publishPlaybackStates()
    }

    private fun syncPlaybackQueueModeFromPlayer(c: Player) {
        playbackQueueMode = when {
            playbackOrderState.shuffleEnabled -> PlaybackQueueMode.SHUFFLE
            c.repeatMode == Player.REPEAT_MODE_ALL -> PlaybackQueueMode.REPEAT_ALL
            c.repeatMode == Player.REPEAT_MODE_ONE -> PlaybackQueueMode.REPEAT_ONE
            else -> PlaybackQueueMode.OFF
        }
        publishSurfaceState()
    }

    private fun playNextAfterStream() {
        if (songQueue.isEmpty()) return
        val next = resolveNextIndex(forManualSkip = false)
        DiagnosticLog.event(
            "Player",
            "automatic next target=$next; ${playbackSnapshot()}",
        )
        if (playbackQueueMode == PlaybackQueueMode.OFF && next == currentIndex) return
        TrackSwitchPerformance.armTrigger("auto-next")
        trackSkipDirection = TrackSkipDirection.TO_NEXT
        playSong(next, anchorPlaybackOrder = false)
    }

    private fun resolveNextIndex(forManualSkip: Boolean): Int {
        val currentId = currentSong?.id ?: return currentIndex
        val nextId = playbackOrderState
            .moveTo(currentId)
            .nextId(
                manualSkip = forManualSkip,
                repeatAll = playbackQueueMode == PlaybackQueueMode.REPEAT_ALL,
                repeatOne = playbackQueueMode == PlaybackQueueMode.REPEAT_ONE,
            )
            ?: return currentIndex
        return songQueue.indexOfFirst { it.id == nextId }.takeIf { it >= 0 } ?: currentIndex
    }

    private fun resolvePreviousIndex(): Int {
        val currentId = currentSong?.id ?: return currentIndex
        val previousId = playbackOrderState
            .moveTo(currentId)
            .previousId(repeatAll = true)
            ?: return currentIndex
        return songQueue.indexOfFirst { it.id == previousId }.takeIf { it >= 0 } ?: currentIndex
    }



    fun manualNextTarget(): Int? {
        playbackError = null
        if (songQueue.isEmpty()) return null
        val target = resolveNextIndex(forManualSkip = true)
        DiagnosticLog.event("Player", "manual next target=$target; ${playbackSnapshot()}")
        if (target == currentIndex) return null
        TrackSwitchPerformance.armTrigger("button-next")
        trackSkipDirection = TrackSkipDirection.TO_NEXT
        return target
    }

    fun manualPreviousTarget(): Int? {
        playbackError = null
        if (songQueue.isEmpty()) return null
        if (positionMs > 3_000) {
            DiagnosticLog.event("Player", "previous restarted current song; ${playbackSnapshot()}")
            seekToMs(0)
            return null
        }
        val target = resolvePreviousIndex()
        DiagnosticLog.event("Player", "manual previous target=$target; ${playbackSnapshot()}")
        if (target == currentIndex) return null
        TrackSwitchPerformance.armTrigger("button-prev")
        trackSkipDirection = TrackSkipDirection.TO_PREVIOUS
        return target
    }

    fun next() {
        manualNextTarget()?.let { playSong(it, anchorPlaybackOrder = false) }
    }

    fun previous() {
        manualPreviousTarget()?.let { playSong(it, anchorPlaybackOrder = false) }
    }

    private fun playbackSnapshot(): String =
        "index=$currentIndex/${songQueue.size}; current=${currentSong?.id}; " +
            "mode=$playbackQueueMode; playing=$isPlaying; buffering=$isBuffering; " +
            "positionMs=$positionMs"

    fun seek(seconds: Int) = seekToMs(seconds * 1000)

    fun seekToMs(targetMs: Int) {
        val maxMs = maxDurationMs()
        val safe = if (maxMs > 0) targetMs.coerceIn(0, maxMs) else targetMs.coerceAtLeast(0)
        val activeController = controller
        if (!canAcceptSeek(activeController)) {
            DiagnosticLog.event(
                "Player",
                "seek-blocked targetMs=$safe index=$currentIndex " +
                    seekDiagnosticFields(activeController),
            )
            clearPendingSeek()
            syncPosition()
            return
        }
        DiagnosticLog.event(
            "Player",
            "seek song=${currentSong?.id} targetMs=$safe index=$currentIndex " +
                seekDiagnosticFields(activeController),
        )
        releasePendingRestorePosition(currentSong?.id)
        armPendingSeek(safe)
        setPositionMsClamped(safe)
        activeController?.seekTo(safe.toLong()) ?: return
        publishProgressState()
    }

    fun clearUserMessage() {
        userMessage = null
    }

    fun clearPlaybackError() {
        playbackError = null
        publishSurfaceState()
    }

    private fun postUserMessage(text: String) {
        userMessage = UserMessage(text)
    }

    fun release() {
        playbackStatistics.observePlayback(controller?.currentMediaItem?.mediaId, false)
        queueCoordinator.clearMirror()
        clearPendingMediaSelection()
        deferredPlaybackPublish?.let { mainHandler.removeCallbacks(it) }
        deferredPlaybackPublish = null
        scope.cancel()
        connectionSession.release()
        installQueueModel(queueModel().linearQueue(emptyList(), 0))
        isPlaying = false
        playbackError = null
        userMessage = null
        publishPlaybackStates()
    }
}

private const val PLAY_COUNT_PROBE = "DEBUG-PLAYCOUNT-9D2A"

private fun logPlayCountProbe(message: String) {
    DiagnosticLog.event("PlayCountProbe", "$PLAY_COUNT_PROBE $message")
}

private fun mediaTransitionReasonForLog(reason: Int): String =
    when (reason) {
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "repeat"
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "auto"
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "seek"
        Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "playlist-changed"
        else -> "unknown-$reason"
    }

private fun Int.toPlaybackMediaTransition(): PlaybackMediaTransition =
    when (this) {
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> PlaybackMediaTransition.Explicit
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> PlaybackMediaTransition.Automatic
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> PlaybackMediaTransition.Repeat
        else -> PlaybackMediaTransition.Other
    }

private fun timelineChangeReasonForLog(reason: Int): String =
    when (reason) {
        Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED -> "playlist-changed"
        Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE -> "source-update"
        else -> "unknown-$reason"
    }

private fun String.shortSongId(): String =
    takeLast(12)

private fun String?.shortSongIdOrNone(): String =
    this?.shortSongId() ?: "none"

private fun summarizePlaybackUnchangedQueueDiff(
    oldQueue: List<Song>,
    newQueue: List<Song>,
    currentIndex: Int,
): String {
    if (oldQueue == newQueue) return "diag=playback-unchanged-song-diff songDiff=none"
    var changedCount = 0
    var firstChangedIndex = -1
    for (index in oldQueue.indices) {
        if (oldQueue[index] != newQueue[index]) {
            changedCount++
            if (firstChangedIndex < 0) firstChangedIndex = index
        }
    }
    val safeCurrent = currentIndex.takeIf { it in oldQueue.indices && it in newQueue.indices }
    val currentFields = safeCurrent?.let { index ->
        SongChangeDiagnostics.summarizeChangedFields(oldQueue[index], newQueue[index])
    } ?: "n/a"
    val firstFields = firstChangedIndex.takeIf { it >= 0 }?.let { index ->
        SongChangeDiagnostics.summarizeChangedFields(oldQueue[index], newQueue[index])
    } ?: "n/a"
    val firstSongId = firstChangedIndex.takeIf { it >= 0 }
        ?.let { newQueue[it].id.takeLast(12) }
        ?: "none"
    return "diag=playback-unchanged-song-diff songDiff=nonPlayback changedSongs=$changedCount firstIndex=$firstChangedIndex " +
        "firstSong=$firstSongId firstFields=$firstFields currentFields=$currentFields"
}

/**
 * 判断 seek 后是否应放弃 [PlayerController] 的 pending UI 钉死。
 * @return 清除原因；`null` 表示继续钉住 pending。
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

internal fun Song.toMediaItem(): MediaItem =
    com.mica.music.media.SongMediaItemCodec.encode(this)

/**
 * MediaItem 只承载播放/会话所需的轻量字段；曲库中的完整 Song（例如歌词）优先。
 */
internal fun resolveMirroredSong(
    item: MediaItem,
    resolver: ((String) -> Song?)?,
): Song? = item.mediaId
    .takeIf { it.isNotBlank() }
    ?.let { resolver?.invoke(it) }
    ?: SongMediaItemCodec.decode(item)
