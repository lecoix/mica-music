package com.mica.music.media

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import java.io.File
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
        val file: File,
        val kind: OutputKind,
        val pcmFormat: AlacPcmFormat,
        val producer: FfmpegRunner.RunningSession? = null,
    )

    fun decodeAlac(
        inputFile: File,
        outputBase: File,
        format: AlacPcmFormat,
        seekMs: Int = 0,
        preference: OutputPreference = OutputPreference.STREAM_PCM,
        allowEarlyPlayback: Boolean = true,
    ): DecodeResult? {
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
            OutputPreference.STREAM_PCM -> streamAttempts(outputBase, format, seekMs, inputFile)
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
        for (attempt in attempts) {
            attempt.cleanup.forEach { it.delete() }
            if (preference == OutputPreference.STREAM_PCM && allowEarlyPlayback) {
                startStreamingAttempt(attempt)?.let { return it }
                lastHint = lastFailureHint
                continue
            }
            val session = FfmpegRunner.executeWithArguments(appContext, attempt.args)
            val out = attempt.pickResult()
            if (out != null) return out
            lastHint = sessionFailureHint(session)
        }
        lastFailureHint = lastHint
        return null
    }

    private fun startStreamingAttempt(attempt: DecodeAttempt): DecodeResult? {
        val session = FfmpegRunner.startWithArguments(appContext, attempt.args) ?: run {
            lastFailureHint = "无法启动 FFmpeg"
            return null
        }
        val deadline = System.currentTimeMillis() + 8_000L
        val minReadyBytes = attempt.minReadyBytes.coerceAtLeast(64 * 1024L)
        while (System.currentTimeMillis() < deadline) {
            val out = attempt.pickStreamingResult(session, minReadyBytes)
            if (out != null) return out
            if (!session.isAlive) {
                val finished = session.waitFor()
                lastFailureHint = sessionFailureHint(finished)
                return null
            }
            Thread.sleep(40)
        }
        session.destroy()
        lastFailureHint = "FFmpeg 解码启动超时"
        return null
    }

    private fun streamAttempts(
        outputBase: File,
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
                        seekMs, input, outputBase, profile, resampleFlags, format,
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
                    outputBase,
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
        outputBase: File,
        profile: FfmpegCapability.PcmEncodeProfile,
        streamFlags: List<String>,
        sourceFormat: AlacPcmFormat,
    ): DecodeAttempt {
        val pcmOut = File("${outputBase.absolutePath}.${profile.tag}.pcm")
        val outFormat = profile.outputFormat(sourceFormat)
        return DecodeAttempt(
            args = buildArgs(seekMs, input, pcmOut, profile.muxer) {
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
            cleanup = listOf(pcmOut),
            minReadyBytes = outFormat.bytesPerFrame.toLong() * outFormat.sampleRateHz,
            pickResult = {
                if (!pcmOut.exists() || pcmOut.length() < outFormat.bytesPerFrame.toLong()) {
                    return@DecodeAttempt null
                }
                DecodeResult(pcmOut, OutputKind.PCM, outFormat)
            },
            pickStreamingResult = { producer, minReadyBytes ->
                if (!pcmOut.exists() || pcmOut.length() < minReadyBytes) {
                    return@DecodeAttempt null
                }
                DecodeResult(pcmOut, OutputKind.PCM, outFormat, producer)
            },
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
                args = buildArgs(seekMs, input, flacOut, "flac") {
                    listOf("-c:a", "flac", "-compression_level", "5")
                },
                cleanup = listOf(flacOut),
                pickResult = {
                    if (flacOut.exists() && flacOut.length() > 0L) {
                        DecodeResult(flacOut, OutputKind.FLAC, format)
                    } else null
                },
                pickStreamingResult = { _, _ -> null },
            ),
        )
    }

    private fun playbackFormats(source: AlacPcmFormat): List<AlacPcmFormat> {
        if (!source.isDsdSource) return listOf(source)
        val channels = source.channelCount.coerceIn(1, 2)
        val preferred = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AlacPcmFormat(sampleRateHz = 176_400, channelCount = channels, bitsPerSample = 24))
            }
            add(AlacPcmFormat(sampleRateHz = 88_200, channelCount = channels, bitsPerSample = 16))
        }
        return preferred.filter(::isAudioTrackFormatSupported).ifEmpty {
            listOf(AlacPcmFormat(sampleRateHz = 88_200, channelCount = channels, bitsPerSample = 16))
        }
    }

    private fun isAudioTrackFormatSupported(format: AlacPcmFormat): Boolean {
        val channelMask = when (format.channelCount.coerceIn(1, 2)) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            else -> AudioFormat.CHANNEL_OUT_STEREO
        }
        return AudioTrack.getMinBufferSize(
            format.sampleRateHz,
            channelMask,
            format.audioTrackEncoding,
        ) > 0
    }

    private val AlacPcmFormat.isDsdSource: Boolean
        get() = bitsPerSample == 1 || sampleRateHz >= 1_000_000

    private data class DecodeAttempt(
        val args: Array<String>,
        val cleanup: List<File>,
        val minReadyBytes: Long = 0L,
        val pickResult: () -> DecodeResult?,
        val pickStreamingResult: (FfmpegRunner.RunningSession, Long) -> DecodeResult?,
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

    private fun buildArgs(
        seekMs: Int,
        input: File,
        output: File,
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
        add(output.absolutePath)
    }.toTypedArray()

    private fun formatSeekSeconds(seekMs: Int): String =
        String.format(Locale.US, "%.3f", seekMs / 1000.0)
}
