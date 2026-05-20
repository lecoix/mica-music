package com.mica.music.media

import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import java.util.IdentityHashMap

/**
 * 将 ExoPlayer 与 ALAC [AudioTrack] 流式播放桥接到同一 [Player]，
 * 供 MediaSession 向系统报告元数据、进度与播放状态。
 *
 * ALAC 活跃时：**实际音频仅走 AudioTrack**；Exo 只镜像无 URI 的元数据队列（不 prepare、不解码），
 * 播放态/进度/错误均由 [AlacSessionState] 驱动。
 */
class MicaCompositePlayer(
    private val exoPlayer: ExoPlayer,
) : ForwardingPlayer(exoPlayer) {

    @Volatile
    var alacState: AlacSessionState? = null
        private set

    private var sessionMediaItems: List<MediaItem> = emptyList()
    private var sessionCurrentIndex: Int = 0
    private var sessionTimeline: Timeline = Timeline.EMPTY

    private val sessionListeners = LinkedHashSet<Player.Listener>()
    private val listenerProxies = IdentityHashMap<Player.Listener, Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        sessionListeners.add(listener)
        val proxy = ExoEventGuardListener(listener)
        listenerProxies[listener] = proxy
        super.addListener(proxy)
    }

    override fun removeListener(listener: Player.Listener) {
        sessionListeners.remove(listener)
        listenerProxies.remove(listener)?.let { super.removeListener(it) }
    }

    fun endAlacSession() {
        alacState = null
        sessionMediaItems = emptyList()
        sessionCurrentIndex = 0
        sessionTimeline = Timeline.EMPTY
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        notifySessionPlaybackState()
    }

    /**
     * 从 ALAC 切到 Exo 的原子操作：不清空队列、不广播 IDLE，避免锁屏/通知消失且 Exo 无法恢复。
     */
    fun startExoPlayback(mediaItems: List<MediaItem>, startIndex: Int) {
        val safeIndex = startIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0))
        alacState = null
        sessionMediaItems = emptyList()
        sessionCurrentIndex = 0
        sessionTimeline = Timeline.EMPTY
        exoPlayer.setMediaItems(mediaItems, safeIndex, 0L)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        notifyExoSessionStarted()
    }

    private fun notifyExoSessionStarted() {
        val timeline = exoPlayer.currentTimeline
        val item = exoPlayer.currentMediaItem
        sessionListeners.forEach { listener ->
            listener.onTimelineChanged(timeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
            listener.onMediaItemTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
            item?.mediaMetadata?.let { listener.onMediaMetadataChanged(it) }
        }
        notifySessionPlaybackState()
    }

    private fun notifySessionPlaybackState() {
        sessionListeners.forEach { listener ->
            listener.onPlaybackStateChanged(playbackState)
            listener.onPlayWhenReadyChanged(
                playWhenReady,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            )
            listener.onIsPlayingChanged(isPlaying)
            listener.onIsLoadingChanged(isLoading)
        }
    }

    /** ALAC 开始前暂停 Exo，避免其元数据/进度泄漏到 MediaSession。 */
    fun pauseExoForAlac() {
        exoPlayer.playWhenReady = false
        exoPlayer.stop()
    }

    fun publishAlacState(state: AlacSessionState?) {
        alacState = state
        if (state == null) {
            sessionMediaItems = emptyList()
            sessionCurrentIndex = 0
            sessionTimeline = Timeline.EMPTY
        } else if (sessionMediaItems.isNotEmpty()) {
            sessionTimeline = buildSessionTimeline()
            notifySessionMetadataChanged()
        }
        notifyAlacPlaybackListeners()
    }

    /**
     * 为锁屏/通知同步元数据队列。ALAC 音频由 AudioTrack 输出，**不调用 ExoPlayer.setMediaItems**（无 URI 的 item 会 NPE）。
     */
    fun syncAlacSessionQueue(mediaItems: List<MediaItem>, startIndex: Int) {
        sessionMediaItems = mediaItems
        sessionCurrentIndex = startIndex.coerceIn(0, (mediaItems.size - 1).coerceAtLeast(0))
        sessionTimeline = buildSessionTimeline()
        if (alacState == null) return
        notifySessionMetadataChanged()
    }

    private fun buildSessionTimeline(): Timeline {
        if (sessionMediaItems.isEmpty()) return Timeline.EMPTY
        val durationUs = alacState?.durationMs?.takeIf { it > 0 }?.times(1000L) ?: C.TIME_UNSET
        val positionUs = alacState?.positionMs?.coerceAtLeast(0)?.times(1000L) ?: 0L
        return SessionMediaTimeline(
            items = sessionMediaItems,
            currentIndex = sessionCurrentIndex,
            currentDurationUs = durationUs,
            currentPositionUs = positionUs,
        )
    }

    private fun notifySessionMetadataChanged() {
        val item = sessionMediaItems.getOrNull(sessionCurrentIndex)
        sessionListeners.forEach { listener ->
            listener.onTimelineChanged(sessionTimeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
            listener.onMediaItemTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
            item?.mediaMetadata?.let { listener.onMediaMetadataChanged(it) }
        }
    }

    /** 仅更新进度快照；锁屏/通知会轮询 [getCurrentPosition]。 */
    fun publishAlacPosition(positionMs: Long, durationMs: Long) {
        val current = alacState ?: return
        alacState = current.copy(
            positionMs = positionMs,
            durationMs = durationMs.coerceAtLeast(current.durationMs),
        )
        if (sessionMediaItems.isNotEmpty()) {
            sessionTimeline = buildSessionTimeline()
        }
    }

    val isAlacActive: Boolean
        get() = alacState != null

    override fun getCurrentTimeline(): Timeline =
        if (alacState != null) sessionTimeline else super.getCurrentTimeline()

    override fun getMediaItemCount(): Int =
        if (alacState != null) sessionMediaItems.size else super.getMediaItemCount()

    override fun getMediaItemAt(index: Int): MediaItem =
        if (alacState != null) sessionMediaItems[index] else super.getMediaItemAt(index)

    override fun getCurrentMediaItemIndex(): Int =
        if (alacState != null) sessionCurrentIndex else super.getCurrentMediaItemIndex()

    override fun getCurrentMediaItem(): MediaItem? =
        if (alacState != null) sessionMediaItems.getOrNull(sessionCurrentIndex) else super.getCurrentMediaItem()

    override fun getMediaMetadata(): MediaMetadata {
        if (alacState != null) {
            return sessionMediaItems.getOrNull(sessionCurrentIndex)?.mediaMetadata
                ?: MediaMetadata.EMPTY
        }
        return super.getMediaMetadata()
    }

    override fun isPlaying(): Boolean = alacState?.isPlaying ?: super.isPlaying()

    override fun getPlayWhenReady(): Boolean = alacState?.playWhenReady ?: super.getPlayWhenReady()

    override fun getPlaybackState(): Int {
        alacState?.let { state ->
            return when {
                state.buffering -> Player.STATE_BUFFERING
                else -> Player.STATE_READY
            }
        }
        return super.getPlaybackState()
    }

    override fun isLoading(): Boolean = alacState?.buffering ?: super.isLoading()

    override fun getCurrentPosition(): Long = alacState?.positionMs ?: super.getCurrentPosition()

    override fun getDuration(): Long {
        alacState?.durationMs?.takeIf { it > 0 }?.let { return it }
        return super.getDuration()
    }

    override fun getBufferedPosition(): Long {
        alacState?.let { return it.durationMs.coerceAtLeast(it.positionMs) }
        return super.getBufferedPosition()
    }

    override fun getPlayerError(): PlaybackException? =
        if (alacState != null) null else super.getPlayerError()

    override fun getAvailableCommands(): Player.Commands {
        alacState?.let {
            return Player.Commands.Builder()
                .add(Player.COMMAND_PLAY_PAUSE)
                .add(Player.COMMAND_PREPARE)
                .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(Player.COMMAND_GET_TIMELINE)
                .add(Player.COMMAND_GET_METADATA)
                .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_GET_AUDIO_ATTRIBUTES)
                .build()
        }
        return super.getAvailableCommands()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (alacState != null) {
            if (playWhenReady) {
                AlacPlaybackCoordinator.sessionHandler?.onPlay()
            } else {
                AlacPlaybackCoordinator.sessionHandler?.onPause()
            }
        } else {
            super.setPlayWhenReady(playWhenReady)
        }
    }

    override fun play() {
        if (alacState != null) {
            AlacPlaybackCoordinator.sessionHandler?.onPlay()
        } else {
            super.play()
        }
    }

    override fun pause() {
        if (alacState != null) {
            AlacPlaybackCoordinator.sessionHandler?.onPause()
        } else {
            super.pause()
        }
    }

    override fun seekTo(positionMs: Long) {
        if (alacState != null) {
            AlacPlaybackCoordinator.sessionHandler?.onSeekTo(positionMs)
        } else {
            super.seekTo(positionMs)
        }
    }

    override fun seekToNextMediaItem() {
        if (alacState != null) {
            AlacPlaybackCoordinator.sessionHandler?.onSkipToNext()
        } else {
            super.seekToNextMediaItem()
        }
    }

    override fun seekToPreviousMediaItem() {
        if (alacState != null) {
            AlacPlaybackCoordinator.sessionHandler?.onSkipToPrevious()
        } else {
            super.seekToPreviousMediaItem()
        }
    }

    override fun seekToPrevious() {
        if (alacState != null) {
            AlacPlaybackCoordinator.sessionHandler?.onSkipToPrevious()
        } else {
            super.seekToPrevious()
        }
    }

    override fun seekToNext() {
        if (alacState != null) {
            AlacPlaybackCoordinator.sessionHandler?.onSkipToNext()
        } else {
            super.seekToNext()
        }
    }

    private fun notifyAlacPlaybackListeners() {
        notifySessionPlaybackState()
    }

    /**
     * ALAC 播放时完全屏蔽 ExoPlayer 事件；播放态/元数据均由 ALAC Session 驱动。
     */
    private inner class ExoEventGuardListener(
        private val delegate: Player.Listener,
    ) : Player.Listener {

        private val allowExoPlaybackEvents: Boolean
            get() = alacState == null

        override fun onEvents(player: Player, events: Player.Events) {
            if (allowExoPlaybackEvents) delegate.onEvents(player, events)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (allowExoPlaybackEvents) delegate.onPlaybackStateChanged(playbackState)
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (allowExoPlaybackEvents) delegate.onPlayWhenReadyChanged(playWhenReady, reason)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (allowExoPlaybackEvents) delegate.onIsPlayingChanged(isPlaying)
        }

        override fun onIsLoadingChanged(isLoading: Boolean) {
            if (allowExoPlaybackEvents) delegate.onIsLoadingChanged(isLoading)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (allowExoPlaybackEvents) delegate.onMediaItemTransition(mediaItem, reason)
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (allowExoPlaybackEvents) delegate.onTimelineChanged(timeline, reason)
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            if (allowExoPlaybackEvents) delegate.onMediaMetadataChanged(mediaMetadata)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (allowExoPlaybackEvents) delegate.onPlayerError(error)
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            if (allowExoPlaybackEvents) delegate.onPlaybackParametersChanged(playbackParameters)
        }

        override fun onMetadata(metadata: Metadata) {
            if (allowExoPlaybackEvents) delegate.onMetadata(metadata)
        }

        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
            if (allowExoPlaybackEvents) delegate.onTrackSelectionParametersChanged(parameters)
        }
    }
}
