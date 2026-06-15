package com.mica.music.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.mica.music.util.BluetoothAudioDiagnostics
import com.mica.music.util.DecodePerformance
import com.mica.music.util.TrackSwitchPerformance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * 将 FFmpeg 输出的裸 PCM 文件写入 [AudioTrack] 播放。
 *
 * 进度仅按「已提交帧 − 缓冲延迟」估算，单调递增，避免 head/timestamp 混用导致前后乱跳。
 */
internal class AlacPcmPlayer(
    private val scope: CoroutineScope,
    context: Context,
) {
    private val appContext = context.applicationContext
    private companion object {
        const val ProgressUpdateIntervalMs = 200L
        const val ZeroWriteRetryDelayMs = 2L
    }

    interface Listener {
        fun onPrepared(durationSec: Int)
        fun onPositionMs(positionMs: Int)
        fun onPlayingChanged(playing: Boolean)
        fun onOutputRouteChanged(device: android.media.AudioDeviceInfo?)
        fun onEnded()
        fun onError(message: String)
    }

    private var audioTrack: AudioTrack? = null
    private var writeJob: Job? = null
    private var progressJob: Job? = null
    @Volatile
    private var currentInput: InputStream? = null

    @Volatile
    private var paused = false

    private var currentVolume = 1f

    private var sampleRateHz = 44_100
    private var activeFormat: AlacPcmFormat? = null
    private val framesSubmitted = AtomicLong(0L)
    private var routeListener: Listener? = null
    private var lastRoutedDeviceId: Int? = null
    private val routingHandler = Handler(Looper.getMainLooper())
    private val routingChangedListener = AudioRouting.OnRoutingChangedListener { routing ->
        val track = routing as? AudioTrack ?: return@OnRoutingChangedListener
        publishRoute(track, notifyChange = true)
    }

    fun play(
        pcmStream: InputStream,
        format: AlacPcmFormat,
        durationSec: Int,
        stopRequested: () -> Boolean,
        listener: Listener,
        startOffsetMs: Int = 0,
        autoStart: Boolean = true,
        producer: FfmpegRunner.RunningSession,
    ) {
        cancelJobs()
        currentInput = pcmStream
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
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuf <= 0) {
            listener.onError("不支持的 PCM 格式 (${format.bitsPerSample}bit)")
            return
        }

        val bufferBytes = minBuf * 4
        val existing = audioTrack
        val reuseTrack = existing != null &&
            activeFormat == format &&
            existing.state == AudioTrack.STATE_INITIALIZED
        val track = if (reuseTrack) {
            val reuseStartedNs = SystemClock.elapsedRealtimeNanos()
            TrackSwitchPerformance.mark(
                "audio-track-reuse",
                "${format.bitsPerSample}bit/${sampleRate}Hz ch=${format.channelCount}",
            )
            runCatching {
                existing.pause()
                existing.flush()
            }
            DecodePerformance.currentSongId()?.let { songId ->
                DecodePerformance.mark(
                    stage = "decode-audio-track",
                    songId = songId,
                    durationMs = (SystemClock.elapsedRealtimeNanos() - reuseStartedNs) / 1_000_000.0,
                    details = "reuse=true ${format.bitsPerSample}bit/${sampleRate}Hz ch=${format.channelCount}",
                )
            }
            existing
        } else {
            releaseTrack()
            val createStartedNs = SystemClock.elapsedRealtimeNanos()
            val created = runCatching {
                AudioTrack.Builder()
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
            }.getOrElse {
                listener.onError("无法创建 AudioTrack (${format.bitsPerSample}bit/${sampleRate}Hz)")
                return
            }
            if (created.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { created.release() }
                listener.onError("AudioTrack 初始化失败 (${format.bitsPerSample}bit/${sampleRate}Hz)")
                return
            }
            TrackSwitchPerformance.mark(
                "audio-track-create",
                "${format.bitsPerSample}bit/${sampleRate}Hz ch=${format.channelCount}",
            )
            DecodePerformance.currentSongId()?.let { songId ->
                DecodePerformance.mark(
                    stage = "decode-audio-track",
                    songId = songId,
                    durationMs = (SystemClock.elapsedRealtimeNanos() - createStartedNs) / 1_000_000.0,
                    details = "reuse=false ${format.bitsPerSample}bit/${sampleRate}Hz ch=${format.channelCount}",
                )
            }
            created
        }
        if (!reuseTrack && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            track.addOnRoutingChangedListener(routingChangedListener, routingHandler)
        }
        routeListener = listener
        activeFormat = format
        audioTrack = track
        BluetoothAudioDiagnostics.logPlaybackRoute(
            reason = if (reuseTrack) "audio-track-reuse" else "audio-track-create",
            extra = "session=${track.audioSessionId}",
        )
        track.setVolume(currentVolume)
        MicaEqualizerManager.attach(appContext, track.audioSessionId)
        val maxMs = durationSec.coerceAtLeast(1) * 1000
        listener.onPrepared(durationSec.coerceAtLeast(1))
        if (autoStart) {
            runCatching { track.play() }
                .onFailure {
                    listener.onError("AudioTrack 启动失败")
                    stop()
                    return
                }
            listener.onPlayingChanged(true)
            publishRoute(track, notifyChange = false)
        } else {
            val parkedMs = startOffsetMs.coerceIn(0, maxMs)
            listener.onPositionMs(parkedMs)
        }

        writeJob = scope.launch(Dispatchers.IO) {
            try {
                val startFrame = format.framesForMs(startOffsetMs)
                val framesToPlay =
                    ((durationSec.coerceAtLeast(1) * sampleRate).toLong() - startFrame)
                        .coerceAtLeast(0)
                pcmStream.use { input ->
                    val buffer = ByteArray(minBuf)
                    while (isActive && !stopRequested()) {
                        if (paused) {
                            while (paused && isActive && !stopRequested()) {
                                delay(50)
                            }
                        }
                        if (stopRequested()) break
                        val read = input.read(buffer)
                        if (read <= 0) break
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
                            }
                            if (stopRequested()) break
                            val written = track.write(buffer, offset, read - offset)
                            if (written < 0) {
                                throw IllegalStateException("AudioTrack write failed: $written")
                            }
                            if (written == 0) {
                                delay(ZeroWriteRetryDelayMs)
                                continue
                            }
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
                        }
                    }
                }
                if (!stopRequested()) {
                    val finished = producer.waitFor()
                    if (!finished.success) {
                        withContext(Dispatchers.Main) {
                            listener.onError(
                                "FFmpeg 解码失败：${AlacFfmpegHelper.sessionFailureHint(finished)}",
                            )
                        }
                        return@launch
                    }
                    waitForDrain(
                        track,
                        minOf(framesSubmitted.get(), framesToPlay),
                        stopRequested,
                    )
                }
            } catch (e: Exception) {
                if (!stopRequested()) {
                    withContext(Dispatchers.Main) {
                        listener.onError("PCM 播放失败：${e.message}")
                    }
                }
                return@launch
            } finally {
                if (currentInput === pcmStream) currentInput = null
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
                delay(ProgressUpdateIntervalMs)
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

    fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        audioTrack?.setVolume(currentVolume)
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
        cancelJobs()
        releaseTrack()
        paused = false
    }

    /**
     * Stops the current writer but keeps an initialized AudioTrack available for
     * the next software item when its PCM format matches.
     */
    fun stopForSwitch() {
        cancelJobs()
        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
        }
        routeListener = null
        paused = false
    }

    private fun cancelJobs() {
        runCatching { currentInput?.close() }
        currentInput = null
        writeJob?.cancel()
        writeJob = null
        progressJob?.cancel()
        progressJob = null
        framesSubmitted.set(0L)
    }

    private fun releaseTrack() {
        val track = audioTrack
        audioTrack = null
        activeFormat = null
        routeListener = null
        lastRoutedDeviceId = null
        if (track != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching { track.removeOnRoutingChangedListener(routingChangedListener) }
            }
            scope.launch(Dispatchers.IO) {
                runCatching {
                    track.pause()
                    track.flush()
                    track.stop()
                    track.release()
                }
            }
        }
    }

    private fun publishRoute(track: AudioTrack, notifyChange: Boolean) {
        val device = track.routedDevice
        val previousId = lastRoutedDeviceId
        val currentId = device?.id
        SoftwareAudioRouteState.update(device)
        lastRoutedDeviceId = currentId
        BluetoothAudioDiagnostics.logPlaybackRoute(
            reason = if (notifyChange) "audio-track-route-changed" else "audio-track-routed",
            extra = "device=${AudioOutputCapabilities.snapshot(device).deviceName}",
        )
        if (notifyChange && previousId != null && currentId != previousId) {
            routeListener?.onOutputRouteChanged(device)
        }
    }
}
