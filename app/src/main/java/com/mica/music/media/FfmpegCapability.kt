package com.mica.music.media

import android.content.Context

/**
 * 探测当前 APK 内嵌 FFmpeg 实际启用的编码器与 muxer（不同构建差异很大）。
 */
internal object FfmpegCapability {

    data class PcmEncodeProfile(
        val codec: String,
        val sampleFmt: String?,
        val tag: String,
        val muxer: String,
        val bitsPerSample: Int,
    ) {
        fun outputFormat(source: AlacPcmFormat): AlacPcmFormat = AlacPcmFormat(
            sampleRateHz = source.sampleRateHz,
            channelCount = source.channelCount,
            bitsPerSample = bitsPerSample,
        )
    }

    private val KNOWN_PCM_ENCODERS = listOf("pcm_s24le", "pcm_s32le", "pcm_s16le")
    private val KNOWN_PCM_MUXERS = listOf("s32le", "s24le", "s16le")
    private val KNOWN_DSD_DECODERS = listOf("dsd_lsbf", "dsd_msbf", "dsd_lsbf_planar", "dsd_msbf_planar")
    private val KNOWN_DSD_DEMUXERS = listOf("dsf", "iff")

    /** 匹配 ` E s16le ` / ` EV s24le ` 行，避免把 pcm_s16le、wav 说明行误判为 muxer。 */
    private val ENABLED_MUXER_LINE =
        Regex("""\s(?:E|EV)\s+(s32le|s24le|s16le)\s""")

    @Volatile
    private var cachedEncoders: Set<String>? = null

    @Volatile
    private var cachedMuxers: Set<String>? = null

    @Volatile
    private var cachedDecoders: Set<String>? = null

    @Volatile
    private var cachedDemuxers: Set<String>? = null

    fun availableEncoders(context: Context): Set<String> {
        cachedEncoders?.let { return it }
        synchronized(this) {
            cachedEncoders?.let { return it }
            if (!FfmpegRunner.hasEmbeddedBinary(context)) {
                cachedEncoders = emptySet()
                return emptySet()
            }
            val session = FfmpegRunner.executeWithArguments(
                context,
                arrayOf("-hide_banner", "-encoders"),
            )
            val found = KNOWN_PCM_ENCODERS.filter { name ->
                session.logs.contains(name)
            }.toSet()
            cachedEncoders = found
            return found
        }
    }

    fun availableMuxers(context: Context): Set<String> {
        cachedMuxers?.let { return it }
        synchronized(this) {
            cachedMuxers?.let { return it }
            if (!FfmpegRunner.hasEmbeddedBinary(context)) {
                cachedMuxers = emptySet()
                return emptySet()
            }
            val session = FfmpegRunner.executeWithArguments(
                context,
                arrayOf("-hide_banner", "-muxers"),
            )
            val found = ENABLED_MUXER_LINE.findAll(session.logs)
                .map { it.groupValues[1] }
                .toSet()
            cachedMuxers = found
            return found
        }
    }

    fun availableDecoders(context: Context): Set<String> {
        cachedDecoders?.let { return it }
        synchronized(this) {
            cachedDecoders?.let { return it }
            if (!FfmpegRunner.hasEmbeddedBinary(context)) {
                cachedDecoders = emptySet()
                return emptySet()
            }
            val session = FfmpegRunner.executeWithArguments(
                context,
                arrayOf("-hide_banner", "-decoders"),
            )
            val found = KNOWN_DSD_DECODERS.filter { name ->
                session.logs.contains(name)
            }.toSet()
            cachedDecoders = found
            return found
        }
    }

    fun availableDemuxers(context: Context): Set<String> {
        cachedDemuxers?.let { return it }
        synchronized(this) {
            cachedDemuxers?.let { return it }
            if (!FfmpegRunner.hasEmbeddedBinary(context)) {
                cachedDemuxers = emptySet()
                return emptySet()
            }
            val session = FfmpegRunner.executeWithArguments(
                context,
                arrayOf("-hide_banner", "-demuxers"),
            )
            val found = KNOWN_DSD_DEMUXERS.filter { name ->
                Regex("""\sD\s+$name\s""").containsMatchIn(session.logs)
            }.toSet()
            cachedDemuxers = found
            return found
        }
    }

    /** 按优先级生成配置：encoder 与裸 PCM muxer 必须同时存在。 */
    fun pcmEncodeProfiles(context: Context, preferHiRes: Boolean): List<PcmEncodeProfile> {
        val enc = availableEncoders(context)
        val mux = availableMuxers(context)
        val profiles = mutableListOf<PcmEncodeProfile>()
        if (preferHiRes) {
            if ("pcm_s24le" in enc && "s24le" in mux) {
                profiles.add(PcmEncodeProfile("pcm_s24le", "s32", "s24s32", "s24le", 24))
                profiles.add(PcmEncodeProfile("pcm_s24le", null, "s24", "s24le", 24))
            }
            if ("pcm_s32le" in enc && "s32le" in mux) {
                profiles.add(PcmEncodeProfile("pcm_s32le", "s32", "s32", "s32le", 32))
            }
        }
        if ("pcm_s16le" in enc && "s16le" in mux) {
            profiles.add(PcmEncodeProfile("pcm_s16le", "s16", "s16", "s16le", 16))
        }
        return profiles
    }

    fun missingPlaybackHint(context: Context): String? {
        val enc = availableEncoders(context)
        if (enc.isEmpty()) {
            return "FFmpeg 未包含 pcm 编码器，请运行 scripts\\build-ffmpeg-arm64.ps1 后重装"
        }
        val mux = availableMuxers(context)
        if (mux.isEmpty()) {
            return "FFmpeg 未包含 s16le/s24le/s32le 输出 muxer（仅有编码器不够），请运行 scripts\\build-ffmpeg-arm64.ps1 后重装"
        }
        return null
    }

    fun missingEncoderHint(context: Context): String? = missingPlaybackHint(context)

    fun missingDsdPlaybackHint(context: Context): String? {
        missingPlaybackHint(context)?.let { return it }
        val demuxers = availableDemuxers(context)
        if (!demuxers.containsAll(KNOWN_DSD_DEMUXERS)) {
            return "FFmpeg 未包含 DSF/IFF(DFF/DSDIFF) demuxer，请运行 scripts\\build-ffmpeg-arm64.ps1 后重装"
        }
        val decoders = availableDecoders(context)
        if (decoders.intersect(KNOWN_DSD_DECODERS.toSet()).isEmpty()) {
            return "FFmpeg 未包含 DSD decoder，请运行 scripts\\build-ffmpeg-arm64.ps1 后重装"
        }
        return null
    }

    /** 设置页/诊断：当前二进制能力摘要 */
    fun capabilitySummary(context: Context): String {
        val enc = availableEncoders(context).sorted()
        val mux = availableMuxers(context).sorted()
        val dec = availableDecoders(context).sorted()
        val demux = availableDemuxers(context).sorted()
        return "enc=${enc.joinToString()} mux=${mux.joinToString()} dec=${dec.joinToString()} demux=${demux.joinToString()}"
    }
}
