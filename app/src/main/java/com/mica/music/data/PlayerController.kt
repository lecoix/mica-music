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
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.mica.music.media.AlacAudioTrackEngine
import com.mica.music.media.AlacPlayback
import com.mica.music.media.AlacPlaybackCoordinator
import com.mica.music.media.MicaMediaService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * 把 MediaController 桥接成 Compose State，同时承载队列。
 * ALAC 默认走 [AlacAudioTrackEngine] 流式（原位深/采样率，临时文件播完即删）。
 */
class PlayerController(private val context: Context) {

    private val appCtx = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 开始播放某曲时回调（用于统计播放次数）。 */
    var onSongPlayStarted: ((songId: String) -> Unit)? = null

    var currentIndex by mutableIntStateOf(0)
        private set

    var isPlaying by mutableStateOf(false)
        private set

    var positionSec by mutableIntStateOf(0)
        private set

    /** 播放进度（毫秒），供歌词同步等需要 finer 粒度的 UI 使用。 */
    var positionMs by mutableIntStateOf(0)
        private set

    var durationSec by mutableIntStateOf(0)
        private set

    var isBuffering by mutableStateOf(false)
        private set

    var isConnected by mutableStateOf(false)
        private set

    /** 当前是否为 ALAC AudioTrack 流式（非 ExoPlayer） */
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

