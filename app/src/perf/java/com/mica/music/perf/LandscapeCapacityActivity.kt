package com.mica.music.perf

import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.mica.music.data.PlaybackQueueModel
import com.mica.music.data.PlayerCoverFlowMode
import com.mica.music.imaging.CoverDecodeTarget
import com.mica.music.media.MicaCompositePlayer
import com.mica.music.media.PlaybackQueueSnapshot
import com.mica.music.ui.components.PlaybackQueueSheet
import com.mica.music.ui.screens.player.view.CoverFlowCarouselView
import com.mica.music.ui.screens.player.view.CoverFlowReflectionBake
import com.mica.music.ui.screens.player.view.PhotoStackTransitionFramePx
import com.mica.music.ui.screens.player.view.PhotoStackTransitionView
import com.mica.music.ui.theme.MicaTheme
import org.json.JSONObject
import java.io.File

/**
 * Exported only by the perf manifest. It drives production queue/artwork components with a
 * stable 10,000-song list and writes machine-readable samples under externalFiles/capacity.
 */
@UnstableApi
class LandscapeCapacityActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var queue: List<com.mica.music.data.Song>
    private lateinit var reportFile: File
    private lateinit var doneFile: File
    private var activeView: View? = null
    private var retainedQueueModel: Any? = null
    private var retainedHandoffSnapshot: PlaybackQueueSnapshot? = null
    private var handoffPlayer: ExoPlayer? = null
    private var mode: String = "coverflow"
    private var startIndex: Int = 5_000
    private var steps: Int = 200
    private var intervalMs: Long = 120L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EXTRA_MODE)?.lowercase() ?: "coverflow"
        steps = intent.getIntExtra(EXTRA_STEPS, 200).coerceIn(1, 2_000)
        intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, 120L).coerceIn(16L, 2_000L)
        val size = intent.getIntExtra(EXTRA_QUEUE_SIZE, CapacityQueueFactory.DefaultSize)
            .coerceIn(1, 100_000)
        startIndex = intent.getIntExtra(EXTRA_START_INDEX, size / 2).coerceIn(0, size - 1)

        val outputDir = (getExternalFilesDir("capacity") ?: File(filesDir, "capacity"))
            .apply { mkdirs() }
        reportFile = File(outputDir, "capacity-$mode.jsonl").apply { writeText("") }
        doneFile = File(outputDir, "capacity-$mode.done").apply { delete() }

        val queueStartNs = SystemClock.elapsedRealtimeNanos()
        queue = CapacityQueueFactory.create(size)
        sample(
            phase = "queue-created",
            index = startIndex,
            extra = mapOf(
                "queueCreateMs" to elapsedMs(queueStartNs),
                "lyricsPolicy" to "lazy-not-loaded",
            ),
        )

        when (mode) {
            "coverflow" -> startCoverFlow(PlayerCoverFlowMode.PAUSE_FOLD)
            "retro" -> startCoverFlow(PlayerCoverFlowMode.RETRO_3D)
            "photo" -> startPhotoStack()
            "queue" -> startQueuePanel()
            "commit" -> runQueueCommit()
            "handoff" -> runPlaybackHandoffCapture()
            else -> fail("Unknown mode: $mode")
        }
    }

    private fun startCoverFlow(coverMode: PlayerCoverFlowMode) {
        val container = FrameLayout(this).apply { setBackgroundColor(0xFF101018.toInt()) }
        val view = CoverFlowCarouselView(this).apply {
            setMotionEnabled(false)
            setGesturesEnabled(false)
            setFallbackColor(0xFF242438.toInt())
            setScreenWidthPx(resources.displayMetrics.widthPixels.toFloat())
            setCoverDecodeTarget(CoverDecodeTarget.fromPixels(640f, 640f))
            setCoverSizePx(640f, 640f)
            setCoverStartPaddingPx(48f)
            setReflectionGapPx(18f)
            setCameraDistancePx(1_600f)
            setCoverFlowMode(coverMode)
            setFoldProgress(1f)
            applyHostUpdate(queue, startIndex, stageActive = true)
        }
        activeView = view
        configureActiveViewForViewport()
        container.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(container)
        browse { index -> view.applyHostUpdate(queue, index, stageActive = true) }
    }

    private fun startPhotoStack() {
        val container = FrameLayout(this).apply { setBackgroundColor(0xFF18130F.toInt()) }
        val view = PhotoStackTransitionView(this).apply {
            setMotionEnabled(false)
            setGesturesEnabled(false)
            setFrame(
                PhotoStackTransitionFramePx(
                    slotWidthPx = 960f,
                    slotHeightPx = 920f,
                    cardTopInsetPx = 0f,
                    cardWidthPx = 620f,
                    cardHeightPx = 760f,
                    artworkInsetTopPx = 44f,
                    artworkInsetHorizontalPx = 44f,
                    waveformHeightPx = 88f,
                ),
            )
            setPlaybackState(
                sliderValue = 60f,
                rangeStart = 0f,
                rangeEnd = 240f,
                isPlaying = false,
                spectrumEnabled = false,
                onSeekValueChange = {},
                onSeekFinished = {},
            )
            setCallbacks({ _ -> }, {}, {}, {}, null)
            applyHostUpdate(queue, startIndex, stageActive = true)
        }
        activeView = view
        configureActiveViewForViewport()
        container.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(container)
        browse { index -> view.applyHostUpdate(queue, index, stageActive = true) }
    }

    private fun startQueuePanel() {
        setContent {
            MicaTheme(darkTheme = true) {
                Surface {
                    PlaybackQueueSheet(
                        queue = queue,
                        currentIndex = startIndex,
                        isPlaying = false,
                        onDismiss = {},
                        onPlayAt = {},
                        onMove = { _, _ -> },
                        onRemove = {},
                        landscape = resources.configuration.orientation ==
                            android.content.res.Configuration.ORIENTATION_LANDSCAPE,
                    )
                }
            }
        }
        handler.postDelayed({
            sample("queue-composed", startIndex)
            complete()
        }, 2_000L)
    }

    private fun runQueueCommit() {
        val initial = PlaybackQueueModel().linearQueue(queue, startIndex)
        sample("commit-before", startIndex)
        val startedNs = SystemClock.elapsedRealtimeNanos()
        val moved = initial.move(queue.lastIndex, 0)
        retainedQueueModel = moved
        sample(
            phase = "commit-after",
            index = moved.currentIndex,
            extra = mapOf(
                "commitMs" to elapsedMs(startedNs),
                "firstSongId" to moved.queue.first().id,
                "queueSizeAfter" to moved.queue.size,
            ),
        )
        complete()
    }

    /**
     * Measures the exact production handoff capture operation: the Exo timeline already owns the
     * MediaItems, and [MicaCompositePlayer.playbackQueueSnapshot] must only copy their references.
     * This intentionally excludes the memory of constructing a replacement ExoPlayer stack.
     */
    private fun runPlaybackHandoffCapture() {
        val items = List(queue.size) { index ->
            MediaItem.Builder()
                .setMediaId(queue[index].id)
                .setUri("file:///capacity/${queue[index].id}.flac")
                .build()
        }
        val exo = ExoPlayer.Builder(this).build()
        handoffPlayer = exo
        val composite = MicaCompositePlayer(exo)
        composite.setMediaItems(items, startIndex, 123_456L)
        Runtime.getRuntime().gc()
        handler.postDelayed({
            val runtime = Runtime.getRuntime()
            val beforeBytes = runtime.totalMemory() - runtime.freeMemory()
            val beforeAllocatedBytes = Debug.getRuntimeStat("art.gc.bytes-allocated")
                ?.toLongOrNull() ?: -1L
            val startedNs = SystemClock.elapsedRealtimeNanos()
            val snapshot = composite.playbackQueueSnapshot()
            val captureMs = elapsedMs(startedNs)
            val afterBytes = runtime.totalMemory() - runtime.freeMemory()
            val afterAllocatedBytes = Debug.getRuntimeStat("art.gc.bytes-allocated")
                ?.toLongOrNull() ?: -1L
            val allocatedDeltaBytes = if (beforeAllocatedBytes >= 0L && afterAllocatedBytes >= 0L) {
                afterAllocatedBytes - beforeAllocatedBytes
            } else {
                -1L
            }
            retainedHandoffSnapshot = snapshot
            val referencesPreserved = snapshot.items.indices.all { index ->
                snapshot.items[index] === exo.getMediaItemAt(index)
            }
            sample(
                phase = "handoff-captured",
                index = snapshot.currentIndex,
                extra = mapOf(
                    "handoffCaptureMs" to captureMs,
                    "handoffJavaDeltaBytes" to (afterBytes - beforeBytes),
                    "handoffAllocatedDeltaBytes" to allocatedDeltaBytes,
                    "handoffItemCount" to snapshot.items.size,
                    "handoffReferencesPreserved" to referencesPreserved,
                    "handoffWithin5Mb" to
                        (allocatedDeltaBytes in 0L..(5L * 1024L * 1024L)),
                ),
            )
            complete()
        }, 1_000L)
    }

    private fun browse(update: (Int) -> Unit) {
        var step = 0
        val runnable = object : Runnable {
            override fun run() {
                if (step >= steps) {
                    handler.postDelayed({
                        sample("browse-settled", indexForStep(step - 1))
                        releaseActiveView()
                        Runtime.getRuntime().gc()
                        handler.postDelayed({
                            sample("released-after-gc", indexForStep(step - 1))
                            complete()
                        }, 2_000L)
                    }, 1_500L)
                    return
                }
                val index = indexForStep(step)
                update(index)
                if (step == 0 || step % 25 == 0 || step == steps - 1) {
                    sample("browse", index, mapOf("step" to step))
                }
                step++
                handler.postDelayed(this, intervalMs)
            }
        }
        handler.post(runnable)
    }

    private fun indexForStep(step: Int): Int =
        (startIndex.toLong() + step.coerceAtLeast(0).toLong() * 37L)
            .mod(queue.size.toLong())
            .toInt()

    private fun sample(phase: String, index: Int, extra: Map<String, Any?> = emptyMap()) {
        val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val runtime = Runtime.getRuntime()
        val diagnostic = when (val view = activeView) {
            is CoverFlowCarouselView -> view.diagnosticArtworkState()
            is PhotoStackTransitionView -> view.diagnosticArtworkState()
            else -> null
        }
        val json = JSONObject().apply {
            put("timestampMs", System.currentTimeMillis())
            put("mode", mode)
            put("phase", phase)
            put("index", index)
            put("queueSize", queue.size)
            put("viewportWidthPx", resources.displayMetrics.widthPixels)
            put("viewportHeightPx", resources.displayMetrics.heightPixels)
            put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            put("javaHeapPssKb", memory.getMemoryStat("summary.java-heap").toLongOrNull() ?: -1L)
            put("nativeHeapPssKb", memory.getMemoryStat("summary.native-heap").toLongOrNull() ?: -1L)
            put("graphicsPssKb", memory.getMemoryStat("summary.graphics").toLongOrNull() ?: -1L)
            put("totalPssKb", memory.totalPss)
            put("reflectionCacheBytes", CoverFlowReflectionBake.cacheSizeBytes())
            put("retainedBitmapCount", diagnostic?.retainedBitmapCount ?: -1)
            put("pendingLoadCount", diagnostic?.pendingLoadCount ?: -1)
            put("rendererQueueSize", diagnostic?.queueSize ?: -1)
            extra.forEach { (key, value) -> put(key, value) }
        }
        reportFile.appendText(json.toString() + "\n")
        Log.i(TAG, json.toString())
    }

    private fun releaseActiveView() {
        when (val view = activeView) {
            is CoverFlowCarouselView -> view.release()
            is PhotoStackTransitionView -> view.release()
        }
        (activeView?.parent as? ViewGroup)?.removeView(activeView)
        activeView = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configureActiveViewForViewport()
        sample("viewport-changed", startIndex)
    }

    private fun configureActiveViewForViewport() {
        val width = resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
        val height = resources.displayMetrics.heightPixels.toFloat().coerceAtLeast(1f)
        val shortEdge = minOf(width, height)
        when (val view = activeView) {
            is CoverFlowCarouselView -> {
                val coverSize = (shortEdge * 0.56f).coerceIn(320f, 900f)
                view.setScreenWidthPx(width)
                view.setCoverSizePx(coverSize, coverSize)
                view.setCoverDecodeTarget(CoverDecodeTarget.fromPixels(coverSize, coverSize))
            }
            is PhotoStackTransitionView -> {
                val cardWidth = (shortEdge * 0.54f).coerceIn(320f, 760f)
                view.setFrame(
                    PhotoStackTransitionFramePx(
                        slotWidthPx = width * 0.72f,
                        slotHeightPx = height * 0.88f,
                        cardTopInsetPx = 0f,
                        cardWidthPx = cardWidth,
                        cardHeightPx = cardWidth * 1.22f,
                        artworkInsetTopPx = cardWidth * 0.07f,
                        artworkInsetHorizontalPx = cardWidth * 0.07f,
                        waveformHeightPx = cardWidth * 0.14f,
                    ),
                )
            }
        }
    }

    private fun complete() {
        doneFile.writeText("complete\n")
        Log.i(TAG, "complete mode=$mode report=${reportFile.absolutePath}")
    }

    private fun fail(message: String) {
        reportFile.appendText(JSONObject(mapOf("mode" to mode, "error" to message)).toString() + "\n")
        doneFile.writeText("failed\n")
        Log.e(TAG, message)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releaseActiveView()
        retainedQueueModel = null
        retainedHandoffSnapshot = null
        handoffPlayer?.release()
        handoffPlayer = null
        super.onDestroy()
    }

    private fun elapsedMs(startedNs: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000.0

    companion object {
        private const val TAG = "MicaCapacity"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_STEPS = "steps"
        private const val EXTRA_INTERVAL_MS = "intervalMs"
        private const val EXTRA_QUEUE_SIZE = "queueSize"
        private const val EXTRA_START_INDEX = "startIndex"
    }
}
