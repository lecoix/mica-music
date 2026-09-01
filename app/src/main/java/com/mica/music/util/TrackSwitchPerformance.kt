package com.mica.music.util

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import android.view.Choreographer
import com.mica.music.BuildConfig
import java.util.Locale
import kotlin.math.max

object TrackSwitchPerformance {
    private const val CAPTURE_DURATION_MS = 1_500L
    private const val EXPECTED_FRAME_NS = 16_666_667L
    private const val SPIKE_LOG_THRESHOLD_NS = 24_000_000L
    private val mainHandler by lazy(LazyThreadSafetyMode.NONE) { Handler(Looper.getMainLooper()) }

    data class VisualContext(
        val coverFlowMode: String = "STANDARD",
        val lowerBackground: String = "unknown",
        val coverFlowStageActive: Boolean = false,
        val motionEnabled: Boolean = true,
        val queueSize: Int = 0,
    )

    @Volatile
    private var visualContext = VisualContext()

    @Volatile
    private var pendingTrigger: String = "unknown"

    private const val MARK_DEDUPE_WINDOW_MS = 500L
    private val markDedupeAtMs = mutableMapOf<String, Long>()

    private var nextId = 0
    private var capture: Capture? = null

    fun updateVisualContext(ctx: VisualContext) {
        if (!BuildConfig.TRACK_SWITCH_PERFORMANCE) return
        visualContext = ctx
    }

    fun armTrigger(trigger: String) {
        if (!BuildConfig.TRACK_SWITCH_PERFORMANCE) return
        pendingTrigger = trigger
    }

    fun begin(fromIndex: Int, toIndex: Int, songId: String, queueSize: Int = visualContext.queueSize) {
        if (!BuildConfig.TRACK_SWITCH_PERFORMANCE) {
            pendingTrigger = "unknown"
            return
        }
        runOnMain {
            finish("superseded")
            startCapture(
                trigger = pendingTrigger.also { pendingTrigger = "unknown" },
                queueSize = queueSize,
                banner = "begin $fromIndex->$toIndex song=$songId",
            )
        }
    }

    /** 封面流手势按下：若尚无采集窗口则开启，便于记录拖动期 draw/anim/host 指标。 */
    fun beginCoverFlowWindow(index: Int, queueSize: Int) {
        if (!BuildConfig.TRACK_SWITCH_PERFORMANCE) return
        runOnMain {
            if (capture != null) return@runOnMain
            startCapture(
                trigger = "coverflow-drag",
                queueSize = queueSize,
                banner = "coverflow-window-start index=$index",
            )
        }
    }

