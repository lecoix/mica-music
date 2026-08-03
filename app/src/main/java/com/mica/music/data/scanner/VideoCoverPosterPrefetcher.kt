package com.mica.music.data.scanner

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.media.MediaMetadataRetriever
import com.mica.music.util.DiagnosticLog
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * After folder scan publishes matched video covers, extract first-frame posters
 * on a single background thread (cancel prior job, skip cache hits).
 */
internal object VideoCoverPosterPrefetcher {
    private const val MaxEdgePx = 720

    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        ThreadFactory { runnable ->
            Thread(runnable, "video-cover-poster-prefetch").apply { isDaemon = true }
        },
    )
    private val generation = AtomicLong(0)
    private val enqueueLock = Any()
    private var latestTask: FutureTask<Unit>? = null

    fun cancel() {
        synchronized(enqueueLock) {
            generation.incrementAndGet()
            latestTask?.let { task ->
                task.cancel(true)
                executor.remove(task)
            }
            latestTask = null
        }
    }

    fun enqueue(context: Context, uris: Collection<String>) {
        val unique = uris.mapNotNull { it.takeIf(String::isNotBlank) }.distinct()
        val appContext = context.applicationContext
        synchronized(enqueueLock) {
            val gen = generation.incrementAndGet()
            latestTask?.let { task ->
                task.cancel(true)
                executor.remove(task)
            }
            latestTask = null
            if (unique.isEmpty()) return

            lateinit var task: FutureTask<Unit>
            task = FutureTask {
                try {
                    val stats = prefetchVideoCoverPosters(
                        uris = unique,
                        isCached = { VideoCoverPosterStore.isCached(appContext, it) },
                        extract = { extractFirstFrame(appContext, it) },
                        store = { uri, bitmap -> VideoCoverPosterStore.put(appContext, uri, bitmap) },
                        shouldContinue = { generation.get() == gen },
                    )
                    DiagnosticLog.event(
                        "VideoCover",
                        "prefetch gen=$gen stored=${stats.stored} skipped=${stats.skipped} " +
                            "failed=${stats.failed} total=${stats.total}",
                    )
                } finally {
                    synchronized(enqueueLock) {
                        if (latestTask === task) latestTask = null
                    }
                }
            }
            latestTask = task
            executor.execute(task)
        }
    }

    internal data class PrefetchStats(
        val total: Int,
        val skipped: Int,
        val stored: Int,
        val failed: Int,
    )

    internal fun prefetchVideoCoverPosters(
        uris: Collection<String>,
        isCached: (String) -> Boolean,
        extract: (String) -> Bitmap?,
        store: (String, Bitmap) -> Unit,
        shouldContinue: () -> Boolean = { true },
    ): PrefetchStats {
        val unique = uris.mapNotNull { it.takeIf(String::isNotBlank) }.distinct()
        var skipped = 0
        var stored = 0
        var failed = 0
        for (uri in unique) {
            if (!shouldContinue()) break
            if (isCached(uri)) {
                skipped++
                continue
            }
            val bitmap = extract(uri)
            if (!shouldContinue()) {
                recycleIfNeeded(bitmap)
                break
            }
            if (bitmap == null || bitmap.isRecycled) {
                failed++
                continue
            }
            store(uri, bitmap)
            stored++
        }
        return PrefetchStats(
            total = unique.size,
            skipped = skipped,
            stored = stored,
            failed = failed,
        )
    }

    private fun recycleIfNeeded(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) {
            runCatching { bitmap.recycle() }
        }
    }

    private fun extractFirstFrame(context: Context, uriString: String): Bitmap? {
        val uri = Uri.parse(uriString)
        frameFromRetriever { it.setDataSource(context, uri) }?.let { return it }
        return context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            frameFromRetriever { it.setDataSource(pfd.fileDescriptor) }
        }
    }

    private fun frameFromRetriever(setSource: (MediaMetadataRetriever) -> Unit): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            setSource(retriever)
            val raw = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: return null
            downscale(raw, MaxEdgePx)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun downscale(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        if (scaled !== bitmap && !bitmap.isRecycled) {
            bitmap.recycle()
        }
        return scaled
    }
}
