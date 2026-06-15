package com.mica.music.media

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import com.mica.music.util.DecodePerformance
import java.io.File
import java.io.InputStream
import java.util.Locale

internal object AlacFfmpegHelper {

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    enum class OutputKind { PCM, FLAC }

    enum class OutputPreference {
        /** FFmpeg 直出裸 PCM（s16le/s24le/s32le muxer）→ AudioTrack */
        STREAM_PCM,
        FLAC_FILE,
    }

    data class DecodeResult(
        val file: File? = null,
        val kind: OutputKind,
        val pcmFormat: AlacPcmFormat,
        val producer: FfmpegRunner.RunningSession? = null,
        val pcmStream: InputStream? = null,
    )

    fun decodeAlac(
        inputFile: File,
        outputBase: File,
        format: AlacPcmFormat,
        seekMs: Int = 0,
        preference: OutputPreference = OutputPreference.STREAM_PCM,
        traceSongId: String? = null,
        isCancelled: () -> Boolean = { false },
    ): DecodeResult? {
        if (isCancelled()) return null
        if (!FfmpegRunner.hasEmbeddedBinary(appContext)) {
            lastFailureHint = "未安装 FFmpeg：请运行 scripts\\build-ffmpeg-arm64.ps1 后重新编译安装"
            return null
        }
        val missingHint = if (format.isDsdSource) {
            FfmpegCapability.missingDsdPlaybackHint(appContext)
        } else {
            FfmpegCapability.missingPlaybackHint(appContext)
        }
        missingHint?.let {
            lastFailureHint = it
            return null
        }
        val attempts = when (preference) {
            OutputPreference.STREAM_PCM -> streamAttempts(format, seekMs, inputFile)
            OutputPreference.FLAC_FILE -> flacAttempts(outputBase, format, seekMs, inputFile)
        }
        if (attempts.isEmpty()) {
            lastFailureHint = buildString {
                append(FfmpegCapability.missingPlaybackHint(appContext) ?: "无可用 PCM 配置")
                append("（")
                append(FfmpegCapability.capabilitySummary(appContext))
                append("）")
            }
            return null
        }

        var lastHint: String? = null
        for ((attemptIndex, attempt) in attempts.withIndex()) {
            attempt.cleanup.forEach { it.delete() }
            if (preference == OutputPreference.STREAM_PCM) {
                startPipedAttempt(attempt, traceSongId, attemptIndex, isCancelled)?.let { return it }
                if (traceSongId != null) {
                    DecodePerformance.mark(
                        stage = "decode-ffmpeg-attempt-fail",
                        songId = traceSongId,
                        details = "attempt=$attemptIndex hint=${lastFailureHint ?: "unknown"}",
                    )
                }
                lastHint = lastFailureHint
                continue
            }
            val startedNs = SystemClock.elapsedRealtimeNanos()
            val session = FfmpegRunner.executeWithArguments(appContext, attempt.args)
            val out = attempt.pickResult()
            if (out != null) {
                if (traceSongId != null) {
                    val waitMs = (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000.0
                    DecodePerformance.mark(
                        stage = "decode-ffmpeg-ready",
                        songId = traceSongId,
                        durationMs = waitMs,
                        details = "mode=blocking attempt=$attemptIndex bytes=${out.file?.length() ?: 0L} " +
                            "format=${out.pcmFormat.bitsPerSample}bit/${out.pcmFormat.sampleRateHz}Hz",
                    )
                }
                return out
            }
            lastHint = sessionFailureHint(session)
        }
        lastFailureHint = lastHint
        return null
    }

    private fun startPipedAttempt(
        attempt: DecodeAttempt,
        traceSongId: String?,
        attemptIndex: Int,
        isCancelled: () -> Boolean,
    ): DecodeResult? {
        val startedNs = SystemClock.elapsedRealtimeNanos()
        if (traceSongId != null) {
            DecodePerformance.mark(
                stage = "decode-ffmpeg-start",
                songId = traceSongId,
                details = "attempt=$attemptIndex output=stdout",
            )
        }
        if (isCancelled()) return null
        val session = FfmpegRunner.startPipedWithArguments(appContext, attempt.args) ?: run {
            lastFailureHint = "无法启动 FFmpeg"
            return null
        }
        val stream = session.stdout
        if (stream == null || isCancelled()) {
            session.destroy()
            lastFailureHint = if (isCancelled()) "解码请求已作废" else "FFmpeg stdout 不可用"
            return null
        }
        if (traceSongId != null) {
            val waitMs = (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000.0
            DecodePerformance.mark(
                stage = "decode-ffmpeg-ready",
                songId = traceSongId,
                durationMs = waitMs,
                details = "mode=stdout attempt=$attemptIndex " +
                    "format=${attempt.pcmFormat?.bitsPerSample}bit/" +
                    "${attempt.pcmFormat?.sampleRateHz}Hz",
            )
        }
        return DecodeResult(
            kind = OutputKind.PCM,
            pcmFormat = attempt.pcmFormat ?: return null,
            producer = session,
            pcmStream = stream,
        )
    }

    private fun streamAttempts(
        format: AlacPcmFormat,
        seekMs: Int,
        input: File,
    ): List<DecodeAttempt> {
        val probe = listOf("-probesize", "32M", "-analyzeduration", "10M")
        val streamFlags = listOf("-vn", "-sn", "-dn") + probe
        if (!format.isDsdSource) {
            val preferHiRes = format.bitsPerSample > 16 &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val profiles = FfmpegCapability.pcmEncodeProfiles(appContext, preferHiRes)
            return profiles.flatMapIndexed { index, profile ->
                val resampleFlags = if (index == 0) {
                    streamFlags + listOf(
                        "-ar", format.sampleRateHz.toString(),
                        "-ac", format.channelCount.toString(),
                    )
                } else {
                    streamFlags
                }
                listOf(
                    rawPcmAttempt(
                        seekMs, input, profile, resampleFlags, format,
                    ),
                )
            }
        }
        val targetFormats = playbackFormats(format)
        val preferHiRes = targetFormats.any { it.bitsPerSample > 16 } &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val profiles = FfmpegCapability.pcmEncodeProfiles(appContext, preferHiRes)
        return targetFormats.flatMap { targetFormat ->
            profiles.filter { it.bitsPerSample == targetFormat.bitsPerSample }.map { profile ->
                val resampleFlags = streamFlags + listOf(
                    "-ar", targetFormat.sampleRateHz.toString(),
                    "-ac", targetFormat.channelCount.toString(),
                )
                rawPcmAttempt(
                    seekMs,
                    input,
                    profile,
                    resampleFlags,
                    targetFormat,
                )
            }
        }
    }

    private fun rawPcmAttempt(
        seekMs: Int,
        input: File,
        profile: FfmpegCapability.PcmEncodeProfile,
        streamFlags: List<String>,
        sourceFormat: AlacPcmFormat,
    ): DecodeAttempt {
        val outFormat = profile.outputFormat(sourceFormat)
        return DecodeAttempt(
            args = buildArgs(seekMs, input, "pipe:1", profile.muxer) {
                buildList {
                    add("-c:a")
                    add(profile.codec)
                    if (profile.sampleFmt != null) {
                        add("-sample_fmt")
                        add(profile.sampleFmt)
                    }
                    addAll(streamFlags)
                }
            },
            cleanup = emptyList(),
            pcmFormat = outFormat,
            pickResult = { null },
        )
    }

    private fun flacAttempts(
        outputBase: File,
        format: AlacPcmFormat,
        seekMs: Int,
        input: File,
    ): List<DecodeAttempt> {
        val flacOut = File("${outputBase.absolutePath}.flac")
        return listOf(
            DecodeAttempt(
                args = buildArgs(seekMs, input, flacOut.absolutePath, "flac") {
                    listOf("-c:a", "flac", "-compression_level", "5")
                },
                cleanup = listOf(flacOut),
                pickResult = {
                    if (flacOut.exists() && flacOut.length() > 0L) {
                        DecodeResult(file = flacOut, kind = OutputKind.FLAC, pcmFormat = format)
                    } else null
                },
            ),
        )
    }

    private fun playbackFormats(source: AlacPcmFormat): List<AlacPcmFormat> {
        if (!source.isDsdSource) return listOf(source)
        val preferred = DsdOutputPolicy.candidates(appContext, source.channelCount)
        return preferred.ifEmpty {
            listOf(
                AlacPcmFormat(
                    sampleRateHz = 48_000,
                    channelCount = source.channelCount.coerceIn(1, 2),
                    bitsPerSample = 16,
                ),
            )
        }
    }

    private val AlacPcmFormat.isDsdSource: Boolean
        get() = bitsPerSample == 1 || sampleRateHz >= 1_000_000

    private data class DecodeAttempt(
        val args: Array<String>,
        val cleanup: List<File>,
        val pcmFormat: AlacPcmFormat? = null,
        val pickResult: () -> DecodeResult?,
    )

    var lastFailureHint: String? = null
        private set

    fun sessionFailureHint(session: FfmpegRunner.Session): String {
        val tail = session.logs
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(6)
            .joinToString("；")
        return if (tail.isNotBlank()) tail else "FFmpeg exit ${session.returnCode}"
    }

    internal fun buildArgs(
        seekMs: Int,
        input: File,
        output: String,
        muxerFormat: String,
        extra: () -> List<String>,
    ): Array<String> = buildList {
        add("-hide_banner")
        add("-nostdin")
        add("-y")
        add("-threads")
        add("1")
        add("-i")
        add(input.absolutePath)
        if (seekMs > 0) {
            add("-ss")
            add(formatSeekSeconds(seekMs))
        }
        add("-map")
        add("0:a:0?")
        addAll(extra())
        add("-f")
        add(muxerFormat)
        add(output)
    }.toTypedArray()

    private fun formatSeekSeconds(seekMs: Int): String =
        String.format(Locale.US, "%.3f", seekMs / 1000.0)
}
