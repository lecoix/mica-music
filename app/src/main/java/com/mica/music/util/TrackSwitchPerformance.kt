package com.mica.music.util

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import android.view.Choreographer
import java.util.Locale
import kotlin.math.max

object TrackSwitchPerformance {
    private const val CAPTURE_DURATION_MS = 1_200L
    private const val EXPECTED_FRAME_NS = 16_666_667L
    private val mainHandler = Handler(Looper.getMainLooper())

    private var nextId = 0
    private var capture: Capture? = null

    fun begin(fromIndex: Int, toIndex: Int, mode: String, songId: String) {
        runOnMain {
            finish("superseded")
            val item = Capture(
                id = ++nextId,
                startedNs = SystemClock.elapsedRealtimeNanos(),
            )
            capture = item
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Trace.beginAsyncSection("MicaTrackSwitch", item.id)
            }
            DiagnosticLog.event(
                "TrackPerf",
                "#${item.id} begin index=$fromIndex->$toIndex mode=$mode song=$songId",
            )
            Choreographer.getInstance().postFrameCallback(item.frameCallback)
        }
    }

    fun mark(stage: String, details: String = "") {
        runOnMain {
            val item = capture ?: return@runOnMain
            DiagnosticLog.event(
                "TrackPerf",
                "#${item.id} +${format(elapsedMs(item.startedNs))}ms $stage" +
                    details.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty(),
            )
        }
    }

    fun recordCoverDraw(durationNs: Long, laneCount: Int, reflection: Boolean) {
        val item = capture ?: return
        item.drawCount++
        item.drawTotalNs += durationNs
        item.maxDrawNs = max(item.maxDrawNs, durationNs)
        item.maxLaneCount = max(item.maxLaneCount, laneCount)
        item.reflectionDrawn = item.reflectionDrawn || reflection
    }

    private fun finish(reason: String) {
        val item = capture ?: return
        capture = null
        Choreographer.getInstance().removeFrameCallback(item.frameCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Trace.endAsyncSection("MicaTrackSwitch", item.id)
        }
        val averageFrameMs = if (item.frameIntervals > 0) {
            item.frameTotalNs.toDouble() / item.frameIntervals / 1_000_000.0
        } else {
            0.0
        }
        val averageDrawMs = if (item.drawCount > 0) {
            item.drawTotalNs.toDouble() / item.drawCount / 1_000_000.0
        } else {
            0.0
        }
        DiagnosticLog.event(
            "TrackPerf",
            "#${item.id} summary reason=$reason duration=${format(elapsedMs(item.startedNs))}ms; " +
                "frames=${item.frameIntervals}; avgFrame=${format(averageFrameMs)}ms; " +
                "maxFrame=${format(item.maxFrameNs / 1_000_000.0)}ms; " +
                "over16=${item.over16}; over24=${item.over24}; over32=${item.over32}; " +
                "over50=${item.over50}; estimatedMissed=${item.estimatedMissedFrames}; " +
                "coverDraws=${item.drawCount}; avgDraw=${format(averageDrawMs)}ms; " +
                "maxDraw=${format(item.maxDrawNs / 1_000_000.0)}ms; " +
                "lanes=${item.maxLaneCount}; reflection=${item.reflectionDrawn}",
        )
    }

    private fun onFrame(item: Capture, frameTimeNs: Long) {
        if (capture !== item) return
        val previousNs = item.lastFrameNs
        item.lastFrameNs = frameTimeNs
        if (previousNs != 0L) {
            val intervalNs = (frameTimeNs - previousNs).coerceAtLeast(0L)
            item.frameIntervals++
            item.frameTotalNs += intervalNs
            item.maxFrameNs = max(item.maxFrameNs, intervalNs)
            if (intervalNs > EXPECTED_FRAME_NS) item.over16++
            if (intervalNs > 24_000_000L) item.over24++
            if (intervalNs > 32_000_000L) item.over32++
            if (intervalNs > 50_000_000L) item.over50++
            item.estimatedMissedFrames +=
                ((intervalNs + EXPECTED_FRAME_NS / 2) / EXPECTED_FRAME_NS - 1)
                    .coerceAtLeast(0)
                    .toInt()
        }
        if (elapsedMs(item.startedNs) >= CAPTURE_DURATION_MS) {
            finish("window-complete")
        } else {
            Choreographer.getInstance().postFrameCallback(item.frameCallback)
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun elapsedMs(startedNs: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000.0

    private fun format(value: Double): String = String.format(Locale.US, "%.2f", value)

    private class Capture(
        val id: Int,
        val startedNs: Long,
    ) {
        var lastFrameNs = 0L
        var frameIntervals = 0
        var frameTotalNs = 0L
        var maxFrameNs = 0L
        var over16 = 0
        var over24 = 0
        var over32 = 0
        var over50 = 0
        var estimatedMissedFrames = 0
        var drawCount = 0
        var drawTotalNs = 0L
        var maxDrawNs = 0L
        var maxLaneCount = 0
        var reflectionDrawn = false
        val frameCallback = Choreographer.FrameCallback { frameTimeNs ->
            onFrame(this, frameTimeNs)
        }
    }
}
