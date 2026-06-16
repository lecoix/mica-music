package com.mica.music.media

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.mica.music.data.DsdSupport
import com.mica.music.data.Song
import com.mica.music.util.DiagnosticLog
import com.mica.music.util.DecodePerformance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

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
    private val pcmPlayer = AlacPcmPlayer(scope, appCtx)
    private val inputCache = AudioInputCache(appCtx.cacheDir)
    private var playJob: Job? = null
    private var callback: Callback? = null
    private var currentSong: Song? = null
    private var pcmFormat: AlacPcmFormat? = null
    private var sessionSongId: String? = null
    private var sessionDecode: AlacFfmpegHelper.DecodeResult? = null
    private var sessionInputLease: InputFileLease? = null
    private var durationSec: Int = 0
    private var paused = false
    private var stopRequested = false
    private var playbackEpoch = 0
    @Volatile
    private var decodeGeneration = 0
    private var playbackVolume = 1f
    private var currentPositionMs = 0

    fun setVolume(volume: Float) {
        playbackVolume = volume.coerceIn(0f, 1f)
        pcmPlayer.setVolume(playbackVolume)
    }

    fun play(song: Song, listener: Callback, startOffsetMs: Int = 0) {
        AlacFfmpegHelper.init(appCtx)
        stopRequested = true
        playbackEpoch++
        playJob?.cancel()
        playJob = null
        pcmPlayer.stopForSwitch()
        val generation = ++decodeGeneration
        DecodePerformance.measure("decode-session-release", song.id) {
            releaseSession()
        }
        stopRequested = false
        callback = listener
        currentSong = song
        pcmFormat = AlacPcmFormat.fromSong(song)
        paused = false
        listener.onBuffering(true)
        val offsetMs = startOffsetMs.coerceAtLeast(0)
        currentPositionMs = offsetMs
        val pipelineStartedNs = android.os.SystemClock.elapsedRealtimeNanos()

        playJob = scope.launch {
            val (decoded, failHint) = withContext(Dispatchers.IO) {
                runCatching {
                    ensureSessionDecoded(
                        song,
                        generation = generation,
                        seekMs = offsetMs,
                    )
                }
                    .getOrElse { e ->
                        null to (e.message ?: e.javaClass.simpleName)
                    }
            }
            if (generation != decodeGeneration) {
                DecodePerformance.pipelineCancelled(
                    song.id,
                    pipelineStartedNs,
                    details = "stage=decode-superseded",
                )
                return@launch
            }
            if (decoded == null || stopRequested) {
                callback?.onBuffering(false)
                if (stopRequested) {
                    DecodePerformance.pipelineCancelled(
                        song.id,
                        pipelineStartedNs,
                        details = "stage=decode",
                    )
                } else {
                    DecodePerformance.pipelineCancelled(
                        song.id,
                        pipelineStartedNs,
                        details = "error=${failHint ?: "decode-failed"}",
                    )
                }
                if (!stopRequested) {
                    val detail = failHint?.let { "：$it" }.orEmpty()
                    callback?.onError("解码失败$detail")
                }
                releaseSession()
                return@launch
            }
            if (generation != decodeGeneration) {
                DecodePerformance.pipelineCancelled(
                    song.id,
                    pipelineStartedNs,
                    details = "stage=pre-playback-superseded",
                )
                return@launch
            }
            if (stopRequested || callback == null) {
                DecodePerformance.pipelineCancelled(song.id, pipelineStartedNs, details = "stage=pre-playback")
                releaseSession()
                return@launch
            }
            durationSec = song.durationSec
            callback?.onBuffering(false)
            DecodePerformance.pipelineDone(
                song.id,
                pipelineStartedNs,
                details = "format=${decoded.pcmFormat.bitsPerSample}bit/${decoded.pcmFormat.sampleRateHz}Hz " +
                    "transport=stdout",
            )
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

    /** 切歌间隙：停掉当前 PCM 输出，保留引擎与会话状态。 */
    fun stopPlaybackOnlyForSwitch() {
        stopPlaybackOnly()
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
        val generation = ++decodeGeneration
        stopPlaybackOnly(releaseOutput = false)
        releaseSession()
        stopRequested = false
        cb.onBuffering(true)
        playJob = scope.launch {
            val (decoded, failHint) = withContext(Dispatchers.IO) {
                runCatching {
                    ensureSessionDecoded(
                        song = song,
                        generation = generation,
                        seekMs = seekMs,
                    )
                }
                    .getOrElse { e ->
                        null to (e.message ?: e.javaClass.simpleName)
                    }
            }
            if (generation != decodeGeneration) return@launch
            if (decoded == null || stopRequested) {
                cb.onBuffering(false)
                if (!stopRequested) {
                    val detail = failHint?.let { "：$it" }.orEmpty()
                    cb.onError("跳转失败$detail")
                }
                return@launch
            }
            cb.onBuffering(false)
            if (!startPlayback) paused = true
            startPcmPlayback(
                decoded = decoded,
                startOffsetMs = seekMs,
                startPlayback = startPlayback,
            )
        }
    }

    fun seekTo(seconds: Int) = seekToMs(seconds * 1000)

    fun stop() {
        stop(cleanupAsync = true)
    }

    /**
     * Stops software output while retaining the initialized AudioTrack for a later
     * same-format software item.
     */
    fun stopForBackendSwitch() {
        decodeGeneration++
        stopRequested = true
        stopPlaybackOnly(releaseOutput = false)
        val cleanup = detachSession()
        callback = null
        currentSong = null
        scope.launch(Dispatchers.IO) {
            cleanup.release()
        }
    }

    private fun stop(cleanupAsync: Boolean) {
        decodeGeneration++
        stopRequested = true
        stopPlaybackOnly(releaseOutput = true)
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

    private fun ensureSessionDecoded(
        song: Song,
        generation: Int = decodeGeneration,
        seekMs: Int = 0,
    ): Pair<AlacFfmpegHelper.DecodeResult?, String?> {
        if (generation != decodeGeneration) return null to "请求已作废"
        val (candidate, failure) = decodeAtPosition(
            song = song,
            generation = generation,
            seekMs = seekMs,
        )
        val result = candidate?.decode
        if (candidate != null && generation == decodeGeneration) {
                sessionSongId = song.id
                sessionDecode = result
                sessionInputLease = candidate.inputLease
        } else if (candidate != null) {
                candidate.decode.producer?.destroy()
                runCatching { candidate.decode.pcmStream?.close() }
                candidate.inputLease.close()
        }
        return result to failure
    }

    private fun decodeAtPosition(
        song: Song,
        generation: Int,
        seekMs: Int,
    ): Pair<DecodeCandidate?, String?> {
        val format = AlacPcmFormat.fromSong(song)
        val input = resolveInputFile(Uri.parse(song.effectivePlaybackUri), song, generation)
            ?: return null to if (generation == decodeGeneration) "无法读取源文件" else "请求已作废"
        if (generation != decodeGeneration) {
            input.close()
            return null to "请求已作废"
        }
        val base = File(appCtx.cacheDir, "alac_stream/${song.id}_${generation}_session")
        base.parentFile?.mkdirs()
        val result = AlacFfmpegHelper.decodeAlac(
            input.file,
            base,
            format,
            seekMs = seekMs,
            preference = AlacFfmpegHelper.OutputPreference.STREAM_PCM,
            traceSongId = song.id,
            isCancelled = { generation != decodeGeneration },
        )
        return if (result != null && result.kind == AlacFfmpegHelper.OutputKind.PCM) {
            DecodeCandidate(result, input) to null
        } else {
            input.close()
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
        if (stopBeforeStart) stopPlaybackOnly(releaseOutput = false)
        val epoch = playbackEpoch
        if (!startPlayback) paused = true
        scope.launch(Dispatchers.IO) {
            if (epoch != playbackEpoch || stopRequested) return@launch
            pcmPlayer.setVolume(playbackVolume)
            pcmPlayer.play(
                pcmStream = decoded.pcmStream ?: run {
                    cb.onError("FFmpeg stdout 不可用")
                    return@launch
                },
                format = decoded.pcmFormat,
                durationSec = durationSec,
                startOffsetMs = startOffsetMs,
                autoStart = startPlayback,
                stopRequested = { stopRequested || epoch != playbackEpoch },
                producer = decoded.producer ?: run {
                    cb.onError("FFmpeg 会话不可用")
                    return@launch
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
                            currentPositionMs = positionMs
                            cb.onPositionMs(positionMs)
                        }
                    }

                    override fun onPlayingChanged(playing: Boolean) {
                        scope.launch {
                            if (epoch != playbackEpoch) return@launch
                            cb.onPlayingChanged(playing)
                        }
                    }

                    override fun onOutputRouteChanged(device: android.media.AudioDeviceInfo?) {
                        scope.launch {
                            if (epoch != playbackEpoch || stopRequested) return@launch
                            val song = currentSong ?: return@launch
                            if (!DsdSupport.isDsdMetadata(song.metadata) &&
                                !DsdSupport.isDsdExtension(
                                    song.fileName.substringAfterLast('.', ""),
                                )
                            ) {
                                return@launch
                            }
                            val route = AudioOutputCapabilities.snapshot(device)
                            DiagnosticLog.event(
                                "AudioRoute",
                                "rebuild software song=${song.id} positionMs=$currentPositionMs " +
                                    "device=${route.deviceName} type=${route.deviceType}",
                            )
                            seekToMs(
                                positionMs = currentPositionMs,
                                startPlayback = !paused,
                            )
                        }
                    }

                    override fun onEnded() {
                        scope.launch {
                            if (epoch != playbackEpoch || stopRequested) return@launch
                            val cleanup = detachSession()
                            scope.launch(Dispatchers.IO) { cleanup.release() }
                            cb.onPlayingChanged(false)
                            cb.onEnded()
                        }
                    }

                    override fun onError(message: String) {
                        scope.launch {
                            if (epoch != playbackEpoch || stopRequested) return@launch
                            val cleanup = detachSession()
                            scope.launch(Dispatchers.IO) { cleanup.release() }
                            cb.onError(message)
                        }
                    }
                },
            )
        }
    }

    private fun stopPlaybackOnly(releaseOutput: Boolean = false) {
        playbackEpoch++
        playJob?.cancel()
        playJob = null
        if (releaseOutput) {
            pcmPlayer.stop()
        } else {
            pcmPlayer.stopForSwitch()
        }
        paused = false
    }

    private fun resolveInputFile(
        uri: Uri,
        song: Song,
        generation: Int,
    ): InputFileLease? {
        resolveDirectFile(uri)?.let { direct ->
            DecodePerformance.mark(
                stage = "decode-input-direct",
                songId = song.id,
                durationMs = 0.0,
                details = "sizeMB=${formatMb(direct.length())} path=${direct.name}",
            )
            return InputFileLease(direct)
        }

        val ext = song.fileName.substringAfterLast('.', "")
            .lowercase()
            .takeIf { it.length in 1..8 && it.all { c -> c.isLetterOrDigit() } }
            ?: "audio"
        val startedNs = android.os.SystemClock.elapsedRealtimeNanos()
        val cacheResult = inputCache.getOrCopy(
            identity = "${song.id}|${song.sizeBytes}|${song.dateModifiedMs}|${song.effectivePlaybackUri}",
            extension = ext,
            expectedBytes = song.sizeBytes,
            isCancelled = { generation != decodeGeneration },
            openInput = { appCtx.contentResolver.openInputStream(uri) },
        )
        val result = cacheResult?.file
        val durationMs = (android.os.SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000.0
        if (result != null) {
            DecodePerformance.mark(
                stage = "decode-input-copy",
                songId = song.id,
                durationMs = durationMs,
                details = "reused=${cacheResult.reused} sizeMB=${formatMb(result.length())} ext=$ext",
            )
        } else {
            DecodePerformance.mark(
                stage = "decode-input-copy",
                songId = song.id,
                durationMs = durationMs,
                details = "failed ext=$ext",
            )
        }
        return cacheResult?.let { result ->
            InputFileLease(result.file, result::close)
        }
    }

    private fun resolveDirectFile(uri: Uri): File? {
        val candidate = when (uri.scheme?.lowercase()) {
            "file" -> uri.path?.let(::File)
            "content" -> resolveExternalStorageDocument(uri)
            else -> null
        } ?: return null
        val readable = runCatching {
            candidate.isFile &&
                candidate.length() > 0L &&
                FileInputStream(candidate).use { it.read() >= 0 }
        }.getOrDefault(false)
        return candidate.takeIf { readable }
    }

    private fun resolveExternalStorageDocument(uri: Uri): File? {
        if (uri.authority != EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY) return null
        val documentId = runCatching {
            DocumentsContract.getDocumentId(uri)
        }.getOrNull() ?: return null
        val separator = documentId.indexOf(':')
        if (separator <= 0 || separator == documentId.lastIndex) return null
        val volume = documentId.substring(0, separator)
        val relativePath = documentId.substring(separator + 1)
        val root = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage", volume)
        }
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching {
            File(canonicalRoot, relativePath).canonicalFile
        }.getOrNull() ?: return null
        val rootPath = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
        return candidate.takeIf { it.path.startsWith(rootPath) }
    }

    private fun formatMb(bytes: Long): String =
        String.format(java.util.Locale.US, "%.2f", bytes / (1024.0 * 1024.0))

    private fun releaseSession() {
        detachSession().release()
    }

    private fun detachSession(): SessionCleanup {
        val cleanup = SessionCleanup(
            producer = sessionDecode?.producer,
            pcmStream = sessionDecode?.pcmStream,
            inputLease = sessionInputLease,
        )
        sessionSongId = null
        sessionDecode = null
        sessionInputLease = null
        return cleanup
    }

    private data class DecodeCandidate(
        val decode: AlacFfmpegHelper.DecodeResult,
        val inputLease: InputFileLease,
    )

    private data class InputFileLease(
        val file: File,
        private val releaseAction: () -> Unit = {},
    ) : AutoCloseable {
        override fun close() = releaseAction()
    }

    private data class SessionCleanup(
        val producer: FfmpegRunner.RunningSession?,
        val pcmStream: java.io.InputStream?,
        val inputLease: InputFileLease?,
    ) {
        fun release() {
            runCatching { pcmStream?.close() }
            producer?.destroy()
            inputLease?.close()
        }
    }

    private companion object {
        const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY =
            "com.android.externalstorage.documents"
    }
}
