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

    /** 停止 ALAC 音频但保留 Exo 队列；清除 session 元数据快照，避免误路由 seek/切歌。 */
    fun dropAlacSessionState() {
        alacState = null
        sessionMediaItems = emptyList()
        sessionCurrentIndex = 0
        sessionTimeline = Timeline.EMPTY
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
            notifySessionTimelineChanged()
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

    /** ALAC 切歌时更新 session 当前索引（不重载音频）。 */
    fun setAlacSessionIndex(index: Int) {
        if (sessionMediaItems.isEmpty()) return
        sessionCurrentIndex = index.coerceIn(0, sessionMediaItems.lastIndex)
        sessionTimeline = buildSessionTimeline()
        if (alacState != null) {
            notifySessionMetadataChanged()
        }
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

    private fun notifySessionTimelineChanged() {
        sessionListeners.forEach { listener ->
            listener.onTimelineChanged(
                sessionTimeline,
                Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
            )
        }
    }

    /** 仅更新进度快照；时间轴仅在 [publishAlacState] 时刷新，避免通知/锁屏条随轮询乱跳。 */
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
        get() = useAlacSnapshot

    private val useAlacSnapshot: Boolean
        get() = alacState != null && AlacPlaybackCoordinator.alacStreamActive

    override fun getCurrentTimeline(): Timeline =
        if (useAlacSnapshot) sessionTimeline else super.getCurrentTimeline()

    override fun getMediaItemCount(): Int =
        if (useAlacSnapshot) sessionMediaItems.size else super.getMediaItemCount()

    override fun getMediaItemAt(index: Int): MediaItem =
        if (useAlacSnapshot) sessionMediaItems[index] else super.getMediaItemAt(index)

    override fun getCurrentMediaItemIndex(): Int =
        if (useAlacSnapshot) sessionCurrentIndex else super.getCurrentMediaItemIndex()

    override fun getCurrentMediaItem(): MediaItem? =
        if (useAlacSnapshot) sessionMediaItems.getOrNull(sessionCurrentIndex) else super.getCurrentMediaItem()

    override fun getMediaMetadata(): MediaMetadata {
        if (useAlacSnapshot) {
            return sessionMediaItems.getOrNull(sessionCurrentIndex)?.mediaMetadata
                ?: MediaMetadata.EMPTY
        }
        return super.getMediaMetadata()
    }

    override fun isPlaying(): Boolean = if (useAlacSnapshot) alacState!!.isPlaying else super.isPlaying()

    override fun getPlayWhenReady(): Boolean =
        if (useAlacSnapshot) alacState!!.playWhenReady else super.getPlayWhenReady()

    override fun getPlaybackState(): Int {
        if (useAlacSnapshot) {
            val state = alacState!!
            return when {
                state.buffering -> Player.STATE_BUFFERING
                else -> Player.STATE_READY
            }
        }
        return super.getPlaybackState()
    }

    override fun isLoading(): Boolean = if (useAlacSnapshot) alacState!!.buffering else super.isLoading()

    override fun getCurrentPosition(): Long =
        if (useAlacSnapshot) alacState!!.positionMs else super.getCurrentPosition()

    override fun getDuration(): Long {
        if (useAlacSnapshot) {
            alacState!!.durationMs.takeIf { it > 0 }?.let { return it }
        }
        return super.getDuration()
    }

    override fun getBufferedPosition(): Long {
        if (useAlacSnapshot) {
            val state = alacState!!
            return state.durationMs.coerceAtLeast(state.positionMs)
        }
        return super.getBufferedPosition()
    }

    override fun getPlayerError(): PlaybackException? =
        if (useAlacSnapshot) null else super.getPlayerError()

    override fun getAvailableCommands(): Player.Commands {
        if (useAlacSnapshot) {
            val it = alacState!!
            val builder = Player.Commands.Builder()
                .add(Player.COMMAND_PLAY_PAUSE)
                .add(Player.COMMAND_PREPARE)
                .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_GET_TIMELINE)
                .add(Player.COMMAND_GET_METADATA)
                .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_GET_AUDIO_ATTRIBUTES)
            if (hasNextMediaItem()) {
                builder.add(Player.COMMAND_SEEK_TO_NEXT)
                builder.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            }
            if (hasPreviousMediaItem()) {
                builder.add(Player.COMMAND_SEEK_TO_PREVIOUS)
                builder.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            }
            return builder.build()
        }
        return super.getAvailableCommands()
    }

    override fun hasNextMediaItem(): Boolean {
        if (useAlacSnapshot) {
            if (sessionMediaItems.isEmpty()) return false
            return sessionCurrentIndex < sessionMediaItems.lastIndex
        }
        return super.hasNextMediaItem()
    }

    override fun hasPreviousMediaItem(): Boolean {
        if (useAlacSnapshot) {
            if (sessionMediaItems.isEmpty()) return false
            return sessionCurrentIndex > 0
        }
        return super.hasPreviousMediaItem()
    }

    private val routeToAlacHandler: Boolean
        get() = useAlacSnapshot

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (routeToAlacHandler) {
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
        if (routeToAlacHandler) {
            AlacPlaybackCoordinator.sessionHandler?.onPlay()
        } else {
            super.play()
        }
    }

    override fun pause() {
        if (routeToAlacHandler) {
            AlacPlaybackCoordinator.sessionHandler?.onPause()
        } else {
            super.pause()
        }
    }

    override fun seekTo(positionMs: Long) {
        if (routeToAlacHandler) {
            AlacPlaybackCoordinator.sessionHandler?.onSeekTo(positionMs)
        } else {
            super.seekTo(positionMs)
        }
    }

    override fun seekToNextMediaItem() {
        if (routeToAlacHandler) {
            AlacPlaybackCoordinator.sessionHandler?.onSkipToNext()
        } else {
            super.seekToNextMediaItem()
        }
    }

    override fun seekToPreviousMediaItem() {
        if (routeToAlacHandler) {
            AlacPlaybackCoordinator.sessionHandler?.onSkipToPrevious()
        } else {
            super.seekToPreviousMediaItem()
        }
    }

    override fun seekToPrevious() {
        if (routeToAlacHandler) {
            AlacPlaybackCoordinator.sessionHandler?.onSkipToPrevious()
        } else {
            super.seekToPrevious()
        }
    }

    override fun seekToNext() {
        if (routeToAlacHandler) {
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
            get() = !useAlacSnapshot

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
