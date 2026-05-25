package com.mica.music.data

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.mica.music.media.AlacAudioTrackEngine
import com.mica.music.media.AlacPlayback
import com.mica.music.media.AlacPlaybackClock
import com.mica.music.media.AlacPlaybackCoordinator
import com.mica.music.media.AlacSessionCommandHandler
import com.mica.music.media.MicaMediaService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 把 MediaController 桥接成 Compose State，同时承载队列。
 * 全部曲目走 [AlacAudioTrackEngine]（FFmpeg → PCM → AudioTrack）。
 */
class PlayerController(private val context: Context) {

    private val appCtx = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

    private val alacClock = AlacPlaybackClock()

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
    }

    private fun notifyPlaybackProgress(rawMs: Int) {
        setPositionMsClamped(rawMs)
        if (!alacStreamActive && pendingSeekMs >= 0 &&
            kotlin.math.abs(positionMs - pendingSeekMs) <= 800
        ) {
            pendingSeekMs = -1
        }
        maybePersistPlaybackSession()
    }

    fun persistPlaybackSessionNow() = maybePersistPlaybackSession(force = true)

    private fun maybePersistPlaybackSession(force: Boolean = false) {
        val song = currentSong ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastSessionPersistMs < 3_000L) return
        lastSessionPersistMs = now
        PlaybackSessionStore.save(
            appCtx,
            PlaybackSession(songId = song.id, positionMs = uiPositionMs()),
            sync = force,
        )
    }

    /** 曲库就绪后恢复上次播放的歌曲与进度（不自动开始播放）。 */
    fun restoreSession(session: PlaybackSession) {
        if (songQueue.isEmpty()) return
        val index = songQueue.indexOfFirst { it.id == session.songId }
        if (index < 0) {
            persistedSessionSongId = null
            PlaybackSessionStore.clear(appCtx)
            return
        }
        persistedSessionSongId = session.songId
        currentIndex = index
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
        if (index >= 0) currentIndex = index
    }

    /** 曲库与 [restoreSession] 就绪后再次对齐索引，避免 [onConnected] 与恢复竞态。 */
    fun reconcileRestoredSessionIndex() {
        reapplyPersistedSessionIndex()
        pendingRestorePositionMs?.let { setPositionMsClamped(it) }
    }

    private fun clearPendingSeek() {
        pendingSeekMs = -1
        alacPendingSeekMs = -1
    }

    /** 播放进度与 seek 目标接近（±800ms）后才解除 UI 钉住；超前时不松开，避免松手后条往右跳。 */
    private fun reconcileAlacPending(appliedMs: Int) {
        val pending = alacPendingSeekMs
        if (pending < 0) return
        if (kotlin.math.abs(appliedMs - pending) <= 800) {
            alacPendingSeekMs = -1
        }
    }

    private fun playbackPlayer(): Player? =
        AlacPlaybackCoordinator.compositePlayer ?: controller

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

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var pendingQueue: List<Song>? = null
    private var connectStarted = false
    private var pendingRestorePositionMs: Int? = null
    /** 冷启动恢复前 MediaSession 尚未对应该曲，避免 [onConnected] 把索引打回 0。 */
    private var persistedSessionSongId: String? = null
    private var lastSessionPersistMs: Long = 0L
    /** 本次开播是否带会话恢复进度（与拖动 seek 的 [alacPendingSeekMs] 区分）。 */
    private var sessionRestoreSeekPending = false

    private val alacEngine: AlacAudioTrackEngine?
        get() = AlacPlaybackCoordinator.engine

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
        val token = SessionToken(
            appCtx,
            ComponentName(appCtx, MicaMediaService::class.java),
        )
        val future = MediaController.Builder(appCtx, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                runCatching {
                    onConnected(future.get())
                }.onFailure {
                    connectStarted = false
                    postUserMessage("无法连接播放服务，请稍后重试")
                }
            },
            ContextCompat.getMainExecutor(appCtx),
        )
    }

    private fun onConnected(c: MediaController) {
        controller = c
        AlacPlaybackCoordinator.sessionHandler = createAlacSessionHandler()

        c.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (alacStreamActive) return
                if (ignoreExoIndexSync()) return
                syncIndexFromPlayer(c)
                clearPendingSeek()
                setPositionMsClamped(0)
                playbackError = null
                if (c.duration > 0) durationSec = (c.duration / 1000).toInt()
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                if (alacStreamActive) return
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (alacStreamActive) return
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY && c.duration > 0) {
                    durationSec = (c.duration / 1000).toInt()
                }
                if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                    if (!ignoreExoIndexSync()) syncIndexFromPlayer(c)
                }
            }

            @UnstableApi
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (alacStreamActive) return
                if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                    reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                ) {
                    pendingSeekMs = -1
                    notifyPlaybackProgress(newPosition.positionMs.toInt().coerceAtLeast(0))
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (alacStreamActive) return
                handlePlaybackError(c, error)
            }
        })

        isConnected = true
        applyPlaybackQueueMode(c)

        pendingQueue?.let {
            applyQueue(c, it, preservePlayback = true)
            pendingQueue = null
        }

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
    }

    private fun handlePlaybackError(c: MediaController, error: PlaybackException) {
        val song = currentSong
        if (song != null) {
            scope.launch {
                startAlacStream(song, currentIndex)
                playbackError = null
            }
            return
        }
        finishPlaybackError(c, error)
    }

    private fun finishPlaybackError(c: MediaController, error: PlaybackException) {
        val title = currentSong?.title
        val message = PlaybackErrorMapper.toUserMessage(error, title)
        playbackError = message
        postUserMessage(message)
        isPlaying = false

        if (c.hasNextMediaItem()) {
            c.seekToNextMediaItem()
            postUserMessage("无法播放，已跳过")
        } else {
            c.pause()
        }
    }

    /** 冷启动已恢复曲目、尚未真正开播前，不信任 Exo/MediaSession 的索引（多为 0）。 */
    private fun ignoreExoIndexSync(): Boolean = persistedSessionSongId != null

    private fun syncIndexFromPlayer(c: MediaController) {
        if (ignoreExoIndexSync()) {
            reapplyPersistedSessionIndex()
            return
        }
        if (songQueue.isEmpty()) {
            currentIndex = 0
            return
        }
        val idx = c.currentMediaItemIndex
        currentIndex = when {
            idx in songQueue.indices -> idx
            else -> currentIndex.coerceIn(0, songQueue.lastIndex)
        }
    }

    fun syncPlaybackState() {
        if (alacStreamActive) return
        val c = controller ?: return
        if (ignoreExoIndexSync()) {
            reapplyPersistedSessionIndex()
            pendingRestorePositionMs?.let { setPositionMsClamped(it) }
            return
        }
        syncIndexFromPlayer(c)
        syncPosition()
        isPlaying = c.isPlaying
        isBuffering = c.playbackState == Player.STATE_BUFFERING
        if (c.duration > 0) durationSec = (c.duration / 1000).toInt()
    }

    fun setQueue(newQueue: List<Song>) {
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

        songQueue = newQueue

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
            return
        }
        applyQueue(c, newQueue, preservePlayback = true, preserveSongId = preserveId)
    }

    private fun applyPreserveIndexForQueue(newQueue: List<Song>, preserveSongId: String?) {
        val preserveId = preserveSongId ?: return
        val keepIndex = newQueue.indexOfFirst { it.id == preserveId }
        if (keepIndex >= 0) currentIndex = keepIndex
    }

    @Suppress("UNUSED_PARAMETER")
    private fun applyQueue(
        c: MediaController,
        newQueue: List<Song>,
        preservePlayback: Boolean = false,
        preserveSongId: String? = preserveSongIdForQueue(),
    ) {
        if (newQueue.isEmpty()) {
            stopAlacStream()
            currentIndex = 0
            isPlaying = false
            playbackError = null
            PlaybackSessionStore.clear(appCtx)
            return
        }

        val playingId = preserveSongId
        val keepIndex = playingId?.let { id -> newQueue.indexOfFirst { it.id == id } } ?: -1
        val foundOldSong = preservePlayback && keepIndex >= 0
        currentIndex = if (foundOldSong) keepIndex else 0
        if (alacStreamActive) {
            syncSessionQueueForAlac(currentIndex)
            AlacPlaybackCoordinator.compositePlayer?.setAlacSessionIndex(currentIndex)
        } else if (wasPlayingBeforeQueueChange(c) && !foundOldSong) {
            isPlaying = false
            postUserMessage("当前歌曲已从库中移除")
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

    fun togglePlay() {
        if (songQueue.isEmpty()) return
        val engine = alacEngine ?: run {
            connectIfNeeded()
            postUserMessage("播放服务未就绪")
            return
        }
        playbackError = null

        if (isPlaying && alacStreamActive) {
            alacPlayWhenReady = false
            alacClock.applyPlayWhenReady(false)
            alacClock.applyPlaying(alacClock.generation, false)
            applyAlacClockToUi()
            syncAlacFromClock(flushTimeline = true)
            engine.pause()
            return
        }
        if (isPlaying) {
            isPlaying = false
            isBuffering = false
        }

        if (alacStreamActive) {
            alacPlayWhenReady = true
            alacClock.applyPlayWhenReady(true)
            applyAlacClockToUi()
            syncAlacFromClock(flushTimeline = true)
            engine.resumeOrRestart()
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

        if (alacStreamActive) {
            songQueue = list
            currentIndex = playIndex
            postUserMessage("已加入下一首播放")
            return
        }

        applyQueueOrder(list, playIndex)
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
        applyQueueOrder(list, newCurrent)
    }

    fun removeFromQueue(index: Int) {
        if (index !in songQueue.indices) return
        val removingCurrent = index == currentIndex
        val wasPlaying = isPlaying
        val list = songQueue.toMutableList()
        list.removeAt(index)
        if (list.isEmpty()) {
            stopAlacStream()
            songQueue = emptyList()
            currentIndex = 0
            isPlaying = false
            playbackError = null
            controller?.clearMediaItems()
            return
        }
        val newIndex = when {
            index < currentIndex -> currentIndex - 1
            index == currentIndex -> index.coerceAtMost(list.lastIndex)
            else -> currentIndex
        }.coerceIn(0, list.lastIndex)
        applyQueueOrder(list, newIndex)
        if (removingCurrent) {
            if (wasPlaying) playSong(newIndex) else currentIndex = newIndex
        }
    }

    /** 更新内存队列；音频由 [AlacAudioTrackEngine] 输出，仅同步 MediaSession 元数据。 */
    private fun applyQueueOrder(list: List<Song>, newIndex: Int) {
        currentIndex = newIndex
        songQueue = list
        if (controller == null) {
            pendingQueue = list
            return
        }
        if (alacStreamActive) {
            syncSessionQueueForAlac(newIndex)
            AlacPlaybackCoordinator.compositePlayer?.setAlacSessionIndex(newIndex)
        }
    }

    fun playSong(index: Int) {
        if (songQueue.isEmpty()) return
        val safe = index.coerceIn(0, songQueue.lastIndex)
        playbackError = null
        val song = songQueue[safe]
        if (song.id != persistedSessionSongId) {
            pendingRestorePositionMs = null
        }
        currentIndex = safe
        onSongPlayStarted?.invoke(song.id)
        maybePersistPlaybackSession(force = true)
        if (alacEngine == null) {
            postUserMessage("播放服务未就绪")
            return
        }
        startAlacStream(song, safe)
    }

    private fun stopAlacEngineOnly() {
        sessionRestoreSeekPending = false
        alacClock.bumpGeneration()
        alacEngine?.stop()
        syncAlacStreamActive(false)
        alacPlayWhenReady = false
        isBuffering = false
        AlacPlaybackCoordinator.compositePlayer?.dropAlacSessionState()
    }

    private fun syncAlacStreamActive(active: Boolean) {
        alacStreamActive = active
        AlacPlaybackCoordinator.alacStreamActive = active
    }

    /** ALAC 流式时用户「想播」意图，与 [isPlaying]（实际在播）区分，供 MediaSession。 */
    private var alacPlayWhenReady = false

    private fun isAlacCallbackStale(observedGeneration: Int): Boolean =
        alacClock.isStale(observedGeneration) || !alacStreamActive

    private fun applyAlacClockToUi(updatePosition: Boolean = true) {
        if (updatePosition && !alacSeekUiActive) {
            setPositionMsClamped(alacClock.positionMs.toInt())
        }
        durationSec = (alacClock.durationMs / 1000).toInt().coerceAtLeast(durationSec)
        isPlaying = alacClock.isPlaying
        isBuffering = alacClock.buffering
        alacPlayWhenReady = alacClock.playWhenReady
    }

    private fun syncAlacFromClock(flushTimeline: Boolean) {
        if (!alacStreamActive) return
        val composite = AlacPlaybackCoordinator.compositePlayer ?: return
        val state = alacClock.toSessionState().copy(
            durationMs = maxOf(alacClock.durationMs, maxDurationMs().toLong()),
        )
        if (flushTimeline) {
            composite.publishAlacState(state)
        } else if (!alacSeekUiActive) {
            val publishMs = alacPendingSeekMs.takeIf { it >= 0 }?.toLong() ?: state.positionMs
            composite.publishAlacPosition(
                positionMs = publishMs,
                durationMs = state.durationMs,
            )
        }
    }

    private fun startAlacStream(song: Song, index: Int) {
        val engine = alacEngine ?: run {
            postUserMessage("播放服务未就绪")
            return
        }
        if (!com.mica.music.media.FfmpegRunner.hasEmbeddedBinary(appCtx)) {
            playbackError = "未找到 FFmpeg"
            postUserMessage("未找到 FFmpeg，请在电脑上运行 scripts\\build-ffmpeg-arm64.ps1 后重新安装")
            return
        }
        val composite = AlacPlaybackCoordinator.compositePlayer
        if (alacStreamActive) {
            alacClock.bumpGeneration()
        }
        alacEngine?.stop()
        if (!alacStreamActive) {
            composite?.pauseExoForAlac()
        }
        syncAlacStreamActive(true)
        persistedSessionSongId = null
        currentIndex = index
        clearPendingSeek()
        val restoreMs = pendingRestorePositionMs?.takeIf { it >= 1_000 } ?: 0
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
        AlacPlaybackCoordinator.compositePlayer?.setAlacSessionIndex(index)
        syncAlacFromClock(flushTimeline = true)

        engine.play(song, createAlacCallback(), startOffsetMs = restoreMs)
    }

    private fun createAlacCallback(): AlacAudioTrackEngine.Callback =
        object : AlacAudioTrackEngine.Callback {
            override fun onPrepared(durationSec: Int) {
                val gen = alacClock.generation
                if (isAlacCallbackStale(gen)) return
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
                val gen = alacClock.generation
                if (isAlacCallbackStale(gen)) return
                val maxMs = maxDurationMs().toLong()
                val applied = alacClock.applyPosition(gen, positionMs.toLong(), maxMs)
                if (applied == null) return
                reconcileAlacPending(applied.toInt())
                applyAlacClockToUi()
                syncAlacFromClock(flushTimeline = false)
            }

            override fun onPlayingChanged(playing: Boolean) {
                val gen = alacClock.generation
                if (isAlacCallbackStale(gen)) return
                alacClock.applyPlaying(gen, playing)
                if (playing && alacPendingSeekMs >= 0) {
                    reconcileAlacPending(alacClock.positionMs.toInt())
                }
                applyAlacClockToUi()
                syncAlacFromClock(flushTimeline = true)
            }

            override fun onBuffering(buffering: Boolean) {
                val gen = alacClock.generation
                if (isAlacCallbackStale(gen)) return
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
                val gen = alacClock.generation
                if (isAlacCallbackStale(gen)) return
                syncAlacStreamActive(false)
                isPlaying = false
                alacPlayWhenReady = false
                AlacPlaybackCoordinator.compositePlayer?.endAlacSession()
                playNextAfterStream()
            }

            override fun onError(message: String) {
                val gen = alacClock.generation
                if (isAlacCallbackStale(gen)) return
                clearPendingSeek()
                syncAlacStreamActive(false)
                isBuffering = false
                isPlaying = false
                alacPlayWhenReady = false
                AlacPlaybackCoordinator.compositePlayer?.endAlacSession()
                playbackError = message
                postUserMessage(message)
            }
        }

    fun cyclePlaybackQueueMode() {
        playbackQueueMode = playbackQueueMode.next()
        controller?.let { applyPlaybackQueueMode(it) }
    }

    private fun applyPlaybackQueueMode(c: MediaController) {
        when (playbackQueueMode) {
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

    private fun playNextAfterStream() {
        if (songQueue.isEmpty()) return
        val next = resolveNextIndex(forManualSkip = false)
        if (playbackQueueMode == PlaybackQueueMode.OFF && next == currentIndex) return
        trackSkipDirection = TrackSkipDirection.TO_NEXT
        playSong(next)
    }

    private fun resolveNextIndex(forManualSkip: Boolean): Int {
        if (songQueue.isEmpty()) return 0
        val last = songQueue.lastIndex
        return when (playbackQueueMode) {
            PlaybackQueueMode.REPEAT_ONE ->
                if (forManualSkip) {
                    if (currentIndex < last) currentIndex + 1 else 0
                } else {
                    currentIndex
                }
            PlaybackQueueMode.SHUFFLE -> randomIndexExcept(currentIndex)
            PlaybackQueueMode.REPEAT_ALL ->
                if (currentIndex < last) currentIndex + 1 else 0
            PlaybackQueueMode.OFF ->
                if (forManualSkip) {
                    if (currentIndex < last) currentIndex + 1 else 0
                } else {
                    if (currentIndex < last) currentIndex + 1 else currentIndex
                }
        }
    }

    private fun resolvePreviousIndex(): Int {
        if (songQueue.isEmpty()) return 0
        val last = songQueue.lastIndex
        return when (playbackQueueMode) {
            PlaybackQueueMode.SHUFFLE -> randomIndexExcept(currentIndex)
            PlaybackQueueMode.REPEAT_ONE,
            PlaybackQueueMode.REPEAT_ALL,
            PlaybackQueueMode.OFF,
            -> if (currentIndex > 0) currentIndex - 1 else last
        }
    }

    private fun randomIndexExcept(exclude: Int): Int {
        if (songQueue.size <= 1) return exclude.coerceIn(0, (songQueue.size - 1).coerceAtLeast(0))
        var pick = exclude
        while (pick == exclude) {
            pick = Random.nextInt(songQueue.size)
        }
        return pick
    }

    private fun stopAlacStream() {
        val wasAlac = alacStreamActive ||
            AlacPlaybackCoordinator.compositePlayer?.isAlacActive == true
        stopAlacEngineOnly()
        if (wasAlac) {
            AlacPlaybackCoordinator.compositePlayer?.endAlacSession()
        }
    }

    /** 同步 MediaSession 元数据队列（无 URI，不解码）；音频仍由 AudioTrack 输出。 */
    private fun syncSessionQueueForAlac(index: Int) {
        if (songQueue.isEmpty()) return
        val safe = index.coerceIn(0, songQueue.lastIndex)
        val items = songQueue.map { it.toSessionMediaItem() }
        AlacPlaybackCoordinator.compositePlayer?.syncAlacSessionQueue(items, safe)
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

    fun next() {
        playbackError = null
        if (songQueue.isEmpty()) return
        controller?.let { applyPlaybackQueueMode(it) }
        trackSkipDirection = TrackSkipDirection.TO_NEXT
        playSong(resolveNextIndex(forManualSkip = true))
    }

    fun previous() {
        playbackError = null
        if (songQueue.isEmpty()) return
        if (!alacStreamActive && positionMs > 3_000) {
            seekToMs(0)
            return
        }
        controller?.let { applyPlaybackQueueMode(it) }
        trackSkipDirection = TrackSkipDirection.TO_PREVIOUS
        playSong(resolvePreviousIndex())
    }

    fun seek(seconds: Int) = seekToMs(seconds * 1000)

    fun seekToMs(targetMs: Int) {
        val maxMs = maxDurationMs()
        val safe = if (maxMs > 0) targetMs.coerceIn(0, maxMs) else targetMs.coerceAtLeast(0)
        if (alacStreamActive) {
            alacPendingSeekMs = safe
            alacClock.beginSeek(safe.toLong(), alacPlayWhenReady)
            alacClock.ensureDurationMs(maxMs.toLong())
            setPositionMsClamped(safe)
            applyAlacClockToUi(updatePosition = false)
            syncAlacFromClock(flushTimeline = true)
            alacEngine?.seekToMs(safe, startPlayback = alacPlayWhenReady)
            maybePersistPlaybackSession(force = true)
            return
        }
        pendingSeekMs = safe
        setPositionMsClamped(safe)
        playbackPlayer()?.seekTo(safe.toLong()) ?: return
        maybePersistPlaybackSession(force = true)
    }

    fun clearUserMessage() {
        userMessage = null
    }

    fun clearPlaybackError() {
        playbackError = null
    }

    private fun postUserMessage(text: String) {
        userMessage = UserMessage(text)
    }

    fun release() {
        scope.cancel()
        stopAlacStream()
        syncAlacStreamActive(false)
        AlacPlaybackCoordinator.alacStreamActive = false
        releaseConnectionOnly()
        songQueue = emptyList()
        currentIndex = 0
        isPlaying = false
        playbackError = null
        userMessage = null
    }

    private fun releaseConnectionOnly() {
        AlacPlaybackCoordinator.sessionHandler = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        isConnected = false
        connectStarted = false
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

/** 锁屏 / MediaSession 专用：仅元数据，不带 URI，避免 Exo 尝试解码 ALAC。 */
private fun Song.toSessionMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(toMediaMetadataBuilder().build())
        .build()

private fun Song.toMediaItem(): MediaItem {
    val playUri = effectivePlaybackUri
    val mime = when {
        playUri.contains(".flac", ignoreCase = true) -> MimeTypes.AUDIO_FLAC
        playUri.contains(".wav", ignoreCase = true) -> MimeTypes.AUDIO_WAV
        else -> metadata.playbackMimeType.takeIf { it.isNotBlank() } ?: MimeTypes.APPLICATION_MP4
    }
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(playUri)
        .setMimeType(mime)
        .setMediaMetadata(toMediaMetadataBuilder().build())
        .build()
}
