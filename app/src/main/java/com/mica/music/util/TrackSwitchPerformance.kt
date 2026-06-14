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
    private const val CAPTURE_DURATION_MS = 1_500L
    private const val EXPECTED_FRAME_NS = 16_666_667L
    private const val SPIKE_LOG_THRESHOLD_NS = 24_000_000L
    private val mainHandler = Handler(Looper.getMainLooper())

    data class VisualContext(
        val coverFlowMode: String = "STANDARD",
        val lowerBackground: String = "unknown",
        val coverFlowStageActive: Boolean = false,
        val motionEnabled: Boolean = true,
    )

    @Volatile
    private var visualContext = VisualContext()

    @Volatile
    private var pendingTrigger: String = "unknown"

    private const val AUDIO_START_DEFER_MS = 120L
    private const val MARK_DEDUPE_WINDOW_MS = 500L
    private val markDedupeAtMs = mutableMapOf<String, Long>()

    private var nextId = 0
    private var capture: Capture? = null

    fun updateVisualContext(ctx: VisualContext) {
        visualContext = ctx
    }

    fun armTrigger(trigger: String) {
        pendingTrigger = trigger
    }

    /** 封面流舞台活跃时延后音频启动，让切歌动画先跑起来。 */
    fun audioStartDeferMs(): Long {
        val ctx = visualContext
        return if (ctx.coverFlowStageActive && ctx.motionEnabled) AUDIO_START_DEFER_MS else 0L
    }

    fun begin(fromIndex: Int, toIndex: Int, songId: String) {
        runOnMain {
            finish("superseded")
            val ctx = visualContext
            val trigger = pendingTrigger.also { pendingTrigger = "unknown" }
            markDedupeAtMs.clear()
            val item = Capture(
                id = ++nextId,
                startedNs = SystemClock.elapsedRealtimeNanos(),
                trigger = trigger,
                visualContext = ctx,
            )
            capture = item
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Trace.beginAsyncSection("MicaTrackSwitch", item.id)
            }
            DiagnosticLog.event(
                "TrackPerf",
                "#${item.id} begin $fromIndex->$toIndex trigger=$trigger " +
                    "coverFlow=${ctx.coverFlowMode} stage=${ctx.coverFlowStageActive} " +
                    "bg=${ctx.lowerBackground} motion=${ctx.motionEnabled} song=$songId",
            )
            AudioEnvironmentDiagnostics.logEnvironment("track-switch")
            Choreographer.getInstance().postFrameCallback(item.frameCallback)
        }
    }

    fun mark(stage: String, details: String = "") {
        runOnMain {
            val item = capture ?: return@runOnMain
            if (shouldSkipDuplicateMark(stage, details)) return@runOnMain
            val offsetMs = elapsedMs(item.startedNs)
            item.stages.add(Stage(stage, offsetMs, details))
            DiagnosticLog.event(
                "TrackPerf",
                "#${item.id} +${format(offsetMs)}ms $stage" +
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
        val stageSnapshots = item.stages.map { StageSnapshot(it.stage, it.offsetMs, it.details) }
        val timeline = stageSnapshots.joinToString(" | ") { stage ->
            buildString {
                append(stage.stage)
                append('@')
                append(format(stage.offsetMs))
                append("ms")
                if (stage.details.isNotBlank()) {
                    append(' ')
                    append(stage.details)
                }
            }
        }
        val decodeSummary = DecodePerformance.summarizeStages(stageSnapshots)
        val ctx = item.visualContext
        DiagnosticLog.event(
            "TrackPerf",
            "#${item.id} summary reason=$reason duration=${format(elapsedMs(item.startedNs))}ms; " +
                "trigger=${item.trigger}; coverFlow=${ctx.coverFlowMode}; stage=${ctx.coverFlowStageActive}; " +
                "bg=${ctx.lowerBackground}; $decodeSummary; " +
                "frames=${item.frameIntervals}; avgFrame=${format(averageFrameMs)}ms; " +
                "maxFrame=${format(item.maxFrameNs / 1_000_000.0)}ms; " +
                "spikes24=${item.spikeCount}; over16=${item.over16}; over32=${item.over32}; " +
                "over50=${item.over50}; estimatedMissed=${item.estimatedMissedFrames}; " +
                "coverDraws=${item.drawCount}; avgDraw=${format(averageDrawMs)}ms; " +
                "maxDraw=${format(item.maxDrawNs / 1_000_000.0)}ms; " +
                "lanes=${item.maxLaneCount}; reflection=${item.reflectionDrawn}; " +
                "timeline=$timeline",
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
            if (intervalNs > 32_000_000L) item.over32++
            if (intervalNs > 50_000_000L) item.over50++
            val missed = ((intervalNs + EXPECTED_FRAME_NS / 2) / EXPECTED_FRAME_NS - 1)
                .coerceAtLeast(0)
                .toInt()
            item.estimatedMissedFrames += missed
            if (intervalNs >= SPIKE_LOG_THRESHOLD_NS) {
                item.spikeCount++
                DiagnosticLog.event(
                    "TrackPerf",
                    "#${item.id} +${format(elapsedMs(item.startedNs))}ms frame-spike " +
                        "interval=${format(intervalNs / 1_000_000.0)}ms missed~$missed",
                )
            }
        }
        if (elapsedMs(item.startedNs) >= CAPTURE_DURATION_MS) {
            finish("window-complete")
        } else {
            Choreographer.getInstance().postFrameCallback(item.frameCallback)
        }
    }

    private fun shouldSkipDuplicateMark(stage: String, details: String): Boolean {
        if (stage != "cover-load-start" && stage != "ui-cover-flow-progress-start") return false
        val key = "$stage|$details"
        val now = SystemClock.elapsedRealtime()
        val last = markDedupeAtMs[key]
        if (last != null && now - last < MARK_DEDUPE_WINDOW_MS) return true
        markDedupeAtMs[key] = now
        return false
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun elapsedMs(startedNs: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000.0

    private fun format(value: Double): String = String.format(Locale.US, "%.2f", value)

    data class StageSnapshot(
        val stage: String,
        val offsetMs: Double,
        val details: String,
    )

    private data class Stage(
        val stage: String,
        val offsetMs: Double,
        val details: String,
    )

    private class Capture(
        val id: Int,
        val startedNs: Long,
        val trigger: String,
        val visualContext: VisualContext,
    ) {
        var lastFrameNs = 0L
        var frameIntervals = 0
        var frameTotalNs = 0L
        var maxFrameNs = 0L
        var over16 = 0
        var over32 = 0
        var over50 = 0
        var spikeCount = 0
        var estimatedMissedFrames = 0
        var drawCount = 0
        var drawTotalNs = 0L
        var maxDrawNs = 0L
        var maxLaneCount = 0
        var reflectionDrawn = false
        val stages = mutableListOf<Stage>()
        val frameCallback = Choreographer.FrameCallback { frameTimeNs ->
            onFrame(this, frameTimeNs)
        }
    }
}
