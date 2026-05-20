package com.mica.music.media

import android.content.Context
import android.os.Build
import java.io.File

internal object AlacFfmpegHelper {

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    enum class OutputKind { PCM, WAV, FLAC }

    enum class OutputPreference {
        /** 流式：优先裸 PCM，再 WAV 兜底 */
        STREAM_PCM,
        /** 仅 FLAC 文件（ExoPlayer 缓存路径） */
        FLAC_FILE,
    }

    data class DecodeResult(
        val file: File,
        val kind: OutputKind,
        val pcmFormat: AlacPcmFormat,
    )

    fun decodeAlac(
        inputFile: File,
        outputBase: File,
        format: AlacPcmFormat,
        seekSec: Int = 0,
        preference: OutputPreference = OutputPreference.STREAM_PCM,
    ): DecodeResult? {
        val attempts = when (preference) {
            OutputPreference.STREAM_PCM -> streamAttempts(outputBase, format, seekSec, inputFile)
            OutputPreference.FLAC_FILE -> flacAttempts(outputBase, format, seekSec, inputFile)
        }

        var lastHint: String? = null
        for (attempt in attempts) {
            attempt.cleanup.forEach { it.delete() }
            val session = FfmpegRunner.executeWithArguments(appContext, attempt.args)
            val out = attempt.pickResult()
            if (out != null) return out
            lastHint = sessionFailureHint(session)
        }
        lastFailureHint = lastHint
        return null
    }

    private fun streamAttempts(
        outputBase: File,
        format: AlacPcmFormat,
        seekSec: Int,
        input: File,
    ): List<DecodeAttempt> {
        val pcm16 = File("${outputBase.absolutePath}.pcm")
        val pcm24 = File("${outputBase.absolutePath}.s24.pcm")
        val wavOut = File("${outputBase.absolutePath}.wav")

        return buildList {
            // 尽量简单：只映射音轨，让 swresample/aformat 做格式转换（需 FFmpeg 编进对应 filter）
            add(
                pcmAttempt(seekSec, input, pcm16, format, "s16le", "pcm_s16le", "s16"),
            )
            add(
                pcmAttempt(
                    seekSec, input, pcm16, format, "s16le", "pcm_s16le", "s16",
                    extra = listOf(
                        "-ar", format.sampleRateHz.toString(),
                        "-ac", format.channelCount.toString(),
                    ),
                ),
            )
            if (format.bitsPerSample > 16 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(
                    pcmAttempt(seekSec, input, pcm24, format, "s24le", "pcm_s24le", "s32"),
                )
            }
            add(wavAttempt(seekSec, input, wavOut, format, withRate = false))
            add(wavAttempt(seekSec, input, wavOut, format, withRate = true))
        }
    }

    private fun flacAttempts(
        outputBase: File,
        format: AlacPcmFormat,
        seekSec: Int,
        input: File,
    ): List<DecodeAttempt> {
        val flacOut = File("${outputBase.absolutePath}.flac")
        return listOf(
            DecodeAttempt(
                args = buildArgs(seekSec, input, flacOut, "flac") {
                    listOf("-c:a", "flac", "-compression_level", "5")
                },
                cleanup = listOf(flacOut),
                pickResult = {
                    if (flacOut.exists() && flacOut.length() > 0L) {
                        DecodeResult(flacOut, OutputKind.FLAC, format)
                    } else null
                },
            ),
        )
    }

    private fun pcmAttempt(
        seekSec: Int,
        input: File,
        output: File,
        format: AlacPcmFormat,
        muxer: String,
        codec: String,
        sampleFmt: String,
        extra: List<String> = emptyList(),
    ): DecodeAttempt = DecodeAttempt(
        args = buildArgs(seekSec, input, output, muxer) {
            buildList {
                add("-c:a")
                add(codec)
                add("-sample_fmt")
                add(sampleFmt)
                addAll(extra)
            }
        },
        cleanup = listOf(output),
        pickResult = {
            if (output.exists() && output.length() > format.bytesPerFrame) {
                DecodeResult(output, OutputKind.PCM, format)
            } else null
        },
    )

    private fun wavAttempt(
        seekSec: Int,
        input: File,
        output: File,
        format: AlacPcmFormat,
        withRate: Boolean,
    ): DecodeAttempt = DecodeAttempt(
        args = buildArgs(seekSec, input, output, "wav") {
            buildList {
                add("-c:a")
                add("pcm_s16le")
                add("-sample_fmt")
                add("s16")
                if (withRate) {
                    add("-ar")
                    add(format.sampleRateHz.toString())
                    add("-ac")
                    add(format.channelCount.toString())
                }
            }
        },
        cleanup = listOf(output),
        pickResult = {
            if (output.exists() && output.length() > 44L) {
                DecodeResult(output, OutputKind.WAV, format)
            } else null
        },
    )

    private data class DecodeAttempt(
        val args: Array<String>,
        val cleanup: List<File>,
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

    private fun buildArgs(
        seekSec: Int,
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
        if (seekSec > 0) {
            add("-ss")
            add(seekSec.toString())
        }
        add("-i")
        add(input.absolutePath)
        add("-map")
        add("0:a:0?")
        addAll(extra())
        add("-f")
        add(muxerFormat)
        add(output.absolutePath)
    }.toTypedArray()
}
