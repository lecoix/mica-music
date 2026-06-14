package com.mica.music.util

import android.os.SystemClock
import java.util.Locale

/**
 * 解码链路耗时诊断：写入 [DiagnosticLog]（DecodePerf）并汇入当前切歌 [TrackSwitchPerformance] 时间线。
 */
object DecodePerformance {
    @Volatile
    private var boundSongId: String? = null

    fun bindSwitch(songId: String) {
        boundSongId = songId
    }

    fun clearSwitch() {
        boundSongId = null
    }

    fun currentSongId(): String? = boundSongId

    inline fun <T> measure(stage: String, songId: String, details: String = "", block: () -> T): T {
        val startedNs = SystemClock.elapsedRealtimeNanos()
        return try {
            block()
        } finally {
            val durationMs = (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000.0
            mark(stage, songId, durationMs, details)
        }
    }

    fun mark(
        stage: String,
        songId: String,
        durationMs: Double? = null,
        details: String = "",
    ) {
        val message = buildString {
            append("song=")
            append(songId.takeLast(16))
            if (durationMs != null) {
                append(" dur=")
                append(format(durationMs))
                append("ms")
            }
            if (details.isNotBlank()) {
                append(' ')
                append(details)
            }
            val bound = boundSongId
            if (bound != null && bound != songId) {
                append(" late=true bound=")
                append(bound.takeLast(16))
            }
        }
        DiagnosticLog.event("DecodePerf", "$stage $message")
        if (boundSongId == songId) {
            val perfDetails = buildString {
                if (durationMs != null) {
                    append("dur=")
                    append(format(durationMs))
                    append("ms")
                }
                if (details.isNotBlank()) {
                    if (isNotEmpty()) append(' ')
                    append(details)
                }
            }
            TrackSwitchPerformance.mark(stage, perfDetails)
        }
    }

    fun pipelineDone(songId: String, startedNs: Long, details: String = "") {
        val durationMs = (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000.0
        mark("decode-pipeline-done", songId, durationMs, details)
    }

    fun pipelineCancelled(songId: String, startedNs: Long, details: String = "") {
        val durationMs = (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000.0
        mark("decode-pipeline-cancelled", songId, durationMs, details)
        if (boundSongId == songId) clearSwitch()
    }

    fun summarizeStages(stages: List<TrackSwitchPerformance.StageSnapshot>): String {
        fun durOf(stage: String): Double? =
            stages.firstOrNull { it.stage == stage }
                ?.details
                ?.substringAfter("dur=", "")
                ?.substringBefore("ms")
                ?.toDoubleOrNull()

        val releaseMs = durOf("decode-session-release")
        val copyMs = durOf("decode-input-copy")
        val ffmpegMs = durOf("decode-ffmpeg-ready")
        val trackMs = durOf("decode-audio-track")
        val pipelineMs = durOf("decode-pipeline-done")
            ?: durOf("decode-pipeline-cancelled")

        if (releaseMs == null && copyMs == null && ffmpegMs == null && pipelineMs == null) {
            return "decode=none"
        }
        return buildString {
            append("decode=")
            append(
                listOfNotNull(
                    releaseMs?.let { "release=${format(it)}" },
                    copyMs?.let { "copy=${format(it)}" },
                    ffmpegMs?.let { "ffmpeg=${format(it)}" },
                    trackMs?.let { "track=${format(it)}" },
                    pipelineMs?.let { "pipeline=${format(it)}" },
                ).joinToString(","),
            )
        }
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.2f", value)
}
