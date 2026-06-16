package com.mica.music.data

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.mica.music.util.DiagnosticLog
import com.mica.music.util.TrackSwitchPerformance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.random.Random

data class PlaybackSurfaceState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val playbackError: String? = null,
    val playbackQueueMode: PlaybackQueueMode = PlaybackQueueMode.OFF,
    val currentIndex: Int = 0,
    val alacStreamActive: Boolean = false,
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
 * 全部曲目走 [AlacAudioTrackEngine]（FFmpeg → PCM → AudioTrack）。
 */
class PlayerController internal constructor(
    private val context: Context,
    private val mediaControllerConnector: MediaControllerConnector,
    private val sessionStorage: PlaybackSessionStorage,
    dispatcher: CoroutineDispatcher,
) {
    constructor(context: Context) : this(
        context = context,
        mediaControllerConnector = AndroidMediaControllerConnector(context.applicationContext),
        sessionStorage = PreferencesPlaybackSessionStorage(context.applicationContext),
        dispatcher = Dispatchers.Main.immediate,
    )

    private val appCtx = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var deferredPlaybackPublish: Runnable? = null

    /** 开始播放某曲时回调（用于统计播放次数）。 */
    var onSongPlayStarted: ((songId: String) -> Unit)? = null

    var currentIndex by mutableIntStateOf(0)
        private set

    /** 供播放页切歌擦除动画消费；在 [next]/[previous]/自动下一曲 时设置。 */
    var trackSkipDirection by mutableStateOf<TrackSkipDirection?>(null)
        private set

    fun consumeTrackSkipDirection(): TrackSkipDirection? {
        val direction = trackSkipDirection
        trackSkipDirection = null
        return direction
    }

    var isPlaying by mutableStateOf(false)
        private set

    var positionSec by mutableIntStateOf(0)
        private set

    /** 播放进度（毫秒），供歌词同步等需要 finer 粒度的 UI 使用。 */
    var positionMs by mutableIntStateOf(0)
        private set

    var durationSec by mutableIntStateOf(0)
        private set

    /** Exo 路径：seek 后暂存目标直至进度接近。 */
    var pendingSeekMs by mutableIntStateOf(-1)
        private set

    /** ALAC 路径：松手 seek 后 UI 暂钉目标，避免引擎超前回报导致往右跳。 */
    private var alacPendingSeekMs: Int = -1

    /** App 内正在拖动进度条时，不向系统 MediaSession 推送进度（避免通知/锁屏条乱跳）。 */
    private var alacSeekUiActive = false

    fun setAlacSeekUiActive(active: Boolean) {
        alacSeekUiActive = active
    }

    fun uiPositionMs(): Int {
        val maxMs = uiDurationMs()
        if (alacStreamActive) {
            alacPendingSeekMs.takeIf { it >= 0 }?.let { pending ->
                return if (maxMs > 0) pending.coerceIn(0, maxMs) else pending.coerceAtLeast(0)
            }
            val pos = if (maxMs > 0) positionMs.coerceIn(0, maxMs) else positionMs.coerceAtLeast(0)
            return pos
        }
        val pos = if (maxMs > 0) positionMs.coerceIn(0, maxMs) else positionMs.coerceAtLeast(0)
        pendingSeekMs.takeIf { it >= 0 }?.let { pending ->
            val target = if (maxMs > 0) pending.coerceIn(0, maxMs) else pending.coerceAtLeast(0)
            return target
        }
        return pos
    }

    fun uiDurationMs(): Int {
        val metaMs = (currentSong?.durationSec ?: 0) * 1000
        val playerMs = durationSec * 1000
        return maxOf(metaMs, playerMs).coerceAtLeast(0)
    }

    private fun maxDurationMs(): Int = uiDurationMs()

    private fun setPositionMsClamped(rawMs: Int) {
        val maxMs = maxDurationMs()
        val clamped = if (maxMs > 0) rawMs.coerceIn(0, maxMs) else rawMs.coerceAtLeast(0)
        positionMs = clamped
        positionSec = clamped / 1000
        publishProgressState()
    }

    private fun notifyPlaybackProgress(rawMs: Int) {
        setPositionMsClamped(rawMs)
        if (!alacStreamActive && pendingSeekMs >= 0 &&
            kotlin.math.abs(positionMs - pendingSeekMs) <= 800
        ) {
            pendingSeekMs = -1
            publishProgressState()
        }
    }

    fun persistPlaybackSessionNow() {
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
            PlaybackSession(songId = song.id, positionMs = uiPositionMs()),
            sync = sync,
        )
    }

    /** 曲库就绪后恢复上次播放的歌曲与进度（不自动开始播放）。 */
    fun restoreSession(session: PlaybackSession) {
        if (songQueue.isEmpty()) return
        val index = songQueue.indexOfFirst { it.id == session.songId }
        if (index < 0) {
            persistedSessionSongId = null
            sessionStorage.clear()
            return
        }
        persistedSessionSongId = session.songId
        currentIndex = index
        publishPlaybackStates()
        val pos = session.positionMs.coerceAtLeast(0)
        if (pos > 0) {
            pendingRestorePositionMs = pos
            setPositionMsClamped(pos)
            val durSec = songQueue[index].durationSec
            if (durSec > 0) durationSec = durSec
        }
    }

    private fun preserveSongIdForQueue(): String? =
        currentSong?.id ?: persistedSessionSongId

    private fun reapplyPersistedSessionIndex() {
        val songId = persistedSessionSongId ?: return
        val index = songQueue.indexOfFirst { it.id == songId }
        if (index >= 0) {
            currentIndex = index
            publishPlaybackStates()
        }
    }

    /** 曲库与 [restoreSession] 就绪后再次对齐索引，避免 [onConnected] 与恢复竞态。 */
    fun reconcileRestoredSessionIndex() {
        reapplyPersistedSessionIndex()
        pendingRestorePositionMs?.let { setPositionMsClamped(it) }
    }

    private fun clearPendingSeek() {
        pendingSeekMs = -1
        alacPendingSeekMs = -1
        publishProgressState()
    }

    /** 播放进度与 seek 目标接近（±800ms）后才解除 UI 钉住；超前时不松开，避免松手后条往右跳。 */
    private fun reconcileAlacPending(appliedMs: Int) {
        val pending = alacPendingSeekMs
        if (pending < 0) return
        if (kotlin.math.abs(appliedMs - pending) <= 800) {
            alacPendingSeekMs = -1
        }
    }

    var isBuffering by mutableStateOf(false)
        private set

    var isConnected by mutableStateOf(false)
        private set

    /** 当前是否由 [AlacAudioTrackEngine] 输出音频（非 Exo 解码） */
    var alacStreamActive by mutableStateOf(false)
        private set

    var playbackError by mutableStateOf<String?>(null)
        private set

    var userMessage by mutableStateOf<UserMessage?>(null)
        private set

    var songQueue by mutableStateOf<List<Song>>(emptyList())
        private set

    var playbackQueueMode by mutableStateOf(PlaybackQueueMode.OFF)
        private set

    val currentSong: Song?
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
            currentIndex = currentIndex,
            alacStreamActive = alacStreamActive,
        )
    }

    private fun publishProgressState() {
        playbackProgressState = PlaybackProgressState(
            positionMs = uiPositionMs(),
            durationMs = uiDurationMs(),
            pendingSeekMs = if (alacStreamActive) alacPendingSeekMs else pendingSeekMs,
        )
    }

    private fun publishQueueState() {
        playbackQueueState = PlaybackQueueState(
            queue = songQueue,
            currentIndex = currentIndex,
        )
    }

    private var controller: MediaController? = null
    private var controllerConnection: MediaControllerConnection? = null
    /** Prevents callbacks from the previously playing item from undoing an optimistic selection. */
    private val pendingMediaSelection = PendingMediaSelection()
    private var pendingQueue: List<Song>? = null
    private var connectStarted = false
    private var pendingRestorePositionMs: Int? = null
    private var pendingPlayCountSongId: String? = null
    /** 冷启动恢复前 MediaSession 尚未对应该曲，避免 [onConnected] 把索引打回 0。 */
    private var persistedSessionSongId: String? = null
    private var lastSessionPersistMs: Long = 0L
    fun connectIfNeeded() {
        if (connectStarted) return
        connectStarted = true
        connect()
    }

    fun retryConnect() {
        releaseConnectionOnly()
        connectStarted = true
        connect()
    }

    private fun connect() {
        controllerConnection = mediaControllerConnector.connect(
            onConnected = ::onConnected,
            onDisconnected = ::onControllerDisconnected,
            onFailure = {
                connectStarted = false
                postUserMessage("无法连接播放服务，请稍后重试")
            },
        )
    }

    private fun onConnected(c: MediaController) {
        controller = c

        c.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (!syncIndexFromPlayer(c)) return
                clearPendingSeek()
                setPositionMsClamped(0)
                playbackError = null
                pendingPlayCountSongId = mediaItem?.mediaId
                if (c.duration > 0) durationSec = (c.duration / 1000).toInt()
                publishPlaybackStates()
                publishPlayCountIfStarted(c, c.isPlaying)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!pendingMediaSelection.accepts(c.currentMediaItem?.mediaId)) return
                val message = error.message?.takeIf { it.isNotBlank() } ?: "Playback failed"
                clearPendingMediaSelection()
                isBuffering = false
                playbackError = message
                postUserMessage(message)
                syncIndexFromPlayer(c)
                publishPlaybackStates()
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) publishPlayCountIfStarted(c, true)
                publishSurfaceState()
            }

            override fun onEvents(player: Player, events: Player.Events) {
                syncPlaybackState()
            }

            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY && c.duration > 0) {
                    durationSec = (c.duration / 1000).toInt()
                }
                if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                    if (!ignoreExoIndexSync()) syncIndexFromPlayer(c)
                }
                publishPlaybackStates()
            }

            @UnstableApi
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                ) {
                    pendingSeekMs = -1
                    notifyPlaybackProgress(newPosition.positionMs.toInt().coerceAtLeast(0))
                }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                syncPlaybackQueueModeFromPlayer(c)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                syncPlaybackQueueModeFromPlayer(c)
            }
        })

        isConnected = true

        pendingQueue?.let {
            applyQueue(c, it, preservePlayback = true)
            pendingQueue = null
        }
        syncPlaybackQueueModeFromPlayer(c)

        if (persistedSessionSongId != null) {
            reapplyPersistedSessionIndex()
        } else {
            syncIndexFromPlayer(c)
        }
        isPlaying = c.isPlaying
        if (c.duration > 0) durationSec = (c.duration / 1000).toInt()
        if (!alacStreamActive && persistedSessionSongId == null) {
            notifyPlaybackProgress(c.currentPosition.toInt().coerceAtLeast(0))
        }
        publishPlaybackStates()
    }

    private fun onControllerDisconnected() {
        pendingMediaSelection.clear()
        controller = null
        controllerConnection = null
        isConnected = false
        connectStarted = false
        publishPlaybackStates()
    }

    private fun publishPlayCountIfStarted(player: Player, playing: Boolean) {
        if (!playing) return
        pendingPlayCountSongId
            ?.takeIf { it == player.currentMediaItem?.mediaId }
            ?.let { songId ->
                pendingPlayCountSongId = null
                onSongPlayStarted?.invoke(songId)
            }
    }

    /** 冷启动已恢复曲目、尚未真正开播前，不信任 Exo/MediaSession 的索引（多为 0）。 */
    private fun ignoreExoIndexSync(): Boolean = persistedSessionSongId != null

    private fun syncIndexFromPlayer(c: MediaController): Boolean {
        if (ignoreExoIndexSync()) {
            reapplyPersistedSessionIndex()
            return true
        }
        if (songQueue.isEmpty()) {
            currentIndex = 0
            publishPlaybackStates()
            return true
        }
        val mediaId = c.currentMediaItem?.mediaId
        if (!pendingMediaSelection.shouldAccept(mediaId)) return false
        val mediaIdIndex = mediaId?.let { id ->
            songQueue.indexOfFirst { it.id == id }.takeIf { it >= 0 }
        }
        val idx = c.currentMediaItemIndex
        currentIndex = when {
            mediaIdIndex != null -> mediaIdIndex
            idx in songQueue.indices -> idx
            else -> currentIndex.coerceIn(0, songQueue.lastIndex)
        }
        publishPlaybackStates()
        return true
    }

    fun syncPlaybackState() {
        if (alacStreamActive) return
        val c = controller ?: return
        if (ignoreExoIndexSync()) {
            reapplyPersistedSessionIndex()
            pendingRestorePositionMs?.let { setPositionMsClamped(it) }
            return
        }
        if (!syncIndexFromPlayer(c)) return
        syncPosition()
        isPlaying = c.isPlaying
        isBuffering = c.playbackState == Player.STATE_BUFFERING
        if (c.duration > 0) durationSec = (c.duration / 1000).toInt()
        publishPlaybackStates()
    }

    fun setQueue(newQueue: List<Song>) {
        if (newQueue.isEmpty() && songQueue.isEmpty()) return

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
            if (!alacStreamActive) {
                if (ignoreExoIndexSync()) {
                    reapplyPersistedSessionIndex()
                } else {
                    controller?.let { syncPlaybackState() }
                }
            }
            return
        }

        songQueue = newQueue
        publishPlaybackStates()

        if (sameOrderAndIds) {
            controller?.let {
                applyQueue(it, newQueue, preservePlayback = true, preserveSongId = preserveId)
            }
            return
        }
        val c = controller
        if (c == null) {
            pendingQueue = newQueue
            if (newQueue.isEmpty()) {
                currentIndex = 0
                persistedSessionSongId = null
            } else {
                applyPreserveIndexForQueue(newQueue, preserveId)
            }
            publishPlaybackStates()
            return
        }
        applyQueue(c, newQueue, preservePlayback = true, preserveSongId = preserveId)
    }

    private fun applyPreserveIndexForQueue(newQueue: List<Song>, preserveSongId: String?) {
        if (newQueue.isEmpty()) {
            currentIndex = 0
            publishPlaybackStates()
            return
        }
        val keepIndex = preserveSongId?.let { id ->
            newQueue.indexOfFirst { it.id == id }
        } ?: -1
        currentIndex = if (keepIndex >= 0) {
            keepIndex
        } else {
            currentIndex.coerceIn(0, newQueue.lastIndex)
        }
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
            currentIndex = 0
            isPlaying = false
            playbackError = null
            sessionStorage.clear()
            publishPlaybackStates()
            return
        }

        val playingId = preserveSongId
        val keepIndex = playingId?.let { id -> newQueue.indexOfFirst { it.id == id } } ?: -1
        val foundOldSong = preservePlayback && keepIndex >= 0
        currentIndex = if (foundOldSong) keepIndex else 0
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

    private fun wasPlayingBeforeQueueChange(c: MediaController): Boolean =
        alacStreamActive && isPlaying || c.isPlaying

    fun syncPosition() {
        if (alacStreamActive) return
        pendingRestorePositionMs?.let {
            setPositionMsClamped(it)
            return
        }
        // 已恢复曲目尚未走 ALAC 时，Exo 停在 0，不要用其覆盖 UI/歌词进度
        if (persistedSessionSongId != null) return
        val c = controller ?: return
        if (c.duration > 0) durationSec = (c.duration / 1000).toInt()
        notifyPlaybackProgress(c.currentPosition.toInt().coerceAtLeast(0))
    }

    fun pauseIfPlaying() {
        if (!isPlaying) return
        controller?.pause()
    }

    fun setPlaybackVolume(volume: Float) {
        controller?.volume = volume.coerceIn(0f, 1f)
    }

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
     * 将曲目插入当前播放位置之后（下一首播放）。
     *
     * ExoPlayer 路径：同步 [applyQueue]。
     * ALAC 流式路径：只改内存 [songQueue] / [currentIndex]，当前曲继续由
     * [AlacAudioTrackEngine] 播放，结束后 [playNextAfterStream] 会播插入项。
     */
    fun insertPlayNext(song: Song) {
        if (songQueue.isEmpty()) {
            setQueue(listOf(song))
            playSong(0)
            postUserMessage("已加入下一首播放")
            return
        }

        val list = songQueue.toMutableList()
        var playIndex = currentIndex.coerceIn(0, list.lastIndex)
        val existing = list.indexOfFirst { it.id == song.id }
        if (existing >= 0) {
            if (existing == playIndex) {
                postUserMessage("正在播放该歌曲")
                return
            }
            list.removeAt(existing)
            if (existing < playIndex) playIndex--
        }
        val insertAt = (playIndex + 1).coerceAtMost(list.size)
        list.add(insertAt, song)
        songQueue = list
        currentIndex = playIndex
        val activeController = controller
        when {
            activeController == null -> pendingQueue = list
            existing >= 0 -> syncExoQueuePreservingPlayback()
            else -> activeController.addMediaItem(insertAt, song.toMediaItem())
        }
        publishPlaybackStates()
        postUserMessage("已加入下一首播放")
    }

    fun moveInQueue(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in songQueue.indices || toIndex !in songQueue.indices) return
        if (fromIndex == toIndex) return
        val list = songQueue.toMutableList()
        val moved = list.removeAt(fromIndex)
        list.add(toIndex, moved)
        val newCurrent = when {
            currentIndex == fromIndex -> toIndex
            fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex - 1
            fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }.coerceIn(0, list.lastIndex)
        songQueue = list
        currentIndex = newCurrent
        controller?.moveMediaItem(fromIndex, toIndex) ?: run {
            pendingQueue = list
        }
        publishPlaybackStates()
    }

    fun removeFromQueue(index: Int) {
        if (index !in songQueue.indices) return
        val removingCurrent = index == currentIndex
        val wasPlaying = isPlaying
        val list = songQueue.toMutableList()
        list.removeAt(index)
        if (list.isEmpty()) {
            songQueue = emptyList()
            currentIndex = 0
            isPlaying = false
            playbackError = null
            controller?.clearMediaItems()
            publishPlaybackStates()
            return
        }
        val newIndex = when {
            index < currentIndex -> currentIndex - 1
            index == currentIndex -> index.coerceAtMost(list.lastIndex)
            else -> currentIndex
        }.coerceIn(0, list.lastIndex)
        applyQueueOrder(list, newIndex)
        if (removingCurrent) {
            if (wasPlaying) {
                playSong(newIndex)
            } else {
                currentIndex = newIndex
                publishPlaybackStates()
            }
        }
    }

    /** 更新内存队列；音频由 [AlacAudioTrackEngine] 输出，仅同步 MediaSession 元数据。 */
    private fun applyQueueOrder(list: List<Song>, newIndex: Int) {
        currentIndex = newIndex
        songQueue = list
        publishPlaybackStates()
        if (controller == null) {
            pendingQueue = list
            return
        }
        syncExoQueuePreservingPlayback()
    }

    fun playSong(index: Int) {
        if (songQueue.isEmpty()) return
        val c = controller ?: run {
            connectIfNeeded()
            postUserMessage("播放服务未就绪")
            return
        }
        val safe = index.coerceIn(0, songQueue.lastIndex)
        val previousIndex = currentIndex
        playbackError = null
        val song = songQueue[safe]
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
                "format=${song.formatLabel}; path=${song.filePath}; ${playbackSnapshot()}",
        )
        if (song.id != persistedSessionSongId) {
            pendingRestorePositionMs = null
        }
        val requestedStartMs = pendingRestorePositionMs
            ?.takeIf { it >= 1_000 }
            ?: 0
        pendingRestorePositionMs = null
        persistedSessionSongId = null
        currentIndex = safe
        deferredPlaybackPublish?.let { mainHandler.removeCallbacks(it) }
        val publish = Runnable {
            deferredPlaybackPublish = null
            if (currentIndex == safe) publishPlaybackStates()
        }
        deferredPlaybackPublish = publish
        mainHandler.post(publish)
        pendingMediaSelection.select(song.id)
        startControllerPlayback(c, safe, requestedStartMs, song.id)
    }

    private fun clearPendingMediaSelection() {
        pendingMediaSelection.clear()
    }

    private fun startControllerPlayback(
        expectedController: MediaController,
        index: Int,
        positionMs: Int,
        songId: String,
    ) {
        if (controller !== expectedController) return
        if (currentIndex != index || songQueue.getOrNull(index)?.id != songId) return
        TrackSwitchPerformance.mark("audio-start", "index=$index")
        expectedController.seekTo(index, positionMs.toLong())
        expectedController.play()
    }

    private fun syncExoQueuePreservingPlayback() {
        val c = controller ?: return
        if (songQueue.isEmpty() || c.mediaItemCount <= 0) return
        val currentId = c.currentMediaItem?.mediaId ?: return
        val matchedIndex = songQueue.indexOfFirst { it.id == currentId }
        val index = matchedIndex
            .takeIf { it >= 0 }
            ?: currentIndex.coerceIn(0, songQueue.lastIndex)
        val position = if (matchedIndex >= 0) c.currentPosition.coerceAtLeast(0) else 0L
        val resume = c.playWhenReady
        c.setMediaItems(songQueue.map { it.toMediaItem() }, index, position)
        if (resume) c.play()
    }

    /** ALAC 流式时用户「想播」意图，与 [isPlaying]（实际在播）区分，供 MediaSession。 */
    /*
     * Legacy controller-owned software playback implementation.
     * The active path is ServicePlaybackEngineCoordinator; this block is isolated pending
     * physical deletion after the architecture migration settles.
    private fun startSoftwarePlayback(song: Song, index: Int, startOffsetMs: Int = 0) {
        val engine = alacEngine ?: run {
            postUserMessage("播放服务未就绪")
            return
        }
        if (!com.mica.music.media.FfmpegRunner.hasEmbeddedBinary(appCtx)) {
            playbackError = "未找到 FFmpeg"
            postUserMessage("未找到 FFmpeg，请在电脑上运行 scripts\\build-ffmpeg-arm64.ps1 后重新安装")
            return
        }
        val composite = playbackBackend.compositePlayer
        TrackSwitchPerformance.mark(
            "audio-start",
            "index=$index format=${song.formatLabel} alacActive=$alacStreamActive",
        )
        DecodePerformance.bindSwitch(song.id)
        val request = requestSequencer.next(
            song = song,
            backend = PlaybackBackendKind.SOFTWARE,
            startPositionMs = startOffsetMs.toLong(),
            playWhenReady = true,
            qualityMode = currentQualityMode(),
        )
        beginRequest(request)
        val wasSoftwareActive = alacStreamActive
        if (wasSoftwareActive) {
            alacClock.bumpGeneration()
        }
        syncAlacStreamActive(true)
        if (!wasSoftwareActive) {
            composite?.pauseExoForAlac()
        }
        persistedSessionSongId = null
        currentIndex = index
        publishPlaybackStates()
        clearPendingSeek()
        val restoreMs = startOffsetMs.takeIf { it > 0 }
            ?: pendingRestorePositionMs?.takeIf { it >= 1_000 }
            ?: 0
        pendingRestorePositionMs = null
        sessionRestoreSeekPending = restoreMs > 0
        val metaDurationMs = song.durationSec.coerceAtLeast(0) * 1000L
        alacClock.resetForNewTrack(metaDurationMs)
        if (restoreMs > 0) {
            alacClock.pinInitialPosition(restoreMs.toLong())
            setPositionMsClamped(restoreMs)
        }
        alacPlayWhenReady = true
        applyAlacClockToUi()
        durationSec = song.durationSec.coerceAtLeast(0)
        syncSessionQueueForAlac(index)
        syncAlacFromClock(flushTimeline = true)

        DiagnosticLog.event(
            "Player",
            "engine.play index=$index generation=${alacClock.generation}; song=${song.id} ${song.title}",
        )
        engine.play(song, createSoftwareCallback(request.id), startOffsetMs = restoreMs)
    }

    private fun createSoftwareCallback(requestId: Long): AlacAudioTrackEngine.Callback =
        object : AlacAudioTrackEngine.Callback {
            private fun isStale(): Boolean =
                activeRequest?.id != requestId || !alacStreamActive

            override fun onPrepared(durationSec: Int) {
                if (isStale()) return
                TrackSwitchPerformance.mark("audio-prepared", "durationSec=$durationSec")
                DiagnosticLog.event(
                    "Player",
                    "engine prepared durationSec=$durationSec; ${playbackSnapshot()}",
                )
                val gen = alacClock.generation
                alacClock.applyPrepared(gen, durationSec)
                if (durationSec > 0) this@PlayerController.durationSec = durationSec
                if (sessionRestoreSeekPending) {
                    sessionRestoreSeekPending = false
                    alacClock.releaseSeekAnchor()
                }
                applyAlacClockToUi()
                syncAlacFromClock(flushTimeline = true)
            }

            override fun onPositionMs(positionMs: Int) {
                if (isStale()) return
                markPlaybackStable(requestId, positionMs.toLong())
                val gen = alacClock.generation
                val maxMs = maxDurationMs().toLong()
                val applied = alacClock.applyPosition(gen, positionMs.toLong(), maxMs)
                if (applied == null) return
                reconcileAlacPending(applied.toInt())
                applyAlacClockToUi()
                syncAlacFromClock(flushTimeline = false)
            }

            override fun onPlayingChanged(playing: Boolean) {
                if (isStale()) return
                if (playing) TrackSwitchPerformance.mark("audio-playing")
                val gen = alacClock.generation
                alacClock.applyPlaying(gen, playing)
                activeRequest?.takeIf { it.id == requestId }?.let { request ->
                    engineState = if (playing) {
                        PlaybackEngineState.Playing(request, alacClock.positionMs)
                    } else {
                        PlaybackEngineState.Paused(request, alacClock.positionMs)
                    }
                }
                if (playing && alacPendingSeekMs >= 0) {
                    reconcileAlacPending(alacClock.positionMs.toInt())
                }
                applyAlacClockToUi()
                syncAlacFromClock(flushTimeline = true)
            }

            override fun onBuffering(buffering: Boolean) {
                if (isStale()) return
                val gen = alacClock.generation
                alacClock.applyBuffering(gen, buffering)
                if (!buffering && !alacPlayWhenReady) {
                    alacClock.applyPlayWhenReady(false)
                    alacClock.applyPlaying(gen, false)
                }
                if (!buffering) {
                    if (sessionRestoreSeekPending) {
                        sessionRestoreSeekPending = false
                        alacClock.releaseSeekAnchor()
                    }
                    if (alacPendingSeekMs >= 0 &&
                        kotlin.math.abs(alacClock.positionMs - alacPendingSeekMs) <= 800
                    ) {
                        alacPendingSeekMs = -1
                    }
                }
                applyAlacClockToUi()
                syncAlacFromClock(flushTimeline = true)
            }

            override fun onEnded() {
                if (isStale()) return
                DiagnosticLog.event("Player", "engine ended; ${playbackSnapshot()}")
                val gen = alacClock.generation
                syncAlacStreamActive(false)
                isPlaying = false
                alacPlayWhenReady = false
                playbackBackend.compositePlayer?.endAlacSession()
                publishPlaybackStates()
                playNextAfterStream()
            }

            override fun onError(message: String) {
                if (isStale()) return
                DiagnosticLog.event("Player", "engine error=$message; ${playbackSnapshot()}")
                val gen = alacClock.generation
                clearPendingSeek()
                syncAlacStreamActive(false)
                isBuffering = false
                isPlaying = false
                alacPlayWhenReady = false
                playbackBackend.compositePlayer?.endAlacSession()
                playbackError = message
                activeRequest?.takeIf { it.id == requestId }?.let { request ->
                    engineState = PlaybackEngineState.Failed(
                        request,
                        PlaybackFailure(PlaybackFailureKind.DECODE_FAILED, message),
                    )
                }
                postUserMessage(message)
                publishPlaybackStates()
                consecutivePlaybackFailures++
                if (consecutivePlaybackFailures < MAX_CONSECUTIVE_PLAYBACK_FAILURES) {
                    val next = resolveNextIndex(forManualSkip = false)
                    if (next != currentIndex) {
                        postUserMessage("无法播放，已跳过")
                        playSong(next)
                    }
                } else {
                    postUserMessage("连续多首歌曲无法播放，已暂停")
                }
            }
        }

    private fun markPlaybackStable(requestId: Long?, positionMs: Long) {
        if (requestId != null &&
            activeRequest?.id == requestId &&
            positionMs >= STABLE_PLAYBACK_RESET_MS
        ) {
            consecutivePlaybackFailures = 0
        }
    }

    private fun beginRequest(request: PlaybackRequest) {
        val previous = activeRequest
        engineState = if (previous == null) {
            PlaybackEngineState.Preparing(request)
        } else {
            PlaybackEngineState.Switching(previous.id, request)
        }
        activeRequest = request
        engineState = PlaybackEngineState.Preparing(request)
    }

    private fun currentQualityMode(): AudioQualityMode =
        if (com.mica.music.data.AppPreferences.equalizerEnabled(appCtx)) {
            AudioQualityMode.DSP
        } else {
            AudioQualityMode.HIFI
        }

    */

    fun cyclePlaybackQueueMode() {
        val nextMode = playbackQueueMode.next()
        controller?.let { applyPlaybackQueueMode(it, nextMode) }
        playbackQueueMode = nextMode
        publishSurfaceState()
    }

    private fun applyPlaybackQueueMode(c: MediaController, mode: PlaybackQueueMode = playbackQueueMode) {
        when (mode) {
            PlaybackQueueMode.OFF -> {
                c.shuffleModeEnabled = false
                c.repeatMode = Player.REPEAT_MODE_OFF
            }
            PlaybackQueueMode.REPEAT_ALL -> {
                c.shuffleModeEnabled = false
                c.repeatMode = Player.REPEAT_MODE_ALL
            }
            PlaybackQueueMode.REPEAT_ONE -> {
                c.shuffleModeEnabled = false
                c.repeatMode = Player.REPEAT_MODE_ONE
            }
            PlaybackQueueMode.SHUFFLE -> {
                c.shuffleModeEnabled = true
                c.repeatMode = Player.REPEAT_MODE_OFF
            }
        }
    }

    private fun syncPlaybackQueueModeFromPlayer(c: Player) {
        playbackQueueMode = when {
            c.shuffleModeEnabled -> PlaybackQueueMode.SHUFFLE
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
        DiagnosticLog.event(
            "Player",
            "automatic next target=$next; ${playbackSnapshot()}",
        )
        TrackSwitchPerformance.armTrigger("auto-next")
        trackSkipDirection = TrackSkipDirection.TO_NEXT
        playSong(next)
    }

    private fun resolveNextIndex(forManualSkip: Boolean): Int {
        return PlaybackQueueNavigator.nextIndex(
            mode = playbackQueueMode,
            currentIndex = currentIndex,
            queueSize = songQueue.size,
            manualSkip = forManualSkip,
            randomIndex = ::randomIndexExcept,
        )
    }

    private fun resolvePreviousIndex(): Int {
        return PlaybackQueueNavigator.previousIndex(
            mode = playbackQueueMode,
            currentIndex = currentIndex,
            queueSize = songQueue.size,
            randomIndex = ::randomIndexExcept,
        )
    }

    private fun randomIndexExcept(exclude: Int): Int {
        if (songQueue.size <= 1) return exclude.coerceIn(0, (songQueue.size - 1).coerceAtLeast(0))
        var pick = exclude
        while (pick == exclude) {
            pick = Random.nextInt(songQueue.size)
        }
        return pick
    }

    /*
     * Legacy software-session command bridge. MediaController now routes these commands to
     * ServicePlaybackEngineCoordinator through MicaCompositePlayer.
    private fun pausePlaybackInternal(engine: AlacAudioTrackEngine? = alacEngine) {
        if (alacStreamActive) {
            alacPlayWhenReady = false
            alacClock.applyPlayWhenReady(false)
            alacClock.applyPlaying(alacClock.generation, false)
            applyAlacClockToUi()
            syncAlacFromClock(flushTimeline = true)
            engine?.pause()
            publishPlaybackStates()
            return
        }
        if (isPlaying) {
            isPlaying = false
            isBuffering = false
            publishSurfaceState()
        }
    }

    private fun stopAlacStream() {
        val wasAlac = alacStreamActive ||
            playbackBackend.compositePlayer?.isAlacActive == true
        stopAlacEngineOnly()
        if (wasAlac) {
            playbackBackend.compositePlayer?.endAlacSession()
        }
    }

    /** 同步 MediaSession 元数据队列（无 URI，不解码）；音频仍由 AudioTrack 输出。 */
    private fun syncSessionQueueForAlac(index: Int) {
        if (songQueue.isEmpty()) return
        val safe = index.coerceIn(0, songQueue.lastIndex)
        if (alacSessionQueueRef === songQueue) {
            playbackBackend.compositePlayer?.setAlacSessionIndex(safe)
            return
        }
        val items = songQueue.map { it.toSessionMediaItem() }
        playbackBackend.compositePlayer?.syncAlacSessionQueue(items, safe)
        alacSessionQueueRef = songQueue
    }

    private fun createAlacSessionHandler(): AlacSessionCommandHandler =
        object : AlacSessionCommandHandler {
            override fun onPlay() {
                if (!alacStreamActive) {
                    if (songQueue.isNotEmpty()) playSong(currentIndex)
                    return
                }
                alacPlayWhenReady = true
                alacClock.applyPlayWhenReady(true)
                applyAlacClockToUi()
                syncAlacFromClock(flushTimeline = true)
                if (!isPlaying) alacEngine?.resumeOrRestart()
            }

            override fun onPause() {
                if (!alacStreamActive) return
                alacPlayWhenReady = false
                alacClock.applyPlayWhenReady(false)
                alacClock.applyPlaying(alacClock.generation, false)
                applyAlacClockToUi()
                syncAlacFromClock(flushTimeline = true)
                alacEngine?.pause()
            }

            override fun onSeekTo(positionMs: Long) {
                if (alacStreamActive) seekToMs(positionMs.toInt().coerceAtLeast(0))
            }

            override fun onSkipToNext() {
                next()
            }

            override fun onSkipToPrevious() {
                previous()
            }
        }

    */

    fun next() {
        playbackError = null
        if (songQueue.isEmpty()) return
        controller?.let { applyPlaybackQueueMode(it) }
        val target = resolveNextIndex(forManualSkip = true)
        DiagnosticLog.event("Player", "manual next target=$target; ${playbackSnapshot()}")
        if (target == currentIndex) return
        TrackSwitchPerformance.armTrigger("button-next")
        trackSkipDirection = TrackSkipDirection.TO_NEXT
        playSong(target)
    }

    fun previous() {
        playbackError = null
        if (songQueue.isEmpty()) return
        if (!alacStreamActive && positionMs > 3_000) {
            DiagnosticLog.event("Player", "previous restarted current song; ${playbackSnapshot()}")
            seekToMs(0)
            return
        }
        controller?.let { applyPlaybackQueueMode(it) }
        val target = resolvePreviousIndex()
        DiagnosticLog.event("Player", "manual previous target=$target; ${playbackSnapshot()}")
        TrackSwitchPerformance.armTrigger("button-prev")
        trackSkipDirection = TrackSkipDirection.TO_PREVIOUS
        playSong(target)
    }

    private fun playbackSnapshot(): String =
        "index=$currentIndex/${songQueue.size}; current=${currentSong?.id}; " +
            "mode=$playbackQueueMode; playing=$isPlaying; buffering=$isBuffering; " +
            "alac=$alacStreamActive; positionMs=$positionMs"

    fun seek(seconds: Int) = seekToMs(seconds * 1000)

    fun seekToMs(targetMs: Int) {
        val maxMs = maxDurationMs()
        val safe = if (maxMs > 0) targetMs.coerceIn(0, maxMs) else targetMs.coerceAtLeast(0)
        val activeController = controller
        DiagnosticLog.event(
            "Player",
            "seek song=${currentSong?.id} targetMs=$safe durationMs=$maxMs index=$currentIndex " +
                "commandAvailable=${activeController?.availableCommands?.contains(
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                ) == true}",
        )
        pendingSeekMs = safe
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
        clearPendingMediaSelection()
        deferredPlaybackPublish?.let { mainHandler.removeCallbacks(it) }
        deferredPlaybackPublish = null
        scope.cancel()
        releaseConnectionOnly()
        songQueue = emptyList()
        currentIndex = 0
        isPlaying = false
        playbackError = null
        userMessage = null
        publishPlaybackStates()
    }

    private fun releaseConnectionOnly() {
        controllerConnection?.cancel()
        controllerConnection = null
        controller = null
        isConnected = false
        connectStarted = false
        publishPlaybackStates()
    }
}

private fun Song.toMediaMetadataBuilder(): MediaMetadata.Builder {
    val builder = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
    albumArtUri?.let { uri ->
        runCatching { builder.setArtworkUri(Uri.parse(uri)) }
    }
    return builder
}

private fun Song.toMediaItem(): MediaItem =
    com.mica.music.media.SongMediaItemCodec.encode(this)