    fun mark(stage: String, details: String = "") {
        if (!BuildConfig.TRACK_SWITCH_PERFORMANCE) return
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

    fun recordCoverDraw(
        durationNs: Long,
        stateBuildNs: Long,
        laneDrawNs: Long,
        laneCount: Int,
        reflection: Boolean,
    ) {
        if (!BuildConfig.TRACK_SWITCH_PERFORMANCE) return
        val item = capture ?: return
        item.drawCount++
        item.drawTotalNs += durationNs
        item.maxDrawNs = max(item.maxDrawNs, durationNs)
        item.stateBuildTotalNs += stateBuildNs
        item.maxStateBuildNs = max(item.maxStateBuildNs, stateBuildNs)
        item.laneDrawTotalNs += laneDrawNs
        item.maxLaneDrawNs = max(item.maxLaneDrawNs, laneDrawNs)
        item.maxLaneCount = max(item.maxLaneCount, laneCount)
        item.reflectionDrawn = item.reflectionDrawn || reflection
    }

    fun recordCoverAnimatorFrame(intervalNs: Long) {
        if (!BuildConfig.TRACK_SWITCH_PERFORMANCE) return
        val item = capture ?: return
        if (intervalNs <= 0L) return
        item.animatorIntervals++
        item.animatorIntervalTotalNs += intervalNs
        item.maxAnimatorIntervalNs = max(item.maxAnimatorIntervalNs, intervalNs)
        if (intervalNs >= SPIKE_LOG_THRESHOLD_NS) {
            item.animatorSpikes++
            DiagnosticLog.event(
                "TrackPerf",
                "#${item.id} +${format(elapsedMs(item.startedNs))}ms anim-spike " +
                    "interval=${format(intervalNs / 1_000_000.0)}ms queueSize=${item.queueSize} " +
                    "drawMax=${format(item.maxDrawNs / 1_000_000.0)}ms " +
                    "hostMax=${format(item.maxHostUpdateNs / 1_000_000.0)}ms " +
                    "asyncActive=${item.activeAsyncWork} " +
                    "lastInvalidate=${item.lastInvalidateReason}",
            )
        }
    }

    fun recordCoverHostUpdate(durationNs: Long, queueSize: Int = 0) {
        if (!BuildConfig.TRACK_SWITCH_PERFORMANCE) return
        val item = capture ?: return
        item.hostUpdateCount++
        item.hostUpdateTotalNs += durationNs
        item.maxHostUpdateNs = max(item.maxHostUpdateNs, durationNs)
        if (queueSize > 0) item.lastHostQueueSize = queueSize
    }

    fun recordCoverQueueCompare(durationNs: Long, queueSize: Int, skippedBySameRef: Boolean) {
        if (!BuildConfig.TRACK_SWITCH_PERFORMANCE) return
        val item = capture ?: return
        item.queueCompareCount++
        item.queueCompareTotalNs += durationNs
        item.maxQueueCompareNs = max(item.maxQueueCompareNs, durationNs)
        if (skippedBySameRef) {
            item.queueCompareSameRef++
        } else {
            item.queueCompareFull++
        }
        if (durationNs >= 2_000_000L) {
            DiagnosticLog.event(
                "TrackPerf",
                "#${item.id} +${format(elapsedMs(item.startedNs))}ms queue-compare-slow " +
                    "duration=${format(durationNs / 1_000_000.0)}ms queueSize=$queueSize " +
                    "sameRef=$skippedBySameRef",
            )
        }
    }

    fun recordCoverInvalidate(reason: String) {
        if (!BuildConfig.TRACK_SWITCH_PERFORMANCE) return
        val item = capture ?: return
        item.invalidateCounts[reason] = (item.invalidateCounts[reason] ?: 0) + 1
        item.lastInvalidateReason = reason
    }

    fun coverAsyncStarted(kind: String) {
        if (!BuildConfig.TRACK_SWITCH_PERFORMANCE) return
        runOnMain {
            val item = capture ?: return@runOnMain
            item.activeAsyncWork++
            item.asyncStarted[kind] = (item.asyncStarted[kind] ?: 0) + 1
        }
    }

    fun coverAsyncFinished(kind: String, durationNs: Long, cacheHit: Boolean) {
        if (!BuildConfig.TRACK_SWITCH_PERFORMANCE) return
        runOnMain {
            val item = capture ?: return@runOnMain
            item.activeAsyncWork = (item.activeAsyncWork - 1).coerceAtLeast(0)
            item.asyncFinished[kind] = (item.asyncFinished[kind] ?: 0) + 1
            if (cacheHit) item.asyncCacheHits[kind] = (item.asyncCacheHits[kind] ?: 0) + 1
            item.asyncTotalNs[kind] = (item.asyncTotalNs[kind] ?: 0L) + durationNs
            item.asyncMaxNs[kind] = max(item.asyncMaxNs[kind] ?: 0L, durationNs)
        }
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
        val averageStateBuildMs = averageMs(item.stateBuildTotalNs, item.drawCount)
        val averageLaneDrawMs = averageMs(item.laneDrawTotalNs, item.drawCount)
        val averageAnimatorIntervalMs =
            averageMs(item.animatorIntervalTotalNs, item.animatorIntervals)
        val averageHostUpdateMs = averageMs(item.hostUpdateTotalNs, item.hostUpdateCount)
        val invalidations = item.invalidateCounts.entries
            .sortedByDescending { it.value }
            .joinToString(",") { "${it.key}:${it.value}" }
            .ifBlank { "none" }
        val asyncSummary = item.asyncStarted.keys
            .plus(item.asyncFinished.keys)
            .distinct()
            .joinToString(",") { kind ->
                val completed = item.asyncFinished[kind] ?: 0
                val total = item.asyncTotalNs[kind] ?: 0L
                val average = averageMs(total, completed)
                "$kind=${item.asyncStarted[kind] ?: 0}/$completed" +
                    " hit=${item.asyncCacheHits[kind] ?: 0}" +
                    " avg=${format(average)} max=${format((item.asyncMaxNs[kind] ?: 0L) / 1_000_000.0)}ms"
            }
            .ifBlank { "none" }
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
        val ctx = item.visualContext
        val averageQueueCompareMs = averageMs(item.queueCompareTotalNs, item.queueCompareCount)
        logCoverFlowDiag(
            item = item,
            reason = reason,
            averageDrawMs = averageDrawMs,
            averageAnimatorIntervalMs = averageAnimatorIntervalMs,
            averageHostUpdateMs = averageHostUpdateMs,
            averageQueueCompareMs = averageQueueCompareMs,
            asyncSummary = asyncSummary,
        )
        DiagnosticLog.event(
            "TrackPerf",
            "#${item.id} summary reason=$reason duration=${format(elapsedMs(item.startedNs))}ms; " +
                "trigger=${item.trigger}; queueSize=${item.queueSize}; " +
                "coverFlow=${ctx.coverFlowMode}; stage=${ctx.coverFlowStageActive}; " +
                "bg=${ctx.lowerBackground}; " +
                "frames=${item.frameIntervals}; avgFrame=${format(averageFrameMs)}ms; " +
                "maxFrame=${format(item.maxFrameNs / 1_000_000.0)}ms; " +
                "spikes24=${item.spikeCount}; over16=${item.over16}; over32=${item.over32}; " +
                "over50=${item.over50}; estimatedMissed=${item.estimatedMissedFrames}; " +
                "coverDraws=${item.drawCount}; avgDraw=${format(averageDrawMs)}ms; " +
                "maxDraw=${format(item.maxDrawNs / 1_000_000.0)}ms; " +
                "stateBuildAvg=${format(averageStateBuildMs)}ms; " +
                "stateBuildMax=${format(item.maxStateBuildNs / 1_000_000.0)}ms; " +
                "laneDrawAvg=${format(averageLaneDrawMs)}ms; " +
                "laneDrawMax=${format(item.maxLaneDrawNs / 1_000_000.0)}ms; " +
                "lanes=${item.maxLaneCount}; reflection=${item.reflectionDrawn}; " +
                "animCallbacks=${item.animatorIntervals}; " +
                "animAvg=${format(averageAnimatorIntervalMs)}ms; " +
                "animMax=${format(item.maxAnimatorIntervalNs / 1_000_000.0)}ms; " +
                "animSpikes24=${item.animatorSpikes}; " +
                "hostUpdates=${item.hostUpdateCount}; hostAvg=${format(averageHostUpdateMs)}ms; " +
                "hostMax=${format(item.maxHostUpdateNs / 1_000_000.0)}ms; " +
                "queueCompare=${item.queueCompareCount}; " +
                "queueCompareSameRef=${item.queueCompareSameRef}; " +
                "queueCompareFull=${item.queueCompareFull}; " +
                "queueCompareAvg=${format(averageQueueCompareMs)}ms; " +
                "queueCompareMax=${format(item.maxQueueCompareNs / 1_000_000.0)}ms; " +
                "invalidates=$invalidations; async=$asyncSummary; " +
                "timeline=$timeline",
        )
    }

    private fun logCoverFlowDiag(
        item: Capture,
        reason: String,
        averageDrawMs: Double,
        averageAnimatorIntervalMs: Double,
        averageHostUpdateMs: Double,
        averageQueueCompareMs: Double,
        asyncSummary: String,
    ) {
        DiagnosticLog.event(
            "TrackPerf",
            "#${item.id} coverflow-diag reason=$reason queueSize=${item.queueSize} " +
                "hostUpdates=${item.hostUpdateCount} hostAvg=${format(averageHostUpdateMs)}ms " +
                "hostMax=${format(item.maxHostUpdateNs / 1_000_000.0)}ms " +
                "avgDraw=${format(averageDrawMs)}ms " +
                "maxDraw=${format(item.maxDrawNs / 1_000_000.0)}ms " +
                "animAvg=${format(averageAnimatorIntervalMs)}ms " +
                "animMax=${format(item.maxAnimatorIntervalNs / 1_000_000.0)}ms " +
                "animSpikes24=${item.animatorSpikes} " +
                "queueCompare=${item.queueCompareCount} " +
                "queueCompareMax=${format(item.maxQueueCompareNs / 1_000_000.0)}ms " +
                "coverLoad=${asyncKindSummary(item, "cover-load")} " +
                "reflectionBake=${asyncKindSummary(item, "reflection-bake")} " +
                "hostPreload=${asyncKindSummary(item, "host-preload")} " +
                "asyncAll=$asyncSummary",
        )
    }

    private fun asyncKindSummary(item: Capture, kind: String): String {
        val started = item.asyncStarted[kind] ?: 0
        val finished = item.asyncFinished[kind] ?: 0
        val hits = item.asyncCacheHits[kind] ?: 0
        val maxMs = format((item.asyncMaxNs[kind] ?: 0L) / 1_000_000.0)
        return "$started/$finished hit=$hits max=${maxMs}ms"
    }

    private fun startCapture(trigger: String, queueSize: Int, banner: String) {
        markDedupeAtMs.clear()
        val ctx = visualContext.copy(queueSize = queueSize)
        val item = Capture(
            id = ++nextId,
            startedNs = SystemClock.elapsedRealtimeNanos(),
            trigger = trigger,
            visualContext = ctx,
            queueSize = queueSize,
        )
        capture = item
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Trace.beginAsyncSection("MicaTrackSwitch", item.id)
        }
        DiagnosticLog.event(
            "TrackPerf",
            "#${item.id} $banner trigger=$trigger " +
                "coverFlow=${ctx.coverFlowMode} stage=${ctx.coverFlowStageActive} " +
                "bg=${ctx.lowerBackground} motion=${ctx.motionEnabled} queueSize=$queueSize",
        )
        Choreographer.getInstance().postFrameCallback(item.frameCallback)
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
                        "interval=${format(intervalNs / 1_000_000.0)}ms missed~$missed " +
                        "queueSize=${item.queueSize} " +
                        "avgDraw=${format(averageMs(item.drawTotalNs, item.drawCount))}ms " +
                        "drawMax=${format(item.maxDrawNs / 1_000_000.0)}ms " +
                        "hostUpdates=${item.hostUpdateCount} " +
                        "hostMax=${format(item.maxHostUpdateNs / 1_000_000.0)}ms " +
                        "animSpikes24=${item.animatorSpikes} " +
                        "asyncActive=${item.activeAsyncWork} " +
                        "coverLoad=${asyncKindSummary(item, "cover-load")} " +
                        "reflectionBake=${asyncKindSummary(item, "reflection-bake")} " +
                        "lastInvalidate=${item.lastInvalidateReason}",
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

    private fun averageMs(totalNs: Long, count: Int): Double =
        if (count > 0) totalNs.toDouble() / count / 1_000_000.0 else 0.0

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
        val queueSize: Int,
    ) {
        var lastHostQueueSize: Int = queueSize
        var queueCompareCount = 0
        var queueCompareTotalNs = 0L
        var maxQueueCompareNs = 0L
        var queueCompareSameRef = 0
        var queueCompareFull = 0
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
        var stateBuildTotalNs = 0L
        var maxStateBuildNs = 0L
        var laneDrawTotalNs = 0L
        var maxLaneDrawNs = 0L
        var maxLaneCount = 0
        var reflectionDrawn = false
        var animatorIntervals = 0
        var animatorIntervalTotalNs = 0L
        var maxAnimatorIntervalNs = 0L
        var animatorSpikes = 0
        var hostUpdateCount = 0
        var hostUpdateTotalNs = 0L
        var maxHostUpdateNs = 0L
        var activeAsyncWork = 0
        var lastInvalidateReason = "none"
        val invalidateCounts = linkedMapOf<String, Int>()
        val asyncStarted = linkedMapOf<String, Int>()
        val asyncFinished = linkedMapOf<String, Int>()
        val asyncCacheHits = linkedMapOf<String, Int>()
        val asyncTotalNs = linkedMapOf<String, Long>()
        val asyncMaxNs = linkedMapOf<String, Long>()
        val stages = mutableListOf<Stage>()
        val frameCallback = Choreographer.FrameCallback { frameTimeNs ->
            onFrame(this, frameTimeNs)
        }
    }
}
