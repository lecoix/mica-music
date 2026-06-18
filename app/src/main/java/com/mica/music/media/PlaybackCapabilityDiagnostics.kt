package com.mica.music.media

import android.content.Context
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import com.mica.music.media.dsf.DsfFormat
import com.mica.music.util.DiagnosticLog

/**
 * 汇总 Exo（media3-ffmpeg-decoder）运行时能力，供关于页与诊断日志使用。
 */
@UnstableApi
object PlaybackCapabilityDiagnostics {

    private const val EXO_FFMPEG_COORDINATES = "org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1"

    data class Line(val title: String, val detail: String)

    data class Report(val lines: List<Line>) {
        fun asLogText(): String = lines.joinToString(" | ") { "${it.title}=${it.detail}" }
    }

    fun report(context: Context): Report {
        val lines = mutableListOf<Line>()
        lines += exoFfmpegLines()
        lines += routingLines()
        return Report(lines)
    }

    fun logStartup(context: Context) {
        val report = report(context)
        DiagnosticLog.event("PlaybackCapability", report.asLogText())
    }

    private fun exoFfmpegLines(): List<Line> {
        val available = runCatching { FfmpegLibrary.isAvailable() }.getOrDefault(false)
        val version = if (available) {
            runCatching { FfmpegLibrary.getVersion() }.getOrNull() ?: "未知"
        } else {
            "不可用"
        }
        val dsdNative = nativeDecoderSupport("dsd_lsbf_planar")
        val dsdMime = formatSupport(DsfFormat.MIME_DSF)
        val coordinates = when {
            dsdNative == "已编入 native" && dsdMime == "支持" ->
                "本地 media3-ffmpeg-decoder-dsd（dsd_lsbf_planar）"
            available -> EXO_FFMPEG_COORDINATES
            else -> "本地模块（libffmpegJNI 未装入 APK 或未加载）"
        }
        return listOf(
            Line("播放后端", "Media3 单链路（ExoPlayer）"),
            Line("Exo FFmpeg 扩展", coordinates),
            Line("libffmpegJNI", if (available) "已加载 · $version" else "未加载"),
            Line("Exo · native dsd_lsbf_planar", dsdNative),
            Line("Exo · DSD (audio/dsd)", dsdMime),
            Line("Exo · ALAC", formatSupport(MimeTypes.AUDIO_ALAC)),
            Line("Exo · FLAC", formatSupport(MimeTypes.AUDIO_FLAC)),
            Line("Exo · DSF 容器", formatSupport(DsfFormat.MIME_CONTAINER_DSF)),
        )
    }

    private fun nativeDecoderSupport(codecName: String): String {
        if (!runCatching { FfmpegLibrary.isAvailable() }.getOrDefault(false)) {
            return "扩展未加载"
        }
        val hasDecoder = runCatching {
            val method = FfmpegLibrary::class.java.getDeclaredMethod(
                "ffmpegHasDecoder",
                String::class.java,
            )
            method.isAccessible = true
            method.invoke(null, codecName) as Boolean
        }.getOrDefault(false)
        return if (hasDecoder) "已编入 native" else "未编入 native"
    }

    private fun routingLines(): List<Line> = listOf(
        Line("DSF 路由", "Media3（DsfExtractor + Exo FFmpeg + DSD 降采样）"),
        Line("DFF 路由", "不支持播放"),
        Line("ALAC 路由", "Media3 FFmpeg 扩展（alac）"),
        Line(
            "Exo DSD 说明",
            "需自编 libffmpegJNI（dsd_lsbf_planar）+ FfmpegLibrary audio/dsd 映射",
        ),
    )

    private fun formatSupport(mimeType: String?): String {
        if (mimeType.isNullOrBlank()) return "MIME 未定义"
        if (!runCatching { FfmpegLibrary.isAvailable() }.getOrDefault(false)) {
            return "扩展未加载"
        }
        val supported = runCatching { FfmpegLibrary.supportsFormat(mimeType) }.getOrDefault(false)
        return if (supported) "支持" else "不支持"
    }
}