        c.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (alacStreamActive) return
                syncIndexFromPlayer(c)
                positionSec = 0
                positionMs = 0
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
                    syncIndexFromPlayer(c)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (alacStreamActive) return
                handlePlaybackError(c, error)
            }
        })

        syncIndexFromPlayer(c)
        isPlaying = c.isPlaying
        if (c.duration > 0) durationSec = (c.duration / 1000).toInt()
        isConnected = true
        applyPlaybackQueueMode(c)

        pendingQueue?.let {
            applyQueue(c, it)
            pendingQueue = null
        }
    }

    private fun handlePlaybackError(c: MediaController, error: PlaybackException) {
        val song = currentSong
        if (song != null && AlacPlayback.isAlac(song)) {
            scope.launch {
                if (AlacPlayback.useStreamPlayback(appCtx)) {
                    startAlacStream(song, currentIndex)
                    playbackError = null
                    return@launch
                }
                val ready = prepareAlacFlacCache(song)
                if (ready.playbackUri != null) {
                    stopAlacStream()
                    startPlaybackAt(c, currentIndex, ready)
                    playbackError = null
                    return@launch
                }
                finishPlaybackError(c, error)
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

    private fun syncIndexFromPlayer(c: MediaController) {
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
        syncIndexFromPlayer(c)
        syncPosition()
        isPlaying = c.isPlaying
        isBuffering = c.playbackState == Player.STATE_BUFFERING
        if (c.duration > 0) durationSec = (c.duration / 1000).toInt()
    }

    fun setQueue(newQueue: List<Song>) {
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
            if (!alacStreamActive) controller?.let { syncPlaybackState() }
            return
        }

        if (sameOrderAndIds) {
            controller?.let { applyQueue(it, newQueue, preservePlayback = true) }
            return
        }
        val c = controller
        if (c == null) {
            pendingQueue = newQueue
            if (newQueue.isEmpty()) currentIndex = 0
            return
        }
        applyQueue(c, newQueue, preservePlayback = true)
    }

    private fun applyQueue(
        c: MediaController,
        newQueue: List<Song>,
        preservePlayback: Boolean = false,
    ) {
        if (newQueue.isEmpty()) {
            stopAlacStream()
            c.clearMediaItems()
            currentIndex = 0
            isPlaying = false
            playbackError = null
            return
        }

        val playingMediaId = c.currentMediaItem?.mediaId
        val keepIndex = newQueue.indexOfFirst { it.id == playingMediaId }
        val foundOldSong = preservePlayback && keepIndex >= 0 && newQueue[keepIndex].id == playingMediaId
        val targetIndex = if (foundOldSong) keepIndex else 0
        val keepPositionMs = if (foundOldSong) c.currentPosition else 0L
        val wasPlaying = c.isPlaying

        c.setMediaItems(newQueue.map { it.toMediaItem() }, targetIndex, keepPositionMs)
        c.prepare()

        if (wasPlaying && foundOldSong && !alacStreamActive) {
            c.play()
        } else if (wasPlaying && !foundOldSong) {
            c.pause()
            isPlaying = false
            postUserMessage("当前歌曲已从库中移除")
        }

        syncIndexFromPlayer(c)
    }

    fun syncPosition() {
        if (alacStreamActive) return
        val c = controller ?: return
        if (c.duration > 0) durationSec = (c.duration / 1000).toInt()
        positionMs = c.currentPosition.toInt().coerceAtLeast(0)
        positionSec = positionMs / 1000
    }

    fun togglePlay() {
        if (alacStreamActive) {
            val engine = alacEngine ?: return
            if (isPlaying) engine.pause() else engine.resume()
            return
        }
        val c = controller ?: run {
            postUserMessage("播放器未就绪")
            return
        }
        if (songQueue.isEmpty()) return
        playbackError = null
        if (c.isPlaying) c.pause() else c.play()
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

    /** 更新内存队列；ALAC 流式播放时不同步 ExoPlayer（当前曲由独立引擎播放）。 */
    private fun applyQueueOrder(list: List<Song>, newIndex: Int) {
        currentIndex = newIndex
        songQueue = list
        val c = controller
        if (c == null) {
            pendingQueue = list
            return
        }
        if (alacStreamActive) return
        applyQueue(c, list, preservePlayback = true)
    }

    fun playSong(index: Int) {
        if (songQueue.isEmpty()) return
        val safe = index.coerceIn(0, songQueue.lastIndex)
        playbackError = null
        val song = songQueue[safe]
        currentIndex = safe
        onSongPlayStarted?.invoke(song.id)

        if (AlacPlayback.isAlac(song) && AlacPlayback.useStreamPlayback(appCtx)) {
            startAlacStream(song, safe)
            return
        }

        val c = controller ?: run {
            postUserMessage("播放器未就绪")
            return
        }

        if (AlacPlayback.isAlac(song) && song.playbackUri.isNullOrBlank()) {
            scope.launch {
                val ready = prepareAlacFlacCache(song)
                if (ready.playbackUri == null) {
                    playbackError = "ALAC 准备失败"
                    postUserMessage("ALAC 无法播放")
                    return@launch
                }
                replaceSongAt(safe, ready)
                stopAlacStream()
                startPlaybackAt(c, safe, ready)
            }
            return
        }

        stopAlacStream()
        startPlaybackAt(c, safe, song)
    }

    private fun startAlacStream(song: Song, index: Int) {
        val engine = alacEngine ?: run {
            postUserMessage("播放服务未就绪")
            return
        }
        controller?.pause()
        stopAlacStream()
        alacStreamActive = true
        currentIndex = index
        positionSec = 0
        positionMs = 0
        durationSec = song.durationSec
        isBuffering = true

        engine.play(song, object : AlacAudioTrackEngine.Callback {
            override fun onPrepared(durationSec: Int) {
                if (durationSec > 0) this@PlayerController.durationSec = durationSec
                isBuffering = false
            }

            override fun onPositionMs(positionMs: Int) {
                this@PlayerController.positionMs = positionMs.coerceAtLeast(0)
                this@PlayerController.positionSec = this@PlayerController.positionMs / 1000
            }

            override fun onPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onBuffering(buffering: Boolean) {
                isBuffering = buffering
            }

            override fun onEnded() {
                alacStreamActive = false
                isPlaying = false
                playNextAfterStream()
            }

            override fun onError(message: String) {
                alacStreamActive = false
                isBuffering = false
                isPlaying = false
                playbackError = message
                postUserMessage(message)
            }
        })
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
        alacEngine?.stop()
        alacStreamActive = false
    }

    private fun startPlaybackAt(c: MediaController, index: Int, song: Song) {
        replaceSongAt(index, song)
        val item = song.toMediaItem()
        if (c.mediaItemCount > index) {
            c.replaceMediaItem(index, item)
        } else {
            c.setMediaItem(item)
        }
        c.seekTo(index, 0)
        c.prepare()
        c.play()
        currentIndex = index
    }

    fun next() {
        playbackError = null
        if (songQueue.isEmpty()) return
        if (alacStreamActive) {
            playSong(resolveNextIndex(forManualSkip = true))
            return
        }
        val c = controller ?: run {
            postUserMessage("播放器未就绪")
            return
        }
        applyPlaybackQueueMode(c)
        when {
            playbackQueueMode == PlaybackQueueMode.SHUFFLE -> c.seekToNextMediaItem()
            c.hasNextMediaItem() -> c.seekToNextMediaItem()
            playbackQueueMode == PlaybackQueueMode.REPEAT_ALL -> c.seekTo(0, 0)
            else -> playSong(resolveNextIndex(forManualSkip = true))
        }
    }

    fun previous() {
        playbackError = null
        if (songQueue.isEmpty()) return
        if (alacStreamActive) {
            playSong(resolvePreviousIndex())
            return
        }
        val c = controller ?: run {
            postUserMessage("播放器未就绪")
            return
        }
        applyPlaybackQueueMode(c)
        when {
            playbackQueueMode == PlaybackQueueMode.SHUFFLE -> c.seekToPreviousMediaItem()
            c.hasPreviousMediaItem() -> c.seekToPreviousMediaItem()
            else -> playSong(resolvePreviousIndex())
        }
    }

    fun seek(seconds: Int) {
        val safe = seconds.coerceAtLeast(0)
        if (alacStreamActive) {
            alacEngine?.seekTo(safe)
            positionSec = safe
            positionMs = safe * 1000
            return
        }
        val c = controller ?: return
        c.seekTo(safe * 1000L)
        positionSec = safe
        positionMs = safe * 1000
    }

    fun clearUserMessage() {
        userMessage = null
    }

    fun clearPlaybackError() {
        playbackError = null
    }

    private suspend fun prepareAlacFlacCache(song: Song): Song {
        AlacPlayback.cachedFlacUri(appCtx, song.id)?.let { cached ->
            return song.copy(playbackUri = cached)
        }
        isBuffering = true
        val flacUri = withContext(Dispatchers.IO) {
            AlacPlayback.transcodeToFlac(appCtx, song)
        }
        isBuffering = false
        return if (flacUri != null) song.copy(playbackUri = flacUri) else song
    }

    private fun replaceSongAt(index: Int, song: Song) {
        if (index !in songQueue.indices) return
        songQueue = songQueue.toMutableList().also { it[index] = song }
    }

    private fun postUserMessage(text: String) {
        userMessage = UserMessage(text)
    }

    fun release() {
        scope.cancel()
        stopAlacStream()
        releaseConnectionOnly()
        songQueue = emptyList()
        currentIndex = 0
        isPlaying = false
        playbackError = null
        userMessage = null
    }

    private fun releaseConnectionOnly() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        isConnected = false
        connectStarted = false
    }
}

private fun Song.toMediaItem(): MediaItem {
    val metaBuilder = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
    albumArtUri?.let { uri ->
        runCatching { metaBuilder.setArtworkUri(Uri.parse(uri)) }
    }
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
        .setMediaMetadata(
            metaBuilder
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        )
        .build()
}
