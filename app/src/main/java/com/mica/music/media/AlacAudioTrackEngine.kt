package com.mica.music.media

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import com.mica.music.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ALAC：FFmpeg 解码为裸 PCM（优先）或 WAV/FLAC 兜底，PCM 走 [AudioTrack]。
 */
class AlacAudioTrackEngine(private val context: Context) {

    interface Callback {
        fun onPrepared(durationSec: Int)
        fun onPositionMs(positionMs: Int)
        fun onPlayingChanged(playing: Boolean)
        fun onBuffering(buffering: Boolean)
        fun onEnded()
        fun onError(message: String)
    }

    private val appCtx = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaPlayer: MediaPlayer? = null
    private val pcmPlayer = AlacPcmPlayer(scope)
    private var playJob: Job? = null
    private var progressJob: Job? = null
    private var decodedFile: File? = null
    private var tempInput: File? = null
    private var callback: Callback? = null
    private var currentSong: Song? = null
    private var pcmFormat: AlacPcmFormat? = null
    private var durationSec: Int = 0
    private var paused = false
    private var stopRequested = false
    private var usingPcm = false

    fun play(song: Song, listener: Callback) {
        AlacFfmpegHelper.init(appCtx)
        stop()
        callback = listener
        currentSong = song
        pcmFormat = AlacPcmFormat.fromSong(song)
        stopRequested = false
        paused = false
        listener.onBuffering(true)

        playJob = scope.launch {
            val (decoded, failHint) = withContext(Dispatchers.IO) {
                runCatching { decodeWithHint(song) }
                    .getOrElse { e ->
                        null to (e.message ?: e.javaClass.simpleName)
                    }
            }
            if (decoded == null || stopRequested) {
                listener.onBuffering(false)
                if (!stopRequested) {
                    val detail = failHint?.let { "：$it" }.orEmpty()
                    listener.onError("ALAC 解码失败$detail")
                }
                cleanupTemp()
                return@launch
            }
            decodedFile = decoded.file
            durationSec = song.durationSec
            listener.onBuffering(false)
            startDecodedPlayback(decoded)
        }
    }

    fun pause() {
        if (!paused) {
            paused = true
            if (usingPcm) {
                pcmPlayer.pause()
            } else {
                mediaPlayer?.pause()
            }
            callback?.onPlayingChanged(false)
        }
    }

    fun resume() {
        if (paused) {
            paused = false
            if (usingPcm) {
                pcmPlayer.resume()
            } else {
                mediaPlayer?.start()
            }
            callback?.onPlayingChanged(true)
        }
    }

    fun seekTo(seconds: Int) {
        val song = currentSong ?: return
        val cb = callback ?: return
        stopPlaybackOnly()
        cb.onBuffering(true)
        playJob = scope.launch {
            val (decoded, failHint) = withContext(Dispatchers.IO) {
                runCatching { decodeWithHint(song, seekSec = seconds) }
                    .getOrElse { e ->
                        null to (e.message ?: e.javaClass.simpleName)
                    }
            }
            if (decoded == null || stopRequested) {
                cb.onBuffering(false)
                if (!stopRequested) {
                    val detail = failHint?.let { "：$it" }.orEmpty()
                    cb.onError("跳转失败$detail")
                }
                cleanupTemp()
                return@launch
            }
            decodedFile = decoded.file
            cb.onBuffering(false)
            startDecodedPlayback(decoded, startAtMs = seconds * 1000)
        }
    }

