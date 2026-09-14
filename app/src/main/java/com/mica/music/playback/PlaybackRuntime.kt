package com.mica.music.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.mica.music.data.*
import com.mica.music.media.ConfirmedPlaybackBoundary
import com.mica.music.media.PendingPlaybackNavigation
import com.mica.music.media.PlaybackRouter
import com.mica.music.media.PlaybackOutputStatus
import com.mica.music.media.PlaybackShuffleSessionCommand
import com.mica.music.data.playback.ServicePlaybackStateStore
import com.mica.music.data.preferences.PlaybackUiPreferences
import com.mica.music.media.SongMediaItemCodec
import com.mica.music.media.MusicVideoPlaybackPolicyCodec
import com.mica.music.util.DiagnosticLog
import com.mica.music.util.TrackSwitchPerformance
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PendingMediaSelection {
    private var targetSongId: String? = null

    fun select(songId: String) {
        targetSongId = songId
    }

    fun shouldAccept(mediaId: String?): Boolean {
        val target = targetSongId ?: return true
        return mediaId == target
    }

    fun confirm(mediaId: String?): Boolean {
        val target = targetSongId ?: return false
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

internal data class PlaybackTrackTransition(
    val previousSongId: String?,
    val newSongId: String?,
    val kind: PlaybackMediaTransition,
)

internal data class PlaybackRuntimeSnapshot(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val playbackError: String? = null,
    val playbackStatus: PlaybackStatus = PlaybackStatus(),
    val playbackQueueMode: PlaybackQueueMode = PlaybackQueueMode.OFF,
    val playbackTuning: PlaybackTuning = PlaybackTuning(),
    val currentIndex: Int = 0,
    val videoState: PlaybackVideoState = PlaybackVideoState(),
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val pendingSeekMs: Int = -1,
    val positionRevision: Long = 0L,
    val queue: List<Song> = emptyList(),
    val userMessage: UserMessage? = null,
)

/**
 * Owns playback application coordination independently of Compose.
 *
 * PlayerController is the UI-facing facade/projection. This runtime owns the MediaController
 * connection, Player.Listener semantics, queue/timeline/tuning/statistics state and transport
 * command execution.
 */
internal class PlaybackRuntime(
    private val context: Context,
    private val mediaControllerConnector: MediaControllerConnector,
    private val sessionStorage: PlaybackSessionStorage,
    private val songResolver: PlaybackSongResolver,
    outputStatusFlow: StateFlow<PlaybackOutputStatus>,
    dispatcher: CoroutineDispatcher,
    private val queueMirrorDispatcher: CoroutineDispatcher,
    private val restoreDispatcher: CoroutineDispatcher,
    monotonicNowMs: () -> Long,
    private val stateSink: (PlaybackRuntimeSnapshot) -> Unit,
    private val playStartedSink: (String) -> Unit,
    private val listenSecondsSink: (String, Long) -> Unit,
    private val trackTransitionSink: (PlaybackTrackTransition) -> Unit,
) {
    internal companion object {
        const val QUEUE_MIRROR_DEBOUNCE_MS = 100L
    }

    private val appCtx = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var deferredPlaybackPublish: Runnable? = null
    private var outputStatus: PlaybackOutputStatus = outputStatusFlow.value
    internal val queueCoordinator = PlaybackQueueCoordinator(
        scope = scope,
        workerDispatcher = queueMirrorDispatcher,
        mirrorDebounceMs = QUEUE_MIRROR_DEBOUNCE_MS,
    )

    init {
        scope.launch {
            outputStatusFlow.collect { next ->
                if (next.revision <= outputStatus.revision) return@collect
                outputStatus = next
                publishSurfaceState()
            }
        }
    }
    private val timelineCoordinator = PlaybackTimelineCoordinator(monotonicNowMs)
    private val tuningCoordinator = PlaybackTuningCoordinator()
    private val playbackOrderState: PlaybackOrderState
        get() = queueCoordinator.order

    private val currentIndex: Int
        get() = queueCoordinator.currentIndex

    private var trackSkipDirection: TrackSkipDirection? = null

    fun peekTrackSkipDirection(): TrackSkipDirection? = trackSkipDirection

    fun consumeTrackSkipDirection(): TrackSkipDirection? {
        val direction = trackSkipDirection
        trackSkipDirection = null
        return direction
    }

    private var isPlaying: Boolean = false

    private val positionMs: Int
        get() = timelineCoordinator.positionMs

    private val durationSec: Int
        get() = timelineCoordinator.durationSec

    private val pendingSeekMs: Int
        get() = timelineCoordinator.pendingSeekMs

    fun setSeekUiActive(active: Boolean) {
        timelineCoordinator.setSeekUiActive(active)
    }

    internal fun uiPositionMs(): Int =
        timelineCoordinator.uiPositionMs(currentSong?.durationSec ?: 0)

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

    private fun notifyPlaybackProgress(rawMs: Int, allowBackward: Boolean = false) {
        if (allowBackward || pendingSeekMs >= 0) {
            timelineCoordinator.setPositionClamped(rawMs, currentSong?.durationSec ?: 0)
        } else {
            timelineCoordinator.samplePresentationPosition(
                rawMs = rawMs,
                songDurationSec = currentSong?.durationSec ?: 0,
                isAdvancing = controller?.isPlaying == true,
                playbackSpeed = playbackTuning.speed,
            )
        }
        publishProgressState()
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
        if (!controller.availableCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return false
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
        savePlaybackSession(sync = force)
    }

    private fun savePlaybackSession(sync: Boolean) {
        val song = currentSong ?: return
        val shuffleEnabled = playbackOrderState.shuffleEnabled
        sessionStorage.save(
            PlaybackSession(
                songId = song.id,
                positionMs = uiPositionMs(),
                shuffleEnabled = shuffleEnabled,
                shuffleSourceIds = if (shuffleEnabled) playbackOrderState.sourceIds else emptyList(),
                shuffleSeed = if (shuffleEnabled) playbackOrderState.shuffleSeed else null,
            ),
            sync = sync,
        )
    }

    private fun restoredShuffleSourceIds(
        session: PlaybackSession?,
        playbackIds: List<String>,
    ): List<String>? {
        if (session?.shuffleEnabled != true || session.shuffleSourceIds.isEmpty()) return null
        val playbackSet = playbackIds.toHashSet()
        val restored = session.shuffleSourceIds.filter { it in playbackSet }.distinct()
        if (restored.isEmpty()) return null
        val restoredSet = restored.toHashSet()
        return restored + playbackIds.filterNot(restoredSet::contains)
    }

    private fun restorePersistedShuffleStateOnConnect(
        c: MediaController,
        session: PlaybackSession,
    ) {
        if (c.mediaItemCount <= 0) return
        if (!session.shuffleEnabled || session.shuffleSourceIds.isEmpty()) return
        val physicalIds = List(c.mediaItemCount) { index -> c.getMediaItemAt(index).mediaId }
        val sourceIds = restoredShuffleSourceIds(session, physicalIds) ?: return
        if (sourceIds.isEmpty()) return
        val currentId = c.currentMediaItem?.mediaId ?: session.songId
        val order = session.shuffleSeed?.let { seed ->
            PlaybackOrderState.fromSource(
                sourceIds = sourceIds,
                currentId = currentId,
                shuffleEnabled = true,
                shuffleSeed = seed,
            )
        } ?: PlaybackOrderState(
            sourceIds = sourceIds,
            playbackIds = physicalIds,
            currentId = currentId,
            shuffleEnabled = true,
            shuffleSeed = null,
        )
        if (songQueue.isEmpty()) {
            queueCoordinator.replaceOrder(order)
        } else {
            applyPlaybackOrderState(order, songQueue)
        }
        session.shuffleSeed?.let { seed -> sendAppShuffleCommand(c, enabled = true, seed = seed) }
    }

    private fun schedulePersistedShuffleRestoreOnConnect(c: MediaController) {
        if (c.mediaItemCount <= 0) return
        val orderAtSchedule = playbackOrderState
        val queueIdsAtSchedule = songQueue.map { it.id }
        scope.launch {
            val session = withContext(restoreDispatcher) { sessionStorage.load() } ?: return@launch
            if (controller !== c) return@launch
            if (playbackOrderState != orderAtSchedule || songQueue.map { it.id } != queueIdsAtSchedule) {
                // A bootstrap or user queue-mode mutation won the race while persistence loaded.
                // Never let an older startup snapshot overwrite newer in-memory intent.
                return@launch
            }
            restorePersistedShuffleStateOnConnect(c, session)
            syncPlaybackQueueModeFromPlayer(c)
            syncIndexFromPlayer(c)
            publishPlaybackStates()
        }
    }

    internal fun restoreSession(session: PlaybackSession) {
        if (songQueue.isEmpty()) return
        val index = songQueue.indexOfFirst { it.id == session.songId }
        if (index < 0) {
            sessionStorage.clear()
            return
        }
        val sourceIds = songQueue.map { it.id }
        val nextOrder = if (session.shuffleEnabled) {
            session.shuffleSeed?.let { seed ->
                PlaybackOrderState.fromSource(
                    sourceIds = sourceIds,
                    currentId = session.songId,
                    shuffleEnabled = true,
                    shuffleSeed = seed,
                )
            } ?: PlaybackOrderState(
                sourceIds = session.shuffleSourceIds.takeIf { it.isNotEmpty() } ?: sourceIds,
                playbackIds = sourceIds,
                currentId = session.songId,
                shuffleEnabled = true,
                shuffleSeed = null,
            )
        } else {
            PlaybackOrderState.fromSource(
                sourceIds = sourceIds,
                currentId = session.songId,
                shuffleEnabled = false,
            )
        }
        queueCoordinator.replaceOrder(nextOrder)
        if (session.shuffleEnabled) {
            applyPlaybackOrderState(nextOrder, songQueue)
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
            "pending-seek-clear reason=${cleared.reason} pendingMs=${cleared.pendingMs} positionMs=${cleared.positionMs}",
        )
    }

    private var isBuffering: Boolean = false
    private var playbackError: String? = null
    private var userMessage: UserMessage? = null
    private var playbackErrorUserMessageId: Long? = null
    private var notifiedMusicVideoFallbackKey: String? = null
    private var playbackVideoState = PlaybackVideoState()
    private val musicVideoOutputCoordinator = MusicVideoOutputCoordinator { state ->
        playbackVideoState = state
        publishSurfaceState()
    }

    private val songQueue: List<Song>
        get() = queueCoordinator.queue

    private var playbackQueueMode: PlaybackQueueMode = PlaybackQueueMode.OFF

    private val playbackTuning: PlaybackTuning
        get() = tuningCoordinator.requested

    private val currentSong: Song?
        get() = songQueue.getOrNull(currentIndex.coerceIn(0, (songQueue.size - 1).coerceAtLeast(0)))

    private fun publishPlaybackStates() = publishSnapshot()
    private fun publishSurfaceState() = publishSnapshot()
    private fun publishProgressState() = publishSnapshot()

    private fun publishSnapshot() {
        val activeController = controller
        val currentMediaItem = activeController?.currentMediaItem
        val currentMediaId = currentMediaItem?.mediaId ?: currentSong?.id
        val musicVideoPolicyEnabled = currentMediaItem
            ?.takeIf { currentSong?.musicVideoUri != null && it.mediaId == currentSong?.id }
            ?.let(MusicVideoPlaybackPolicyCodec::isEnabled)
        val musicVideoEffective = musicVideoPolicyEnabled == true
        val projectedVideoState = projectMusicVideoState(
            currentMediaId = currentMediaId,
            effective = musicVideoEffective,
            rawState = playbackVideoState,
        )
        val actualIsPlaying = activeController?.isPlaying == true
        val actualPlayWhenReady = activeController?.playWhenReady == true
        val actualPlaybackState = activeController?.playbackState
        val status = resolvePlaybackStatus(
            hasCurrentSong = currentSong != null,
            controllerConnected = activeController != null && connectionSession.isConnected,
            playbackState = actualPlaybackState,
            isPlaying = actualIsPlaying,
            playWhenReady = actualPlayWhenReady,
            pendingOutputPlayIntent = outputStatus.pendingPlayIntent,
            playbackSuppressionReason = activeController?.playbackSuppressionReason
                ?: Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            playbackError = playbackError,
            outputAvailability = outputStatus.availability,
        )
        stateSink(
            PlaybackRuntimeSnapshot(
                currentSong = currentSong,
                isPlaying = actualIsPlaying,
                isBuffering = actualPlaybackState == Player.STATE_BUFFERING,
                playbackError = playbackError,
                playbackStatus = status,
                playbackQueueMode = playbackQueueMode,
                playbackTuning = playbackTuning,
                currentIndex = currentIndex,
                videoState = projectedVideoState,
                positionMs = uiPositionMs(),
                durationMs = uiDurationMs(),
                pendingSeekMs = pendingSeekMs,
                positionRevision = timelineCoordinator.positionRevision,
                queue = songQueue,
                userMessage = userMessage,
            ),
        )
    }

    private fun applyPlaybackOrderState(order: PlaybackOrderState, candidates: List<Song> = songQueue) {
        installQueueModel(queueModel().applyOrder(order, candidates))
    }

    private fun resetPlaybackOrderFromQueue(queue: List<Song>, preserveSongId: String?): List<Song> {
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
        onPlaybackStackRebuilt = ::onPlaybackStackRebuilt,
    )
    private val controller: MediaController?
        get() = connectionSession.controller

    internal val isConnected: Boolean
        get() = connectionSession.isConnected

    private val pendingMediaSelection = PendingMediaSelection()
    private var pendingQueue: List<Song>? = null
    private var pendingSingleSongId: String? = null
    private var pendingQueuePlaySongId: String? = null
    private var autoPlayOnLaunchConsumed = false
    private var handlingConnection = false
    private val playbackStatistics = PlaybackStatisticsTracker(
        monotonicNowMs = monotonicNowMs,
        onListenSecondsAdded = listenSecondsSink,
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
        val sameSongRewind = armed &&
            boundary.oldSongId != null &&
            boundary.oldSongId == boundary.newSongId &&
            currentSong?.id == boundary.newSongId &&
            pendingSeekMs < 0 &&
            !timelineCoordinator.seekUiActive
        if (sameSongRewind) {
            timelineCoordinator.markPositionDiscontinuity()
            notifyPlaybackProgress(
                boundary.newPositionMs.toInt().coerceAtLeast(0),
                allowBackward = true,
            )
        }
        controller?.let { publishPlayCountIfStarted(it, it.isPlaying) }
    }

    private fun onPlaybackStackRebuilt() {
        val c = controller ?: return
        musicVideoOutputCoordinator.reattachForPlaybackStack(c, c.currentMediaItem?.mediaId)
    }

    private fun createPlayerListener(c: MediaController, isCurrentConnection: () -> Boolean): Player.Listener =
        object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (!isCurrentConnection()) return
                if (c.mediaItemCount <= 0) return
                if (!handlingConnection) maybeAutoPlayOnLaunch()
                val fallbackSong = c.currentMediaItem?.let(SongMediaItemCodec::decode)
                val fallbackRevision = c.currentMediaItem
                    ?.let(MusicVideoPlaybackPolicyCodec::fallbackRevision)
                    ?.takeIf(String::isNotBlank)
                val fallbackKey = fallbackSong?.id?.let { songId ->
                    fallbackRevision?.let { revision -> "$songId\u0001$revision" }
                }
                fallbackKey
                    ?.takeIf { it != notifiedMusicVideoFallbackKey }
                    ?.let {
                        notifiedMusicVideoFallbackKey = it
                        playbackError = null
                        postUserMessage("MV 无法播放，已继续播放音乐")
                    }
                val mirrorAligned = isQueueMirrorAligned(c)
                if (!mirrorAligned) {
                    scheduleQueueMirrorFromPlayer(c)
                    return
                }
                val signature = queueCoordinator.orderSignature(c)
                if (!queueCoordinator.hasSignature(signature)) scheduleQueueMirrorFromPlayer(c)
                else syncQueueIndexFromPlayer(c)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (!isCurrentConnection()) return
                musicVideoOutputCoordinator.detachForMediaChange(mediaItem?.mediaId)
                val previousSongId = currentSong?.id
                val previousStatsSongId = playbackStatistics.statisticsSongId
                val transitionSongId = mediaItem?.mediaId
                val pendingBefore = playbackStatistics.pendingSongId
                if (!syncIndexFromPlayer(c)) {
                    return
                }
                playbackStatistics.observePlayback(c.currentMediaItem?.mediaId, c.isPlaying)
                val newSongId = currentSong?.id
                val currentSongChanged = previousSongId != newSongId
                val shouldResetPosition =
                    reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK &&
                        reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                        (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT || currentSongChanged) &&
                        (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED || currentSongChanged)
                if (shouldResetPosition) {
                    clearPendingSeek()
                    timelineCoordinator.markPositionDiscontinuity()
                    setPositionMsClamped(0)
                }
                val unsupportedMessage = mediaItem?.let(SongMediaItemCodec::decode)?.let(PlaybackRouter::unsupportedMessage)
                if (unsupportedMessage != null) {
                    if (playbackError != unsupportedMessage) postUserMessage(unsupportedMessage)
                    playbackError = unsupportedMessage
                } else playbackError = null
                val transitionKind = reason.toPlaybackMediaTransition()
                playbackStatistics.onTransition(transitionSongId, transitionKind)
                trackTransitionSink(
                    PlaybackTrackTransition(
                        previousSongId = previousSongId,
                        newSongId = newSongId,
                        kind = transitionKind,
                    ),
                )
                timelineCoordinator.updatePlayerDuration(c.duration)
                syncEffectivePlaybackTuning(reason = "transition")
                publishPlaybackStates()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!isCurrentConnection()) return
                if (!pendingMediaSelection.accepts(c.currentMediaItem?.mediaId)) return
                val failedItem = c.currentMediaItem
                val failedSong = failedItem?.let(SongMediaItemCodec::decode)
                if (failedItem != null &&
                    failedSong?.musicVideoUri?.isNullOrBlank() == false &&
                    MusicVideoPlaybackPolicyCodec.isEnabled(failedItem)
                ) {
                    musicVideoOutputCoordinator.onFailure(c, failedItem.mediaId)
                    return
                }
                musicVideoOutputCoordinator.onFailure(c, c.currentMediaItem?.mediaId)
                playbackStatistics.observePlayback(c.currentMediaItem?.mediaId, false)
                playbackStatistics.clearRequestAndPending()
                val song = failedSong
                val presentation = song?.let(PlaybackRouter::unsupportedMessage)?.let { PlaybackErrorPresentation(it, it) }
                    ?: PlaybackErrorMapper.toPresentation(error, song?.title, song?.isRemote == true)
                DiagnosticLog.event(
                    "Player",
                    "playback-error code=${error.errorCode} song=${song?.id ?: "unknown"} message=${error.message ?: "none"}",
                    error,
                )
                clearPendingMediaSelection()
                isBuffering = false
                playbackError = presentation.inlineMessage
                playbackErrorUserMessageId = presentation.snackbarMessage?.let(::postUserMessage)?.id
                syncIndexFromPlayer(c)
                publishPlaybackStates()
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                if (!isCurrentConnection()) return
                playbackStatistics.observePlayback(c.currentMediaItem?.mediaId, playing)
                isPlaying = playing
                if (playing) {
                    val activeSongId = c.currentMediaItem?.mediaId
                    val selectionConfirmed = pendingMediaSelection.confirm(activeSongId)
                    if (selectionConfirmed && activeSongId != null) {
                        playbackStatistics.confirmRequestedPlayback(activeSongId)
                    }
                    releasePendingRestorePosition(activeSongId)
                    if (playbackError != null) {
                        playbackError = null
                        val errorMessageId = playbackErrorUserMessageId
                        if (errorMessageId != null && userMessage?.id == errorMessageId) userMessage = null
                        playbackErrorUserMessageId = null
                    }
                    syncPosition()
                    publishPlayCountIfStarted(c, true)
                }
                publishSurfaceState()
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (!isCurrentConnection()) return
                syncPlaybackState()
                playbackStatistics.finishEventBatch()
                publishPlayCountIfStarted(c, c.isPlaying)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (!isCurrentConnection()) return
                if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                    playbackStatistics.observePlayback(c.currentMediaItem?.mediaId, false)
                }
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY && c.duration > 0) timelineCoordinator.updatePlayerDuration(c.duration)
                if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) syncIndexFromPlayer(c)
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
                if (automatic || reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
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
                    timelineCoordinator.markPositionDiscontinuity()
                    val songId = newPosition.mediaItem?.mediaId ?: c.currentMediaItem?.mediaId
                    notifyPlaybackProgress(newPosition.positionMs.toInt().coerceAtLeast(0), allowBackward = true)
                } else if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                    clearPendingSeek("discontinuity")
                    timelineCoordinator.markPositionDiscontinuity()
                    notifyPlaybackProgress(newPosition.positionMs.toInt().coerceAtLeast(0), allowBackward = true)
                }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                if (isCurrentConnection()) syncPlaybackQueueModeFromPlayer(c)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (isCurrentConnection()) syncPlaybackQueueModeFromPlayer(c)
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

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (!isCurrentConnection()) return
                musicVideoOutputCoordinator.onVideoSize(
                    controllerIdentity = c,
                    mediaId = c.currentMediaItem?.mediaId,
                    width = videoSize.width,
                    height = videoSize.height,
                    pixelWidthHeightRatio = videoSize.pixelWidthHeightRatio,
                )
            }

            override fun onRenderedFirstFrame() {
                if (!isCurrentConnection()) return
                musicVideoOutputCoordinator.onFirstFrame(c, c.currentMediaItem?.mediaId)
            }
        }

    private fun onConnected(c: MediaController) {
        handlingConnection = true
        try {
            val queuedPlaySongId = pendingQueuePlaySongId
            val queuedForPlay = pendingQueue.takeIf { queuedPlaySongId != null }
            if (queuedForPlay != null && queuedPlaySongId != null) {
                pendingQueue = null
                pendingQueuePlaySongId = null
                val targetIndex = queuedForPlay.indexOfFirst { it.id == queuedPlaySongId }
                if (targetIndex >= 0) playSong(targetIndex, forceQueuePayload = true)
            } else {
                pendingQueue?.let {
                    applyQueue(
                        c = c,
                        newQueue = it,
                        preservePlayback = true,
                        startPositionMs = timelineCoordinator.pendingRestorePosition()?.toLong() ?: 0L,
                    )
                    pendingQueue = null
                }
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
            )?.let { target -> c.setPlaybackParameters(target.toPlaybackParameters()) }
            playbackStatistics.reset(c.currentMediaItem?.mediaId)
            isPlaying = c.isPlaying
            playbackStatistics.observePlayback(c.currentMediaItem?.mediaId, c.isPlaying)
            timelineCoordinator.updatePlayerDuration(c.duration)
            syncPosition()
            publishPlaybackStates()
            schedulePersistedShuffleRestoreOnConnect(c)
            maybeAutoPlayOnLaunch()
        } finally {
            handlingConnection = false
        }
    }

    private fun maybeAutoPlayOnLaunch() {
        if (autoPlayOnLaunchConsumed) return
        if (pendingSingleSongId != null || pendingQueuePlaySongId != null) {
            autoPlayOnLaunchConsumed = true
            return
        }
        val c = controller ?: return
        if (c.mediaItemCount <= 0 || c.currentMediaItem == null) return
        autoPlayOnLaunchConsumed = true
        if (!PlaybackUiPreferences.autoPlayOnLaunch(appCtx)) return
        if (c.playWhenReady || c.isPlaying) return
        playbackError = null
        releasePendingRestorePosition(c.currentMediaItem?.mediaId)
        if (c.playbackState == Player.STATE_ENDED) {
            c.seekTo(c.currentMediaItemIndex.coerceAtLeast(0), 0L)
        }
        c.play()
    }

    private fun onControllerDisconnected(disconnectedController: MediaController?) {
        disconnectedController?.let(musicVideoOutputCoordinator::detachForController)
        playbackStatistics.observePlayback(disconnectedController?.currentMediaItem?.mediaId, false)
        isPlaying = false
        isBuffering = false
        queueCoordinator.clearMirror()
        pendingMediaSelection.clear()
        playbackStatistics.reset(null)
        PendingPlaybackNavigation.clear()
        publishPlaybackStates()
    }

    fun attachMusicVideoOutput(textureView: TextureView): Long? {
        val c = controller ?: return null
        val song = currentSong ?: return null
        val item = c.currentMediaItem ?: return null
        if (item.mediaId != song.id || song.musicVideoUri.isNullOrBlank()) {
            return null
        }
        if (!MusicVideoPlaybackPolicyCodec.isEnabled(item)) {
            return null
        }
        if (!c.availableCommands.contains(Player.COMMAND_SET_VIDEO_SURFACE) ||
            !c.availableCommands.contains(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)
        ) {
            return null
        }
        return musicVideoOutputCoordinator.attach(
            object : MusicVideoOutputLeasePort {
                override val outputIdentity: Any = textureView
                private var boundController: MediaController = c
                override val controllerIdentity: Any
                    get() = boundController
                override val mediaId: String = song.id

                override fun attach() {
                    boundController.setVideoTextureView(textureView)
                    boundController.trackSelectionParameters = boundController.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                        .build()
                }

                override fun detach() {
                    runCatching { boundController.clearVideoTextureView(textureView) }
                    runCatching {
                        boundController.trackSelectionParameters = boundController.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                            .build()
                    }
                }

                override fun rebind(controllerIdentity: Any): Boolean {
                    val next = controllerIdentity as? MediaController ?: return false
                    boundController = next
                    return true
                }
            },
        )
    }

    fun detachMusicVideoOutput(textureView: TextureView, leaseId: Long) {
        musicVideoOutputCoordinator.detach(leaseId, textureView)
    }

    private fun publishPlayCountIfStarted(player: Player, playing: Boolean) {
        val pendingSongId = playbackStatistics.pendingSongId ?: return
        val playerSongId = player.currentMediaItem?.mediaId
        if (!playing) {
            return
        }
        if (pendingSongId != playerSongId) {
            return
        }
        val publishedSongId = playbackStatistics.publishPlayStartedIfReady(playerSongId, playing) ?: return
        playStartedSink(publishedSongId)
    }

    private fun syncIndexFromPlayer(c: MediaController): Boolean {
        if (songQueue.isEmpty()) {
            queueCoordinator.replaceCurrentIndex(0)
            publishPlaybackStates()
            return true
        }
        val mediaId = c.currentMediaItem?.mediaId
        if (!pendingMediaSelection.shouldAccept(mediaId)) return false
        val mediaIdIndex = mediaId?.let { id -> songQueue.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
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

    private fun syncQueueMirrorFromPlayer(c: Player, resolver: (String) -> Song? = songResolver::resolve) {
        if (c.mediaItemCount <= 0) return
        val result = queueCoordinator.rebuildMirrorNow(
            player = c,
            resolver = resolver,
            onApplied = ::publishPlaybackStates,
        )
        if (result.applied && c is MediaController) syncIndexFromPlayer(c)
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

    private fun syncQueueIndexFromPlayer(c: MediaController) {
        syncIndexFromPlayer(c)
    }

    private fun isQueueMirrorAligned(c: Player): Boolean {
        if (songQueue.isEmpty() || c.mediaItemCount != songQueue.size) return false
        val mediaId = c.currentMediaItem?.mediaId ?: return false
        if (songQueue.none { it.id == mediaId }) return false
        val playerIndex = c.currentMediaItemIndex
        if (playerIndex !in songQueue.indices) return false
        return runCatching { c.getMediaItemAt(playerIndex).mediaId == mediaId }.getOrDefault(false)
    }

    suspend fun bootstrapQueue(resolveSong: (String) -> Song?): Boolean {
        checkQueueWriteThread()
        val initialController = controller
        if (initialController != null && initialController.mediaItemCount > 0) {
            val session = withContext(restoreDispatcher) { sessionStorage.load() }
            checkQueueWriteThread()
            val c = controller
            if (c !== initialController || c.mediaItemCount <= 0) {
                return bootstrapQueueFromPersistedSnapshot(resolveSong)
            }
            return bootstrapQueueFromLiveController(c, session, resolveSong)
        }
        return bootstrapQueueFromPersistedSnapshot(resolveSong)
    }

    private fun bootstrapQueueFromLiveController(
        c: MediaController,
        session: PlaybackSession?,
        resolveSong: (String) -> Song?,
    ): Boolean {
        if (c.mediaItemCount <= 0) return false
        syncQueueMirrorFromPlayer(c, resolver = resolveSong)
        val physicalIds = songQueue.map { it.id }
        restoredShuffleSourceIds(session, physicalIds)?.let { sourceIds ->
            val currentId = currentSong?.id
            val seed = session?.shuffleSeed
            val order = seed?.let {
                PlaybackOrderState.fromSource(
                    sourceIds = sourceIds,
                    currentId = currentId,
                    shuffleEnabled = true,
                    shuffleSeed = it,
                )
            } ?: PlaybackOrderState(
                sourceIds = sourceIds,
                playbackIds = physicalIds,
                currentId = currentId,
                shuffleEnabled = true,
                shuffleSeed = null,
            )
            applyPlaybackOrderState(order, songQueue)
            seed?.let { sendAppShuffleCommand(c, enabled = true, seed = it) }
            syncPlaybackQueueModeFromPlayer(c)
            publishPlaybackStates()
        }
        return true
    }

    private suspend fun bootstrapQueueFromPersistedSnapshot(
        resolveSong: (String) -> Song?,
    ): Boolean {
        val (snapshot, session) = withContext(restoreDispatcher) {
            ServicePlaybackStateStore(appCtx).load() to sessionStorage.load()
        }
        checkQueueWriteThread()

        // The service may have connected while persistence was being read. Prefer the live queue
        // over an older snapshot, but reuse the already-loaded session so no main-thread read occurs.
        val liveController = controller
        if (liveController != null && bootstrapQueueFromLiveController(liveController, session, resolveSong)) {
            return true
        }

        snapshot ?: return false
        val persistedExternalSongs = snapshot.externalSongs.associateBy { it.id }
        val persistedRemoteSongs = snapshot.remoteSongs.associateBy { it.id }
        val hydrated = snapshot.queueSongIds.mapNotNull { id ->
            songResolver.resolve(id) ?:
                resolveSong(id) ?:
                persistedExternalSongs[id]?.toSong() ?:
                persistedRemoteSongs[id]?.toSong()
        }
        if (hydrated.isEmpty()) return false
        val preserveId = snapshot.currentSongId.ifBlank {
            snapshot.queueSongIds.getOrNull(snapshot.currentIndex).orEmpty()
        }
        val playbackIds = hydrated.map { it.id }
        val restoredSourceIds = restoredShuffleSourceIds(session, playbackIds)
        if (restoredSourceIds != null) {
            val currentId = preserveId.takeIf { id -> hydrated.any { it.id == id } }
            val seed = session?.shuffleSeed
            val order = seed?.let {
                PlaybackOrderState.fromSource(
                    sourceIds = restoredSourceIds,
                    currentId = currentId,
                    shuffleEnabled = true,
                    shuffleSeed = it,
                )
            } ?: PlaybackOrderState(
                sourceIds = restoredSourceIds,
                playbackIds = playbackIds,
                currentId = currentId,
                shuffleEnabled = true,
                shuffleSeed = null,
            )
            applyPlaybackOrderState(order, hydrated)
            if (controller == null) pendingQueue = songQueue
            playbackQueueMode = PlaybackQueueMode.SHUFFLE
            publishPlaybackStates()
        } else {
            queueCoordinator.replaceOrder(
                PlaybackOrderState(
                    sourceIds = playbackIds,
                    playbackIds = playbackIds,
                    currentId = preserveId.takeIf { id -> hydrated.any { it.id == id } },
                    shuffleEnabled = false,
                ),
            )
            setQueue(hydrated)
        }
        val index = songQueue.indexOfFirst { it.id == preserveId }.takeIf { it >= 0 }
            ?: snapshot.currentIndex.coerceIn(0, songQueue.lastIndex)
        queueCoordinator.replaceCurrentIndex(index)
        if (snapshot.positionMs > 0) {
            timelineCoordinator.setPendingRestore(songQueue[index].id, snapshot.positionMs.toInt())
            setPositionMsClamped(snapshot.positionMs.toInt())
        }
        publishPlaybackStates()
        controller?.let { active ->
            syncQueueToService(active, index, snapshot.positionMs, preserveCurrentPlayback = false)
            active.seekTo(index, snapshot.positionMs)
        }
        maybeAutoPlayOnLaunch()
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

    /** Queue list mutations must run on the main looper (MediaController / UI affinity). */
    private fun checkQueueWriteThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Playback queue mutations must run on the main thread"
        }
    }

    fun setQueue(newQueue: List<Song>) {
        checkQueueWriteThread()
        if (newQueue.isEmpty() && songQueue.isEmpty()) return
        pendingQueuePlaySongId = null
        pendingSingleSongId?.let { pendingId -> if (newQueue.none { it.id == pendingId }) pendingSingleSongId = null }
        val preserveId = preserveSongIdForQueue()
        val sameOrderAndIds = songQueue.isNotEmpty() && newQueue.size == songQueue.size &&
            newQueue.indices.all { i -> newQueue[i].id == songQueue[i].id }
        val playbackUnchanged = sameOrderAndIds && newQueue.indices.all { i ->
            val old = songQueue[i]
            val neu = newQueue[i]
            old.mediaUri == neu.mediaUri && old.playbackUri == neu.playbackUri &&
                old.metadata.playbackMimeType == neu.metadata.playbackMimeType
        }
        if (playbackUnchanged) {
            val changedIndices = MediaControllerQueueSync.metadataChangedIndices(songQueue, newQueue)
            if (songQueue != newQueue) {
                commitSongQueue(newQueue)
                preserveId?.let { queueCoordinator.replaceOrder(playbackOrderState.moveTo(it)) }
                publishPlaybackStates()
            }
            val c = controller
            if (c == null) pendingQueue = songQueue
            else {
                if (c.mediaItemCount > 0 && changedIndices.isNotEmpty()) {
                    applyControllerQueueMetadataRefresh(c, changedIndices)
                }
                syncPlaybackState()
            }
            return
        }
        val previousIndex = currentIndex
        val orderedQueue = resetPlaybackOrderFromQueue(newQueue, preserveId)
        publishPlaybackStates()
        if (sameOrderAndIds) {
            val c = controller
            if (c == null) pendingQueue = orderedQueue else applyQueue(c, orderedQueue, true, preserveId)
            return
        }
        val c = controller
        if (c == null) {
            pendingQueue = orderedQueue
            if (orderedQueue.isEmpty()) queueCoordinator.replaceCurrentIndex(0)
            else applyPreserveIndexForQueue(orderedQueue, preserveId, previousIndex)
            publishPlaybackStates()
            return
        }
        applyQueue(c, orderedQueue, true, preserveId)
    }

    /**
     * Appends [songs] to the live queue. Prefer this over reading a queue snapshot and
     * calling [setQueue] after a suspend point.
     */
    fun appendSongs(songs: List<Song>) {
        checkQueueWriteThread()
        if (songs.isEmpty()) return
        if (songQueue.isEmpty()) {
            setQueue(songs)
            return
        }
        setQueue(songQueue + songs)
    }

    fun playQueueSong(newQueue: List<Song>, songId: String) {
        checkQueueWriteThread()
        if (newQueue.isEmpty()) return
        if (newQueue.none { it.id == songId }) return
        pendingSingleSongId = null

        val activeController = controller
        val requestedSourceIds = newQueue.asSequence()
            .map(Song::id)
            .distinct()
            .toList()
        val sameSourceQueue = requestedSourceIds == playbackOrderState.sourceIds
        if (activeController != null && sameSourceQueue) {
            val targetIndex = songQueue.indexOfFirst { it.id == songId }
            if (targetIndex >= 0) {
                pendingQueue = null
                pendingQueuePlaySongId = null
                playSong(targetIndex)
                return
            }
        }

        val orderedQueue = resetPlaybackOrderFromQueue(newQueue, songId)
        val targetIndex = orderedQueue.indexOfFirst { it.id == songId }
        if (targetIndex < 0) return
        queueCoordinator.replaceCurrentIndex(targetIndex)
        publishPlaybackStates()
        if (activeController == null) {
            pendingQueue = orderedQueue
            pendingQueuePlaySongId = songId
            connectIfNeeded()
            return
        }
        pendingQueue = null
        pendingQueuePlaySongId = null
        playSong(targetIndex, forceQueuePayload = true)
    }

    fun refreshQueueMetadata(latestSongs: List<Song>) {
        checkQueueWriteThread()
        if (songQueue.isEmpty() || latestSongs.isEmpty()) {
            return
        }
        val latestById = latestSongs.associateBy { it.id }
        val refreshed = songQueue.map { queued -> latestById[queued.id] ?: queued }
        if (refreshed == songQueue) {
            return
        }
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
            queueModel().preserveIndexForQueue(newQueue, preserveSongId, fallbackIndex),
        )
        publishPlaybackStates()
    }

    private fun applyQueue(
        c: MediaController,
        newQueue: List<Song>,
        preservePlayback: Boolean = false,
        preserveSongId: String? = preserveSongIdForQueue(),
        startPositionMs: Long = 0L,
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
        val initialPositionMs = startPositionMs.coerceAtLeast(0L)
        if (c.isPlaying && !foundOldSong) {
            c.setMediaItems(newQueue.map { it.toMediaItem(appCtx) }, currentIndex, initialPositionMs)
            c.play()
            postUserMessage("当前歌曲已从库中移除")
        } else if (c.mediaItemCount == 0) {
            c.setMediaItems(newQueue.map { it.toMediaItem(appCtx) }, currentIndex, initialPositionMs)
        } else syncExoQueuePreservingPlayback()
    }

    fun syncPosition() {
        timelineCoordinator.restorePositionForSync(currentSong?.id)?.let { position ->
            setPositionMsClamped(position)
            return
        }
        val c = controller ?: return
        timelineCoordinator.updatePlayerDuration(c.duration)
        val expectedSongId = currentSong?.id
        val controllerSongId = c.currentMediaItem?.mediaId
        if (expectedSongId != null && controllerSongId != null && controllerSongId != expectedSongId) {
            DiagnosticLog.event(
                "Player",
                "position-sync-skipped staleController current=$expectedSongId controller=$controllerSongId rawMs=${c.currentPosition.coerceAtLeast(0L)}",
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
        val c = controller ?: return
        if (c.playWhenReady) c.pause()
    }

    fun setPlaybackVolume(volume: Float) {
        controller?.volume = volume.coerceIn(0f, 1f)
    }

    val playbackVolume: Float
        get() = controller?.volume ?: 1f

    fun setPlaybackSpeed(speed: Float) = applyPlaybackTuning(playbackTuning.withSpeed(speed))
    fun setPlaybackPitchSemitones(semitones: Float) = applyPlaybackTuning(playbackTuning.withPitchSemitones(semitones))
    fun resetPlaybackTuning() = applyPlaybackTuning(PlaybackTuning())

    private fun applyPlaybackTuning(tuning: PlaybackTuning) {
        tuningCoordinator.request(tuning)
        syncEffectivePlaybackTuning(reason = "user-request", force = true)
        publishSurfaceState()
    }

    private fun syncEffectivePlaybackTuning(reason: String, force: Boolean = false) {
        val effective = tuningCoordinator.targetForSync(playbackTuningAvailableFor(currentSong), force) ?: return
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

    private fun playbackTuningAvailableFor(song: Song?): Boolean = song?.let { !DsdSupport.isDsdSong(it) } ?: true

    fun togglePlay() {
        if (songQueue.isEmpty()) return
        val c = controller
        if (c == null) {
            connectIfNeeded()
            postUserMessage("播放服务未就绪")
            return
        }
        playbackError = null
        if (c.playbackState == Player.STATE_ENDED) {
            releasePendingRestorePosition(currentSong?.id)
            val restartIndex = c.currentMediaItemIndex.coerceAtLeast(0)
            c.seekTo(restartIndex, 0L)
            c.play()
            return
        }
        if (c.playWhenReady) {
            c.pause()
            return
        }
        if (c.mediaItemCount > 0 && c.currentMediaItem?.mediaId == currentSong?.id) {
            releasePendingRestorePosition(currentSong?.id)
            c.play()
        } else playSong(currentIndex)
    }

    fun playSongById(songId: String) {
        val index = songQueue.indexOfFirst { it.id == songId }
        if (index >= 0) playSong(index)
    }

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

    fun insertPlayNext(song: Song) {
        checkQueueWriteThread()
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
        if (active == null) pendingQueue = updatedList
        else syncQueueToService(active, updatedIndex, active.currentPosition.coerceAtLeast(0L), true)
        publishPlaybackStates()
        postUserMessage("已加入下一首播放")
    }

    fun moveInQueue(fromIndex: Int, toIndex: Int) {
        checkQueueWriteThread()
        if (fromIndex !in songQueue.indices || toIndex !in songQueue.indices || fromIndex == toIndex) return
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
                c.moveMediaItem(fromIndex, toIndex)
            } else syncQueueToService(c, newCurrent, c.currentPosition.coerceAtLeast(0L), true)
        } ?: run { pendingQueue = list }
        publishPlaybackStates()
    }

    fun removeFromQueue(index: Int) {
        checkQueueWriteThread()
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
            if (wasPlaying) playSong(newIndex)
            else {
                queueCoordinator.replaceCurrentIndex(newIndex)
                publishPlaybackStates()
            }
        }
    }

    /**
     * Atomically removes every queue entry with [songId] from the live queue.
     * Callers must not snapshot the queue across suspend points and rewrite it.
     */
    fun removeSongById(songId: String): Boolean {
        checkQueueWriteThread()
        var removedAny = false
        while (true) {
            val index = songQueue.indexOfFirst { it.id == songId }
            if (index < 0) return removedAny
            removeFromQueue(index)
            removedAny = true
        }
    }

    private fun applyQueueOrder(list: List<Song>, newIndex: Int) {
        installQueueModel(queueModel().linearQueue(list, newIndex))
        publishPlaybackStates()
        if (controller == null) {
            pendingQueue = list
            return
        }
        syncExoQueuePreservingPlayback()
    }

    fun playSong(index: Int) = playSong(index, forceQueuePayload = false)

    private fun playSong(index: Int, forceQueuePayload: Boolean) {
        if (songQueue.isEmpty()) return
        val c = controller ?: run {
            connectIfNeeded()
            postUserMessage("播放服务未就绪")
            return
        }
        var safe = index.coerceIn(0, songQueue.lastIndex)
        val previousIndex = currentIndex
        playbackError = null
        val song = songQueue[safe]
        queueCoordinator.replaceOrder(playbackOrderState.moveTo(song.id))
        PlaybackRouter.unsupportedMessage(song)?.let { message ->
            playbackError = message
            postUserMessage(message)
        }
        if (safe != previousIndex) {
            TrackSwitchPerformance.begin(previousIndex, safe, song.id, songQueue.size)
        }
        val requestedStartMs = timelineCoordinator.consumeRestoreStartPosition(song.id)
        queueCoordinator.replaceCurrentIndex(safe)
        clearPendingSeek()
        if (safe != previousIndex) resetDurationForSongChange(song)
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
        startControllerPlayback(c, safe, requestedStartMs, song.id, forceQueuePayload)
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
        forceQueuePayload: Boolean = false,
    ) {
        if (controller !== expectedController || currentIndex != index || songQueue.getOrNull(index)?.id != songId) {
            clearPendingMediaSelection()
            PendingPlaybackNavigation.clear()
            return
        }
        if (forceQueuePayload) {
            val queueItems = songQueue.map { it.toMediaItem(appCtx) }
            PendingPlaybackNavigation.prepare(songId, queueItems)
            syncQueueToService(
                expectedController,
                index,
                positionMs.toLong(),
                false,
                queueItems,
            )
            val serviceIndex = resolveControllerIndexForSongId(expectedController, songId) ?: index
            TrackSwitchPerformance.mark("audio-start", "index=$serviceIndex songId=$songId")
            expectedController.seekTo(serviceIndex, positionMs.toLong())
            expectedController.play()
            return
        }
        val navigationPlan = PlaybackQueueNavigation.plan(
            queueIds = songQueue.map { it.id },
            requestedIndex = index,
            songId = songId,
            currentMediaId = expectedController.currentMediaItem?.mediaId,
            serviceItemCount = expectedController.mediaItemCount,
            serviceMediaIdAt = { serviceIndex -> runCatching { expectedController.getMediaItemAt(serviceIndex).mediaId }.getOrNull() },
        ) ?: run {
            clearPendingMediaSelection()
            PendingPlaybackNavigation.clear()
            return
        }
        val serviceIndex = when (navigationPlan) {
            is PlaybackQueueNavigationPlan.SeekAligned -> {
                PendingPlaybackNavigation.clear()
                navigationPlan.serviceIndex
            }
            is PlaybackQueueNavigationPlan.CarryQueuePayload -> {
                val queueItems = songQueue.map { it.toMediaItem(appCtx) }
                PendingPlaybackNavigation.prepare(songId, queueItems)
                navigationPlan.serviceIndex
            }
            is PlaybackQueueNavigationPlan.SyncQueue -> {
                val queueItems = songQueue.map { it.toMediaItem(appCtx) }
                syncQueueToService(
                    expectedController,
                    navigationPlan.serviceIndex,
                    positionMs.toLong(),
                    true,
                    queueItems,
                )
                resolveControllerIndexForSongId(expectedController, songId) ?: navigationPlan.serviceIndex
            }
        }
        val refreshedServiceIndex =
            refreshControllerMediaItemForSongIfNeeded(
                c = expectedController,
                songId = songId,
                knownServiceIndex = serviceIndex,
            ) ?: serviceIndex
        TrackSwitchPerformance.mark("audio-start", "index=$refreshedServiceIndex songId=$songId")
        expectedController.seekTo(refreshedServiceIndex, positionMs.toLong())
        expectedController.play()
    }

    private fun applyControllerQueueMetadataRefresh(
        c: MediaController,
        changedIndices: List<Int>,
    ) {
        val plan = MediaControllerQueueSync.planMetadataRefresh(
            player = c,
            queue = songQueue,
            changedIndices = changedIndices,
            mediaItemFactory = { song -> song.toMediaItem(appCtx) },
        )
        when (plan) {
            is PlaybackQueueSyncPlan.ReplaceMediaItems -> {
                MediaControllerQueueSync.executeSyncPlan(c, plan)
            }
            is PlaybackQueueSyncPlan.Skip -> {
                if (changedIndices.isNotEmpty()) {
                    DiagnosticLog.event(
                        "QueueSync",
                        "controller-refresh-queue-metadata skipped changed=${changedIndices.size} " +
                            "queue=${songQueue.size} serviceItems=${c.mediaItemCount} " +
                            "aligned=${plan.result.queueAligned} targetMismatch=${plan.result.targetMismatch}",
                    )
                }
            }
            is PlaybackQueueSyncPlan.SetMediaItems,
            is PlaybackQueueSyncPlan.MoveMediaItems,
            -> DiagnosticLog.important(
                "QueueSync",
                "controller-refresh-queue-metadata rejected unexpected-plan=${plan.javaClass.simpleName} " +
                    "changed=${changedIndices.size} queue=${songQueue.size} serviceItems=${c.mediaItemCount}",
            )
        }
    }

    private fun refreshControllerMediaItemForSongIfNeeded(
        c: MediaController,
        songId: String?,
        knownServiceIndex: Int? = null,
    ): Int? {
        val safeSongId = songId ?: return null
        val logicalSong = songQueue.firstOrNull { it.id == safeSongId } ?: return null
        val serviceIndex = knownServiceIndex
            ?.takeIf { index ->
                index in 0 until c.mediaItemCount &&
                    runCatching { c.getMediaItemAt(index).mediaId == safeSongId }.getOrDefault(false)
            }
            ?: resolveControllerIndexForSongId(c, safeSongId)
            ?: return null
        val serviceItem = runCatching { c.getMediaItemAt(serviceIndex) }.getOrNull() ?: return serviceIndex
        if (
            SongMediaItemCodec.metadataRevision(serviceItem) !=
            SongMediaItemCodec.metadataRevision(logicalSong) &&
            c.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)
        ) {
            c.replaceMediaItem(serviceIndex, logicalSong.toMediaItem(appCtx))
        }
        return serviceIndex
    }

    private fun resolveControllerIndexForSongId(c: Player, songId: String): Int? {
        for (i in 0 until c.mediaItemCount) {
            if (runCatching { c.getMediaItemAt(i).mediaId }.getOrNull() == songId) return i
        }
        return null
    }

    private fun syncQueueToService(
        c: Player,
        targetIndex: Int,
        positionMs: Long,
        preserveCurrentPlayback: Boolean,
        prebuiltItems: List<MediaItem>? = null,
    ) {
        val plan = MediaControllerQueueSync.planSync(
            player = c,
            queue = songQueue,
            targetIndex = targetIndex,
            positionMs = positionMs,
            preserveCurrentPlayback = preserveCurrentPlayback,
            prebuiltItems = prebuiltItems,
            mediaItemFactory = { song -> song.toMediaItem(appCtx) },
        ) ?: return
        val result = plan.result
        if (plan is PlaybackQueueSyncPlan.Skip) {
            return
        }
        MediaControllerQueueSync.executeSyncPlan(c, plan)
    }

    private fun logQueueSyncMs(action: String, startedNs: Long, details: String) {
        DiagnosticLog.event("QueueSync", "$action durMs=${formatQueueSyncMs(startedNs)} $details")
    }

    private fun formatQueueSyncMs(startedNs: Long, endedNs: Long = SystemClock.elapsedRealtimeNanos()): String =
        String.format(java.util.Locale.US, "%.2f", (endedNs - startedNs) / 1_000_000.0)

    private fun syncExoQueuePreservingPlayback() {
        val c = controller ?: return
        if (songQueue.isEmpty() || c.mediaItemCount <= 0) return
        syncQueueToService(c, currentIndex, 0L, true)
        if (c.playWhenReady) c.play()
    }

    fun cyclePlaybackQueueMode() {
        val nextMode = playbackQueueMode.next()
        controller?.let { applyPlaybackQueueMode(it, nextMode) }
    }

    private fun applyPlaybackQueueMode(c: MediaController, mode: PlaybackQueueMode = playbackQueueMode) {
        when (mode) {
            PlaybackQueueMode.OFF -> {
                setAppShuffleEnabled(false, c)
                c.repeatMode = Player.REPEAT_MODE_OFF
            }
            PlaybackQueueMode.REPEAT_ALL -> {
                setAppShuffleEnabled(false, c)
                c.repeatMode = Player.REPEAT_MODE_ALL
            }
            PlaybackQueueMode.REPEAT_ONE -> {
                setAppShuffleEnabled(false, c)
                c.repeatMode = Player.REPEAT_MODE_ONE
            }
            PlaybackQueueMode.SHUFFLE -> {
                setAppShuffleEnabled(true, c)
                c.repeatMode = Player.REPEAT_MODE_OFF
            }
        }
    }

    private fun setAppShuffleEnabled(enabled: Boolean, c: MediaController) {
        if (songQueue.isNotEmpty() && playbackOrderState.shuffleEnabled != enabled) {
            val serviceIds = List(c.mediaItemCount) { index -> c.getMediaItemAt(index).mediaId }
            val order = if (enabled) {
                playbackOrderState
                    .withQueue(songQueue.map { it.id }, preserveSongIdForQueue())
                    .setShuffleEnabled(true)
            } else {
                playbackOrderState.setShuffleEnabled(false)
            }
            applyPlaybackOrderState(order, songQueue)

            val desiredSet = order.playbackIds.toSet()
            val sameServiceSet = serviceIds.size == order.playbackIds.size &&
                serviceIds.size == serviceIds.distinct().size &&
                desiredSet.size == order.playbackIds.size &&
                serviceIds.toSet() == desiredSet
            if (enabled && sameServiceSet) {
                sendAppShuffleCommand(c, enabled = true, seed = order.shuffleSeed)
            } else {
                c.shuffleModeEnabled = false
                if (serviceIds != order.playbackIds) {
                    syncQueueToService(
                        c,
                        currentIndex,
                        runCatching { c.currentPosition }.getOrDefault(0L),
                        preserveCurrentPlayback = true,
                    )
                }
            }
        } else {
            queueCoordinator.replaceOrder(playbackOrderState.copy(shuffleEnabled = enabled))
            if (!enabled) c.shuffleModeEnabled = false
        }
        if (!enabled) sendAppShuffleCommand(c, enabled = false, seed = null)
        playbackQueueMode = if (enabled) PlaybackQueueMode.SHUFFLE
        else if (playbackQueueMode == PlaybackQueueMode.SHUFFLE) PlaybackQueueMode.OFF else playbackQueueMode
        maybePersistPlaybackSession(force = true)
        publishPlaybackStates()
    }

    private fun sendAppShuffleCommand(c: MediaController, enabled: Boolean, seed: Long?) {
        if (!enabled) {
            c.sendCustomCommand(
                PlaybackShuffleSessionCommand.command,
                PlaybackShuffleSessionCommand.encode(enabled = false, seed = null),
            )
            c.shuffleModeEnabled = false
            return
        }
        val safeSeed = seed ?: return
        c.sendCustomCommand(
            PlaybackShuffleSessionCommand.command,
            PlaybackShuffleSessionCommand.encode(enabled = true, seed = safeSeed),
        )
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

    private fun resolveNextIndex(forManualSkip: Boolean): Int {
        val currentId = currentSong?.id ?: return currentIndex
        val nextId = playbackOrderState.moveTo(currentId).nextId(
            manualSkip = forManualSkip,
            repeatAll = playbackQueueMode == PlaybackQueueMode.REPEAT_ALL,
            repeatOne = playbackQueueMode == PlaybackQueueMode.REPEAT_ONE,
        ) ?: return currentIndex
        return songQueue.indexOfFirst { it.id == nextId }.takeIf { it >= 0 } ?: currentIndex
    }

    private fun resolvePreviousIndex(): Int {
        val currentId = currentSong?.id ?: return currentIndex
        val previousId = playbackOrderState.moveTo(currentId).previousId(repeatAll = true) ?: return currentIndex
        return songQueue.indexOfFirst { it.id == previousId }.takeIf { it >= 0 } ?: currentIndex
    }

    fun manualNextTarget(): Int? {
        playbackError = null
        if (songQueue.isEmpty()) return null
        val target = resolveNextIndex(forManualSkip = true)
        if (target == currentIndex) return null
        TrackSwitchPerformance.armTrigger("button-next")
        trackSkipDirection = TrackSkipDirection.TO_NEXT
        return target
    }

    fun manualPreviousTarget(): Int? {
        playbackError = null
        if (songQueue.isEmpty()) return null
        if (positionMs > 3_000) {
            seekToMs(0)
            return null
        }
        val target = resolvePreviousIndex()
        if (target == currentIndex) return null
        TrackSwitchPerformance.armTrigger("button-prev")
        trackSkipDirection = TrackSkipDirection.TO_PREVIOUS
        return target
    }

    fun next() {
        manualNextTarget()?.let(::playSong)
    }

    fun previous() {
        manualPreviousTarget()?.let(::playSong)
    }

    fun seekToMs(targetMs: Int) {
        val maxMs = maxDurationMs()
        val safe = if (maxMs > 0) targetMs.coerceIn(0, maxMs) else targetMs.coerceAtLeast(0)
        val activeController = controller
        if (!canAcceptSeek(activeController)) {
            DiagnosticLog.event("Player", "seek-blocked targetMs=$safe index=$currentIndex ${seekDiagnosticFields(activeController)}")
            clearPendingSeek()
            syncPosition()
            return
        }
        releasePendingRestorePosition(currentSong?.id)
        armPendingSeek(safe)
        setPositionMsClamped(safe)
        activeController?.seekTo(safe.toLong()) ?: return
        publishProgressState()
    }

    fun clearUserMessage() {
        if (userMessage?.id == playbackErrorUserMessageId) playbackErrorUserMessageId = null
        userMessage = null
        publishSnapshot()
    }

    fun clearPlaybackError() {
        playbackError = null
        publishSurfaceState()
    }

    private fun postUserMessage(text: String): UserMessage {
        val message = UserMessage(text)
        userMessage = message
        publishSnapshot()
        return message
    }

    fun release() {
        musicVideoOutputCoordinator.release()
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


internal fun Int.toPlaybackMediaTransition(): PlaybackMediaTransition =
    when (this) {
        Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> PlaybackMediaTransition.Explicit
        Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> PlaybackMediaTransition.Automatic
        Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> PlaybackMediaTransition.Repeat
        else -> PlaybackMediaTransition.Other
    }

internal fun String.shortSongId(): String =
    takeLast(12)

internal fun String?.shortSongIdOrNone(): String =
    this?.shortSongId() ?: "none"

internal fun Song.toMediaItem(): MediaItem =
    if (isRemote) com.mica.music.media.RemoteMediaItemCodec.encode(this)
    else com.mica.music.media.SongMediaItemCodec.encode(this)

internal fun Song.toMediaItem(context: Context): MediaItem =
    if (isRemote) com.mica.music.media.RemoteMediaItemCodec.encode(this)
    else com.mica.music.media.SongMediaItemCodec.encodeForSession(context, this)
