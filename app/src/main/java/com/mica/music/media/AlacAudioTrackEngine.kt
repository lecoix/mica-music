package com.mica.music.media

import android.content.Context
import android.net.Uri
import com.mica.music.data.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 统一软件播放：FFmpeg 解码为裸 PCM，由 [AlacPcmPlayer] 写入 [android.media.AudioTrack]。
 *
 * 同曲播放期间会缓存整首解码结果；跳转进度时直接跳过 PCM 字节，避免重复 FFmpeg 解码。
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
    private val pcmPlayer = AlacPcmPlayer(scope)
    private var playJob: Job? = null
    private var decodedFile: File? = null
    private var tempInput: File? = null
    private var callback: Callback? = null
    private var currentSong: Song? = null
    private var pcmFormat: AlacPcmFormat? = null
    private var sessionSongId: String? = null
    private var sessionDecode: AlacFfmpegHelper.DecodeResult? = null
    private var durationSec: Int = 0
    private var paused = false
    private var stopRequested = false
    private var playbackEpoch = 0
    private var playbackVolume = 1f

    fun setVolume(volume: Float) {
        playbackVolume = volume.coerceIn(0f, 1f)
        pcmPlayer.setVolume(playbackVolume)
    }

    fun play(song: Song, listener: Callback, startOffsetMs: Int = 0) {
        AlacFfmpegHelper.init(appCtx)
        stop()
        callback = listener
        currentSong = song
        pcmFormat = AlacPcmFormat.fromSong(song)
        stopRequested = false
        paused = false
        listener.onBuffering(true)
        val offsetMs = startOffsetMs.coerceAtLeast(0)

        playJob = scope.launch {
            val (decoded, failHint) = withContext(Dispatchers.IO) {
                runCatching { ensureSessionDecoded(song, allowEarlyPlayback = offsetMs <= 0) }
                    .getOrElse { e ->
                        null to (e.message ?: e.javaClass.simpleName)
                    }
            }
            if (decoded == null || stopRequested) {
                callback?.onBuffering(false)
                if (!stopRequested) {
                    val detail = failHint?.let { "：$it" }.orEmpty()
                    callback?.onError("解码失败$detail")
                }
                releaseSession()
                return@launch
            }
            if (stopRequested || callback == null) {
                releaseSession()
                return@launch
            }
            durationSec = song.durationSec
            callback?.onBuffering(false)
            startDecodedPlayback(decoded, startOffsetMs = offsetMs)
        }
    }

    fun pause() {
        if (!paused) {
            paused = true
            pcmPlayer.pause()
            callback?.onPlayingChanged(false)
        }
    }

    fun resume() {
        if (paused) {
            paused = false
            pcmPlayer.resume()
            callback?.onPlayingChanged(true)
        }
    }

    /** 暂停后恢复；若解码已完成但输出未启动，则重新挂载 PCM 播放。 */
    fun resumeOrRestart() {
        if (paused) {
            resume()
            return
        }
        if (playJob?.isActive == true) return
        val decoded = sessionDecode ?: return
        if (stopRequested || callback == null) return
        startDecodedPlayback(decoded)
    }

    fun seekToMs(positionMs: Int, startPlayback: Boolean = !paused) {
        val song = currentSong ?: return
        val cb = callback ?: return
        val maxMs = durationSec.coerceAtLeast(song.durationSec).coerceAtLeast(1) * 1000
        val seekMs = positionMs.coerceIn(0, maxMs)

        if (hasSessionFor(song)) {
            applySeekFromSession(seekMs, startPlayback)
            return
        }

        stopPlaybackOnly()
        cb.onBuffering(true)
        playJob = scope.launch {
            val (decoded, failHint) = withContext(Dispatchers.IO) {
                runCatching { ensureSessionDecoded(song, allowEarlyPlayback = false) }
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
                return@launch
            }
            cb.onBuffering(false)
            applySeekFromSession(seekMs, startPlayback)
        }
    }

    private fun applySeekFromSession(seekMs: Int, startPlayback: Boolean) {
        val cb = callback ?: return
        val decoded = sessionDecode ?: return
        if (decoded.kind != AlacFfmpegHelper.OutputKind.PCM) {
            cb.onError("无法跳转：未生成 PCM")
            return
        }
        val clampedMs = seekMs.coerceIn(0, durationSec.coerceAtLeast(1) * 1000)
        if (!startPlayback) paused = true
        cb.onBuffering(true)
        stopPlaybackOnly()
        startPcmPlayback(
            decoded,
            startOffsetMs = clampedMs,
            stopBeforeStart = false,
            startPlayback = startPlayback,
        )
    }

    fun seekTo(seconds: Int) = seekToMs(seconds * 1000)

    fun stop() {
        stop(cleanupAsync = true)
    }

    private fun stop(cleanupAsync: Boolean) {
        stopRequested = true
        stopPlaybackOnly()
        val cleanup = detachSession()
        callback = null
        currentSong = null
        if (cleanupAsync) {
            scope.launch(Dispatchers.IO) {
                cleanup.release()
            }
        } else {
            cleanup.release()
        }
    }

    fun release() {
        stop(cleanupAsync = false)
        scope.cancel()
    }

    private fun hasSessionFor(song: Song): Boolean =
        sessionSongId == song.id &&
            sessionDecode != null &&
            decodedFile?.exists() == true &&
            (decodedFile?.length() ?: 0L) > 0L

    private fun ensureSessionDecoded(
        song: Song,
        allowEarlyPlayback: Boolean = true,
    ): Pair<AlacFfmpegHelper.DecodeResult?, String?> {
        if (hasSessionFor(song)) {
            return sessionDecode to null
        }
        releaseSession()
        return decodeAtPosition(song, allowEarlyPlayback).also { (result, _) ->
            if (result != null) {
                sessionSongId = song.id
                sessionDecode = result
            }
        }
    }

    private fun decodeAtPosition(
        song: Song,
        allowEarlyPlayback: Boolean,
    ): Pair<AlacFfmpegHelper.DecodeResult?, String?> {
        val format = pcmFormat ?: AlacPcmFormat.fromSong(song)
        if (tempInput == null || sessionSongId != song.id) {
            tempInput?.delete()
            tempInput = copyUriToTemp(Uri.parse(song.mediaUri), song)
                ?: return null to "无法读取源文件"
        }
        val base = File(appCtx.cacheDir, "alac_stream/${song.id}_session")
        base.parentFile?.mkdirs()
        val result = AlacFfmpegHelper.decodeAlac(
            tempInput!!,
            base,
            format,
            seekMs = 0,
            preference = AlacFfmpegHelper.OutputPreference.STREAM_PCM,
            allowEarlyPlayback = allowEarlyPlayback,
        )
        return if (result != null && result.kind == AlacFfmpegHelper.OutputKind.PCM) {
            decodedFile = result.file
            result to null
        } else {
            null to (AlacFfmpegHelper.lastFailureHint ?: "FFmpeg 无法解码此曲目")
        }
    }

    private fun startDecodedPlayback(
        decoded: AlacFfmpegHelper.DecodeResult,
        startOffsetMs: Int = 0,
    ) {
        if (stopRequested || callback == null) return
        if (decoded.kind != AlacFfmpegHelper.OutputKind.PCM) {
            callback?.onError("仅支持 PCM 输出，请检查 FFmpeg 解码")
            return
        }
        startPcmPlayback(decoded, startOffsetMs)
    }

    private fun startPcmPlayback(
        decoded: AlacFfmpegHelper.DecodeResult,
        startOffsetMs: Int = 0,
        stopBeforeStart: Boolean = true,
        startPlayback: Boolean = true,
    ) {
        val cb = callback ?: return
        if (stopBeforeStart) stopPlaybackOnly()
        val epoch = playbackEpoch
        if (!startPlayback) paused = true
        scope.launch(Dispatchers.IO) {
            if (epoch != playbackEpoch || stopRequested) return@launch
            pcmPlayer.setVolume(playbackVolume)
            pcmPlayer.play(
                pcmFile = decoded.file,
                format = decoded.pcmFormat,
                durationSec = durationSec,
                startOffsetMs = startOffsetMs,
                autoStart = startPlayback,
                stopRequested = { stopRequested },
                producerAlive = decoded.producer?.let { producer ->
                    { producer.isAlive }
                },
                listener = object : AlacPcmPlayer.Listener {
                    override fun onPrepared(durationSec: Int) {
                        scope.launch {
                            if (epoch != playbackEpoch) return@launch
                            if (durationSec > 0) this@AlacAudioTrackEngine.durationSec = durationSec
                            cb.onPrepared(this@AlacAudioTrackEngine.durationSec)
                            cb.onBuffering(false)
                        }
                    }

                    override fun onPositionMs(positionMs: Int) {
                        scope.launch {
                            if (epoch != playbackEpoch) return@launch
                            cb.onPositionMs(positionMs)
                        }
                    }

                    override fun onPlayingChanged(playing: Boolean) {
                        scope.launch {
                            if (epoch != playbackEpoch) return@launch
                            cb.onPlayingChanged(playing)
                        }
                    }

                    override fun onEnded() {
                        scope.launch {
                            if (epoch != playbackEpoch || stopRequested) return@launch
                            cb.onPlayingChanged(false)
                            cb.onEnded()
                        }
                    }

                    override fun onError(message: String) {
                        scope.launch {
                            if (epoch != playbackEpoch || stopRequested) return@launch
                            cb.onError(message)
                        }
                    }
                },
            )
        }
    }

    private fun stopPlaybackOnly() {
        playbackEpoch++
        playJob?.cancel()
        playJob = null
        pcmPlayer.stop()
        paused = false
    }

    private fun copyUriToTemp(uri: Uri, song: Song): File? {
        val ext = song.fileName.substringAfterLast('.', "")
            .lowercase()
            .takeIf { it.length in 1..8 && it.all { c -> c.isLetterOrDigit() } }
            ?: "audio"
        val temp = File(appCtx.cacheDir, "alac_stream/${song.id}_in.$ext")
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

    private fun releaseSession() {
        detachSession().release()
    }

    private fun detachSession(): SessionCleanup {
        val cleanup = SessionCleanup(
            producer = sessionDecode?.producer,
            decodedFile = decodedFile,
            tempInput = tempInput,
        )
        decodedFile = null
        tempInput = null
        sessionSongId = null
        sessionDecode = null
        return cleanup
    }

    private data class SessionCleanup(
        val producer: FfmpegRunner.RunningSession?,
        val decodedFile: File?,
        val tempInput: File?,
    ) {
        fun release() {
            producer?.destroy()
            decodedFile?.delete()
            tempInput?.delete()
        }
    }
}