    fun stop() {
        stopRequested = true
        stopPlaybackOnly()
        cleanupTemp()
        callback = null
        currentSong = null
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private fun decodeWithHint(song: Song, seekSec: Int = 0): Pair<AlacFfmpegHelper.DecodeResult?, String?> {
        cleanupTemp()
        val format = pcmFormat ?: AlacPcmFormat.fromSong(song)
        val input = copyUriToTemp(Uri.parse(song.mediaUri), song.id)
            ?: return null to "无法读取源文件"
        tempInput = input
        val base = File(appCtx.cacheDir, "alac_stream/${song.id}_${System.currentTimeMillis()}")
        base.parentFile?.mkdirs()
        val result = AlacFfmpegHelper.decodeAlac(
            input,
            base,
            format,
            seekSec,
            AlacFfmpegHelper.OutputPreference.STREAM_PCM,
        )
        return if (result != null) {
            result to null
        } else {
            null to (AlacFfmpegHelper.lastFailureHint ?: "FFmpeg 无法解码此 ALAC")
        }
    }

    private fun startDecodedPlayback(
        decoded: AlacFfmpegHelper.DecodeResult,
        startAtMs: Int = 0,
    ) {
        when (decoded.kind) {
            AlacFfmpegHelper.OutputKind.PCM -> startPcmPlayback(decoded)
            AlacFfmpegHelper.OutputKind.FLAC,
            AlacFfmpegHelper.OutputKind.WAV,
            -> startMediaPlayer(decoded.file, startAtMs)
        }
    }

    private fun startPcmPlayback(decoded: AlacFfmpegHelper.DecodeResult) {
        val cb = callback ?: return
        stopPlaybackOnly()
        usingPcm = true
        paused = false
        pcmPlayer.play(
            pcmFile = decoded.file,
            format = decoded.pcmFormat,
            durationSec = durationSec,
            stopRequested = { stopRequested },
            listener = object : AlacPcmPlayer.Listener {
                override fun onPrepared(durationSec: Int) {
                    if (durationSec > 0) this@AlacAudioTrackEngine.durationSec = durationSec
                    cb.onPrepared(this@AlacAudioTrackEngine.durationSec)
                }

                override fun onPositionMs(positionMs: Int) {
                    cb.onPositionMs(positionMs)
                }

                override fun onPlayingChanged(playing: Boolean) {
                    cb.onPlayingChanged(playing)
                }

                override fun onEnded() {
                    if (!stopRequested) {
                        cb.onPlayingChanged(false)
                        cb.onEnded()
                    }
                    cleanupTemp()
                }

                override fun onError(message: String) {
                    if (!stopRequested) cb.onError(message)
                    cleanupTemp()
                }
            },
        )
    }

    private fun startMediaPlayer(file: File, startAtMs: Int = 0) {
        val cb = callback ?: return
        stopPlaybackOnly()
        usingPcm = false
        val player = MediaPlayer()
        mediaPlayer = player
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        player.setDataSource(file.absolutePath)
        player.setOnPreparedListener {
            if (stopRequested) return@setOnPreparedListener
            durationSec = (it.duration / 1000).coerceAtLeast(1)
            cb.onPrepared(durationSec)
            if (startAtMs > 0) it.seekTo(startAtMs)
            it.start()
            cb.onPlayingChanged(true)
            startProgressPolling(it)
        }
        player.setOnCompletionListener {
            if (!stopRequested) {
                cb.onPlayingChanged(false)
                cb.onEnded()
            }
            cleanupTemp()
        }
        player.setOnErrorListener { _, what, extra ->
            if (!stopRequested) {
                cb.onError("播放失败 ($what/$extra)")
            }
            cleanupTemp()
            true
        }
        player.prepareAsync()
    }

    private fun startProgressPolling(player: MediaPlayer) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && !stopRequested && mediaPlayer != null) {
                if (!paused && player.isPlaying) {
                    callback?.onPositionMs(player.currentPosition.coerceAtLeast(0))
                }
                delay(50)
            }
        }
    }

    private fun stopPlaybackOnly() {
        playJob?.cancel()
        playJob = null
        progressJob?.cancel()
        progressJob = null
        pcmPlayer.stop()
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null
        paused = false
        usingPcm = false
    }

    private fun copyUriToTemp(uri: Uri, songId: String): File? {
        val temp = File(appCtx.cacheDir, "alac_stream/${songId}_in.m4a")
        temp.parentFile?.mkdirs()
        return try {
            appCtx.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (temp.length() <= 0L) {
                temp.delete()
                null
            } else {
                temp
            }
        } catch (_: Exception) {
            temp.delete()
            null
        }
    }

    private fun cleanupTemp() {
        decodedFile?.delete()
        decodedFile = null
        tempInput?.delete()
        tempInput = null
    }
}
