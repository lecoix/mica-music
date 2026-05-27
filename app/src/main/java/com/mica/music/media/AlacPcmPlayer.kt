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
import java.util.concurrent.atomic.AtomicLong

/**
 * 将 FFmpeg 输出的裸 PCM 文件写入 [AudioTrack] 播放。
 *
 * 进度仅按「已提交帧 − 缓冲延迟」估算，单调递增，避免 head/timestamp 混用导致前后乱跳。
 */
internal class AlacPcmPlayer(
    private val scope: CoroutineScope,
) {
    private companion object {
        const val SpectrumTargetFps = 60
    }

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
    private val framesSubmitted = AtomicLong(0L)

    fun play(
        pcmFile: File,
        format: AlacPcmFormat,
        durationSec: Int,
        stopRequested: () -> Boolean,
        listener: Listener,
        startOffsetMs: Int = 0,
        autoStart: Boolean = true,
        producerAlive: (() -> Boolean)? = null,
    ) {
        stop()
        framesSubmitted.set(0L)
        paused = !autoStart
        val channelMask = if (format.channelCount == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val encoding = format.audioTrackEncoding
        val sampleRate = format.sampleRateHz
        sampleRateHz = sampleRate
        val bytesPerFrame = format.bytesPerFrame.coerceAtLeast(1)
        val spectrumWriteChunkBytes = (
            (sampleRate / SpectrumTargetFps).coerceAtLeast(1) * bytesPerFrame
        ).coerceAtLeast(bytesPerFrame)
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuf <= 0) {
            listener.onError("不支持的 PCM 格式 (${format.bitsPerSample}bit)")
            return
        }

        val bufferBytes = minBuf * 4
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
            .setBufferSizeInBytes(bufferBytes)
            .build()

        audioTrack = track
        AlacPlaybackCoordinator.appContext?.let { ctx ->
            MicaEqualizerManager.attach(ctx, track.audioSessionId)
        }
        val maxMs = durationSec.coerceAtLeast(1) * 1000
        listener.onPrepared(durationSec.coerceAtLeast(1))
        if (autoStart) {
            track.play()
            listener.onPlayingChanged(true)
        } else {
            val parkedMs = startOffsetMs.coerceIn(0, maxMs)
            listener.onPositionMs(parkedMs)
        }

        writeJob = scope.launch(Dispatchers.IO) {
            try {
                val startByte = format.byteOffsetForMs(startOffsetMs)
                val fileFrames = pcmFile.length() / bytesPerFrame
                val startFrame = format.framesForMs(startOffsetMs)
                val framesToPlay = if (producerAlive != null) {
                    ((durationSec.coerceAtLeast(1) * sampleRate).toLong() - startFrame).coerceAtLeast(0)
                } else {
                    (fileFrames - startFrame).coerceAtLeast(0)
                }
                FileInputStream(pcmFile).use { input ->
                    if (startByte > 0) {
                        var remaining = startByte
                        while (remaining > 0) {
                            val skipped = input.skip(remaining)
                            if (skipped <= 0) break
                            remaining -= skipped
                        }
                    }
                    val buffer = ByteArray(minBuf)
                    var nextWriteNanos = System.nanoTime()
                    while (isActive && !stopRequested()) {
                        if (paused) {
                            while (paused && isActive && !stopRequested()) {
                                delay(50)
                            }
                            nextWriteNanos = System.nanoTime()
                        }
                        if (stopRequested()) break
                        val read = input.read(buffer)
                        if (read <= 0) {
                            if (producerAlive?.invoke() == true) {
                                delay(30)
                                continue
                            }
                            break
                        }
                        MicaEqualizerManager.processPcmBuffer(
                            buffer = buffer,
                            offset = 0,
                            length = read,
                            encoding = encoding,
                            sampleRateHz = sampleRate,
                            channelCount = format.channelCount,
                        )
                        var offset = 0
                        while (offset < read && isActive && !stopRequested()) {
                            if (paused) {
                                while (paused && isActive && !stopRequested()) {
                                    delay(50)
                                }
                                nextWriteNanos = System.nanoTime()
                            }
                            if (stopRequested()) break
                            val writeLength = minOf(spectrumWriteChunkBytes, read - offset)
                            val nowNanos = System.nanoTime()
                            val waitNanos = nextWriteNanos - nowNanos
                            if (waitNanos > 1_000_000L) {
                                delay(waitNanos / 1_000_000L)
                            } else if (waitNanos < -50_000_000L) {
                                nextWriteNanos = nowNanos
                            }
                            val written = track.write(buffer, offset, writeLength)
                            if (written <= 0) break
                            MicaSpectrumAnalyzer.processPcmBuffer(
                                buffer = buffer,
                                offset = offset,
                                length = written,
                                encoding = encoding,
                                sampleRateHz = sampleRate,
                                channelCount = format.channelCount,
                            )
                            offset += written
                            val writtenFrames = written / bytesPerFrame
                            framesSubmitted.addAndGet(writtenFrames.toLong())
                            nextWriteNanos += writtenFrames * 1_000_000_000L / sampleRate
                        }
                    }
                }
                if (!stopRequested()) {
                    val drainFrames = if (producerAlive != null) framesSubmitted.get() else framesToPlay
                    waitForDrain(track, drainFrames, stopRequested)
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
                // 用 playbackHeadPosition（已播放帧），不用 framesSubmitted（含缓冲未播帧，seek 后会超前右跳）
                val playedFrames = track.playbackHeadPosition.coerceAtLeast(0)
                val absoluteMs = (startOffsetMs + playedFrames * 1000L / sampleRate).toInt()
                    .coerceIn(0, maxMs)
                listener.onPositionMs(absoluteMs)
                delay(50)
            }
        }
    }

    private suspend fun waitForDrain(
        track: AudioTrack,
        framesToPlay: Long,
        stopRequested: () -> Boolean,
    ) {
        if (framesToPlay <= 0) return
        while (currentCoroutineContext().isActive && !stopRequested()) {
            if (track.playbackHeadPosition >= framesToPlay - 512) break
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
        framesSubmitted.set(0L)
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
