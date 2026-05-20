package com.mica.music.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * 将 FFmpeg 输出的裸 PCM 文件写入 [AudioTrack] 播放。
 */
internal class AlacPcmPlayer(
    private val scope: CoroutineScope,
) {
    interface Listener {
        fun onPrepared(durationSec: Int)
        fun onPositionMs(positionMs: Int)
        fun onPlayingChanged(playing: Boolean)
        fun onEnded()
        fun onError(message: String)
    }

    private var audioTrack: AudioTrack? = null
    private var writeJob: Job? = null
    private var progressJob: Job? = null

    @Volatile
    private var paused = false

    private var sampleRateHz = 44_100

    fun play(
        pcmFile: File,
        format: AlacPcmFormat,
        durationSec: Int,
        stopRequested: () -> Boolean,
        listener: Listener,
    ) {
        stop()
        val channelMask = if (format.channelCount == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val encoding = format.audioTrackEncoding
        val sampleRate = format.sampleRateHz
        sampleRateHz = sampleRate
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuf <= 0) {
            listener.onError("不支持的 PCM 格式 (${format.bitsPerSample}bit)")
            return
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(encoding)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf * 4)
            .build()

        audioTrack = track
        paused = false
        val totalSec = durationSec.coerceAtLeast(1)
        listener.onPrepared(totalSec)
        track.play()
        listener.onPlayingChanged(true)

        writeJob = scope.launch(Dispatchers.IO) {
            try {
                FileInputStream(pcmFile).use { input ->
                    val buffer = ByteArray(minBuf)
                    while (isActive && !stopRequested()) {
                        while (paused && isActive && !stopRequested()) {
                            delay(50)
                        }
                        if (stopRequested()) break
                        val read = input.read(buffer)
                        if (read <= 0) break
                        var offset = 0
                        while (offset < read && isActive && !stopRequested()) {
                            while (paused && isActive && !stopRequested()) {
                                delay(50)
                            }
                            if (stopRequested()) break
                            val written = track.write(buffer, offset, read - offset)
                            if (written <= 0) break
                            offset += written
                        }
                    }
                }
                if (!stopRequested()) {
                    val totalFrames = pcmFile.length() / format.bytesPerFrame.coerceAtLeast(1)
                    waitForDrain(track, totalFrames, stopRequested)
                }
            } catch (e: Exception) {
                if (!stopRequested()) {
                    withContext(Dispatchers.Main) {
                        listener.onError("PCM 播放失败：${e.message}")
                    }
                }
                return@launch
            }
            if (!stopRequested()) {
                withContext(Dispatchers.Main) {
                    listener.onPlayingChanged(false)
                    listener.onEnded()
                }
            }
        }

        progressJob = scope.launch {
            while (isActive && !stopRequested() && audioTrack === track) {
                if (!paused && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    val positionMs = (
                        (track.playbackHeadPosition.toLong() * 1000L) / sampleRateHz
                        ).toInt()
                    listener.onPositionMs(positionMs.coerceAtLeast(0))
                }
                delay(50)
            }
        }
    }

    private suspend fun waitForDrain(
        track: AudioTrack,
        totalFrames: Long,
        stopRequested: () -> Boolean,
    ) {
        while (currentCoroutineContext().isActive && !stopRequested()) {
            if (track.playbackHeadPosition >= totalFrames - 512) break
            delay(80)
        }
        delay(150)
    }

    fun pause() {
        paused = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            audioTrack?.pause()
        }
    }

    fun resume() {
        paused = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            audioTrack?.play()
        }
    }

    fun stop() {
        writeJob?.cancel()
        writeJob = null
        progressJob?.cancel()
        progressJob = null
        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        }
        audioTrack = null
        paused = false
    }
}
